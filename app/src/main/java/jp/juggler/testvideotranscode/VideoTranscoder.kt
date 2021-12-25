package jp.juggler.testvideotranscode

import android.annotation.SuppressLint
import android.content.Context
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMetadataRetriever.METADATA_KEY_BITRATE
import android.media.MediaMetadataRetriever.METADATA_KEY_DURATION
import android.media.MediaMetadataRetriever.METADATA_KEY_SAMPLERATE
import android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_FRAME_COUNT
import android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT
import android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION
import android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH
import android.net.Uri
import android.os.Build
import android.util.Log
import android.util.Size
import com.linkedin.android.litr.MediaTransformer
import com.linkedin.android.litr.TransformationListener
import com.linkedin.android.litr.TransformationOptions
import com.linkedin.android.litr.analytics.TrackTransformationInfo
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resumeWithException
import kotlin.math.max
import kotlin.math.min

object VideoTranscoder {

    private const val TAG = ActMain.TAG

    private const val limitSizeLonger = 960f
    private const val limitSizeShorter = 540f
    private const val limitAudioSampleRate = 44100
    private const val targetVideoBitrate = 300_000
    private const val targetAudioBitrate = 64_000

    private val requestIdSeed = AtomicInteger()

    // MediaTransformer シングルトン
    @SuppressLint("StaticFieldLeak")
    private var mediaTransformerNullable: MediaTransformer? = null

    /**
     * MediaTransformer シングルトンを生成する
     * - 特に解放しなくて良いような気がする
     */
    private fun prepareMediaTransformer(context: Context): MediaTransformer {
        // double-check before/after lock
        mediaTransformerNullable?.let { return it }
        synchronized(this) {
            mediaTransformerNullable?.let { return it }
            // create instance
            return MediaTransformer(context.applicationContext)
                .also { mediaTransformerNullable = it }
        }
    }

    private fun MediaMetadataRetriever.int(key: Int) = extractMetadata(key)?.toIntOrNull()
    private fun MediaMetadataRetriever.long(key: Int) = extractMetadata(key)?.toLongOrNull()

    @Suppress("SameParameterValue")
    private fun logVideoInfo(inFile: File, caption: String) {
        val mmr = MediaMetadataRetriever()
        try {
            mmr.setDataSource(inFile.canonicalPath)
            val rotation = mmr.int(METADATA_KEY_VIDEO_ROTATION) ?: 0
            val width = mmr.int(METADATA_KEY_VIDEO_WIDTH) ?: 0
            val height = mmr.int(METADATA_KEY_VIDEO_HEIGHT) ?: 0
            val bitrate = mmr.int(METADATA_KEY_BITRATE) // may null
            val frameCount = if(Build.VERSION.SDK_INT >= 28){
                mmr.int(METADATA_KEY_VIDEO_FRAME_COUNT)
            }else{
                null
            }
            val duration = mmr.long(METADATA_KEY_DURATION)?.toFloat()?.div(1000)
            val frameRatio =
                if (frameCount != null && frameCount > 0 && duration != null && duration > 0.1f) {
                    frameCount.toFloat().div(duration)
                } else {
                    null
                }

            val audioSampleRate = if (Build.VERSION.SDK_INT >= 31) {
                mmr.int(METADATA_KEY_SAMPLERATE) // may null
            } else {
                null
            }

            Log.i(
                TAG,
                "$caption rotation=$rotation, width=$width, height=$height, frameRatio=$frameRatio, bitrate=$bitrate, audioSampleRate=$audioSampleRate"
            )
        } finally {
            mmr.release()
        }
    }

    /**
     * 動画のファイルサイズが十分に小さいなら真
     */
    private fun isSmallEnough(inFile: File, mmr: MediaMetadataRetriever): Boolean {
        val fileSize = inFile.length()
        // ファイルサイズを取得できないならエラー
        if (fileSize <= 0L) error("too small file. ${inFile.canonicalPath}")
        // ファイルサイズが500KB以内ならビットレートを気にしない
        if (fileSize < 500_000) return true

        // 動画の時間長を秒数(少数あり)にする
        val duration = mmr.long(METADATA_KEY_DURATION)?.toFloat()?.div(1000)
            ?: return false
        // 時間帳が短すぎる(&ファイルサイズが1MB以上)なら再エンコード必要とする
        if (duration < 0.1f) return false
        // bpsを計算
        val bpsActual = fileSize.toFloat().div(duration).times(8).toInt()
        val bpsLimit = (targetVideoBitrate + targetAudioBitrate).plus(1024)
        Log.i(
            TAG,
            "isSmallEnough duration=$duration, bps=$bpsActual/$bpsLimit"
        )
        return bpsActual < bpsLimit
    }

    private fun getScaledSize(inSize: Size): Size? {
        val inSizeLonger = max(inSize.width, inSize.height)
        val inSizeShorter = min(inSize.width, inSize.height)
        // ゼロ除算対策
        if (inSizeShorter < 1) return null
        val scale = min(
            limitSizeLonger / inSizeLonger.toFloat(),
            limitSizeShorter / inSizeShorter.toFloat()
        )
        // 拡大はしない
        if (scale >= 1f) return null

        return if (inSize.width >= inSize.height) {
            Size(
                min(limitSizeLonger, inSize.width.toFloat() * scale + 0.5f).toInt(),
                min(limitSizeShorter, inSize.height.toFloat() * scale + 0.5f).toInt(),
            )
        } else {
            Size(
                min(limitSizeShorter, inSize.width.toFloat() * scale + 0.5f).toInt(),
                min(limitSizeLonger, inSize.height.toFloat() * scale + 0.5f).toInt(),
            )
        }
    }

