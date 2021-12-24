package jp.juggler.testvideotranscode

import com.otaliastudios.transcoder.Transcoder
import com.otaliastudios.transcoder.TranscoderListener
import com.otaliastudios.transcoder.common.Size
import com.otaliastudios.transcoder.strategy.DefaultAudioStrategy
import com.otaliastudios.transcoder.strategy.DefaultVideoStrategy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import java.io.FileInputStream
import kotlin.coroutines.resumeWithException
import kotlin.math.min

object VideoTranscoder {

    private const val limitSizeMajor = 960f
    private const val limitSizeMinor = 540f
    private const val limitFrameRate = 30
    private const val targetVideoBitrate = 300000L
    private const val targetAudioBitrate = 60000L

    /**
     * 動画をトランスコードする
     * - 指定サイズ(長辺、短辺)に収まるようにする
     * - 再エンコードが発生する場合にビットレートを指定する
     * - この処理をキャンセルしたい場合はコルーチンのキャンセルを使うこと
     * - outFileは
     *
     * キャンセルの例
     *  val videoTask = async{ transcodeVideo(...) }
     *  (UIイベントからのキャンセル) videoTask.cancel()
     *  val compressedFile = videoTask.await() // ここでキャンセル例外が戻る
     */
    suspend fun transcodeVideo(
        inFile: File,
        outFile: File,
        onProgress: (Double) -> Unit,
    ) = suspendCancellableCoroutine<File> { cont ->

        val videoStrategy = DefaultVideoStrategy.Builder()
            .addResizer { inSize ->
                if (inSize.major < 1 || inSize.minor < 1) {
                    // ゼロ除算回避：元の動画が小さすぎるならリサイズは必要ない
                    inSize
                } else {
                    val scaleMajor = limitSizeMajor / inSize.major.toFloat()
                    val scaleMinor = limitSizeMinor / inSize.minor.toFloat()
                    val scale = min(scaleMajor, scaleMinor)
                    if (scale >= 1f) {
                        // 拡大はしない
                        inSize
                    } else {
                        // 長辺と短辺が指定以内に収まるよう縮小する
                        Size(
                            min(limitSizeMajor, inSize.major * scale + 0.5f).toInt(),
                            min(limitSizeMinor, inSize.minor * scale + 0.5f).toInt()
                        )
                    }
                }
            }
            .frameRate(limitFrameRate)
            .bitRate(targetVideoBitrate)

        val audioStrategy = DefaultAudioStrategy.Builder()
            .channels(2)
            .sampleRate(48000)
            .bitRate(targetAudioBitrate)

        val inStream = FileInputStream(inFile)
        fun closeInput() = try {
            inStream.close()
        } catch (ex: Throwable) {
        }

        val listener = object : TranscoderListener {
            override fun onTranscodeCanceled() {
                closeInput()
                outFile.delete()
                cont.resumeWithException(CancellationException("transcode cancelled."))
            }

            override fun onTranscodeFailed(exception: Throwable) {
                closeInput()
                outFile.delete()
                cont.resumeWithException(exception)
            }

            override fun onTranscodeProgress(progress: Double) {
                onProgress(progress)
            }

            override fun onTranscodeCompleted(successCode: Int) {
                closeInput()
                val compressedFile = when (successCode) {
                    Transcoder.SUCCESS_TRANSCODED ->
                        outFile
                    else /* Transcoder.SUCCESS_NOT_NEEDED */ -> {
                        outFile.delete()
                        inFile
                    }
                }
                cont.resumeWith(Result.success(compressedFile))
            }
        }
        val transcodeBuilder = Transcoder.into(outFile.canonicalPath)
            .setListener(listener)
            .setVideoTrackStrategy(videoStrategy.build())
            .setAudioTrackStrategy(audioStrategy.build())
            .setValidator { _, _ ->
                // ストラテジー側の判断に関係なくスキップしない
                true
            }
            // ファイルパスをそのまま渡すと落ちる。
            // FilePathDataSource が isInitializedを実装しておらず、
            // DataSourceWrapper の isInitialized が未設定のmSourceにアクセスしようとする。
            .addDataSource(inStream.fd)

        val future = transcodeBuilder.transcode()
        cont.invokeOnCancellation {
            future.cancel(true)
        }
    }
}
