package jp.juggler.testvideotranscode

import android.os.SystemClock
import com.otaliastudios.transcoder.Transcoder
import com.otaliastudios.transcoder.TranscoderListener
import com.otaliastudios.transcoder.common.Size
import com.otaliastudios.transcoder.strategy.DefaultAudioStrategy
import com.otaliastudios.transcoder.strategy.DefaultVideoStrategy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
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

    private val videoStrategy = DefaultVideoStrategy.Builder()
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
        .build()

    private val audioStrategy = DefaultAudioStrategy.Builder()
        .channels(2)
        .sampleRate(48000)
        .bitRate(targetAudioBitrate)
        .build()

    /**
     * 動画をトランスコードする
     * - 指定サイズ(長辺、短辺)に収まるようにする
     * - 再エンコードが発生する場合にビットレートを指定する
     * - この処理をキャンセルしたい場合はコルーチンのキャンセルを使うこと
     * - outFileは処理失敗時などに削除されるかもしれない
     *
     * キャンセルの例
     *  val videoTask = async{ transcodeVideo(...) }
     *  (UIイベントからのキャンセル) videoTask.cancel()
     *  val compressedFile = videoTask.await() // ここでキャンセル例外が戻る
     */
    @Suppress("BlockingMethodInNonBlockingContext")
    suspend fun transcodeVideo(
        inFile: File,
        outFile: File,
        // 開始からの経過時間と進捗率0.0～1.0
        onProgress: (Long,Double) -> Unit,
    ) = withContext(Dispatchers.IO) {
        val inStream = FileInputStream(inFile)
        try {
            suspendCancellableCoroutine<File> { cont ->
                val timeStart = SystemClock.elapsedRealtime()

                val listener = object : TranscoderListener {
                    override fun onTranscodeCanceled() {
                        cont.resumeWithException(CancellationException("transcode cancelled."))
                    }

                    override fun onTranscodeFailed(exception: Throwable) {
                        cont.resumeWithException(exception)
                    }

                    override fun onTranscodeProgress(progress: Double) {
                        onProgress( SystemClock.elapsedRealtime()-timeStart,progress)
                    }

                    override fun onTranscodeCompleted(successCode: Int) {
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

                val future = Transcoder.into(outFile.canonicalPath)
                    .setVideoTrackStrategy(videoStrategy)
                    .setAudioTrackStrategy(audioStrategy)
                    .setListener(listener)
                    // ファイルパスをそのまま渡すと落ちる。(com.otaliastudios:transcoder:0.10.4)
                    // FilePathDataSource が isInitializedを実装しておらず、
                    // DataSourceWrapper の isInitialized が未設定のmSourceにアクセスしようとする。
                    .addDataSource(inStream.fd)
                    .transcode()

                cont.invokeOnCancellation {
                    future.cancel(true)
                }
            }
        } catch (ex: Throwable) {
            outFile.delete()
            throw ex
        } finally {
            try {
                inStream.close()
            } catch (ignored: Throwable) {
            }
        }
    }
}