    private fun createListener(
        cont: CancellableContinuation<Unit>,
        onProgress: (Float) -> Unit,
    ) = object : TransformationListener {
        override fun onStarted(id: String) {
            Log.v(TAG, "[$id]onStarted called.")
        }

        override fun onProgress(id: String, progress: Float) {
            // 呼び出される頻度は十分に低かった
            onProgress(progress)
        }

        override fun onCompleted(
            id: String,
            trackTransformationInfos: List<TrackTransformationInfo>?
        ) {
            Log.v(TAG, "[$id]onCompleted called.")
            cont.resumeWith(Result.success(Unit))
        }

        override fun onCancelled(
            id: String,
            trackTransformationInfos: List<TrackTransformationInfo>?
        ) {
            Log.w(TAG, "[$id]onCancelled called!")
            cont.resumeWithException(CancellationException("transcode cancelled."))
        }

        override fun onError(
            id: String,
            cause: Throwable?,
            trackTransformationInfos: List<TrackTransformationInfo>?
        ) {
            Log.w(TAG, "[$id]onError called!")
            cont.resumeWithException(
                cause ?: IllegalStateException("transcode error?")
            )
        }
    }

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
     *
     *  実装メモ：
     *
     *  縮小について
     *  - 何も指定しない場合、videoFormatに指定したサイズに自動でリサイズされる
     *
     *  回転について
     *  - 何も指定しない場合、入力の回転情報は出力にそのままコピーされる
     *  - MediaCodecに切り替えるより前と同様の挙動であり、影響はないはず
     */
    suspend fun transcodeVideo(
        context: Context,
        inFile: File,
        outFile: File,
        // 進捗率0.0～1.0
        onProgress: (Float) -> Unit,
    ): File {
        val mmr = MediaMetadataRetriever()
        try {
            mmr.setDataSource(inFile.canonicalPath)
            val inRotation = mmr.int(METADATA_KEY_VIDEO_ROTATION) ?: 0
            val inWidth = mmr.int(METADATA_KEY_VIDEO_WIDTH) ?: 0
            val inHeight = mmr.int(METADATA_KEY_VIDEO_HEIGHT) ?: 0
            val inBitrate = mmr.int(METADATA_KEY_BITRATE) // may null
            val inAudioSampleRate = if (Build.VERSION.SDK_INT >= 31) {
                mmr.int(METADATA_KEY_SAMPLERATE) // may null
            } else {
                null
            }

            Log.i(
                TAG,
                "inRotation=$inRotation, inWidth=$inWidth, inHeight=$inHeight, inBitrate=$inBitrate, inAudioSampleRate=$inAudioSampleRate"
            )
            val inSize = Size(inWidth, inHeight)
            val outSize = getScaledSize(inSize) ?: inSize
            if (isSmallEnough(inFile, mmr) && outSize == inSize) {
                Log.i(TAG, "skip transcode and return inFile…")
                return inFile
            }
            Log.i(TAG, "outSize=$outSize")

            val videoFormat = MediaFormat.createVideoFormat(
                MediaFormat.MIMETYPE_VIDEO_AVC,
                outSize.width,
                outSize.height
            ).apply {
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 10)
                // MediaCodecの場合、ビットレートを低めに設定してもあまり小さくならない
                // モトがカメラ向けなので低ビットレートのチューニングはあまりされてないようだ
                // 1.2M～1.7M bps くらいになる
                setInteger(MediaFormat.KEY_BIT_RATE, 1000)
                // MediaCodecはフレームを勝手に捨てることはないので、
                // MediaFormatでフレームレートを下げることはできない
            }

            val audioFormat = MediaFormat.createAudioFormat(
                MediaFormat.MIMETYPE_AUDIO_AAC,
                when (inAudioSampleRate) {
                    null -> limitAudioSampleRate
                    else -> min(inAudioSampleRate, limitAudioSampleRate)
                },
                2
            ).apply {
                setInteger(MediaFormat.KEY_BIT_RATE, targetAudioBitrate)
            }

            val options = TransformationOptions.Builder()
                .setGranularity(MediaTransformer.GRANULARITY_DEFAULT)
                .build()

            val mediaTransformer = prepareMediaTransformer(context)
            val requestId = requestIdSeed.incrementAndGet().toString()
            suspendCancellableCoroutine<Unit> { cont ->
                mediaTransformer.transform(
                    requestId,
                    Uri.fromFile(inFile),
                    outFile.canonicalPath,
                    videoFormat,
                    audioFormat,
                    createListener(cont, onProgress),
                    options,
                )
                cont.invokeOnCancellation { mediaTransformer.cancel(requestId) }
            }
            logVideoInfo(outFile, "output")
            return outFile
        } catch (ex: Throwable) {
            Log.w(TAG, "caught error. delete outFile. ${ex.javaClass.simpleName} ${ex.message}")
            outFile.delete()
            throw ex
        } finally {
            mmr.release()
        }
    }
}
