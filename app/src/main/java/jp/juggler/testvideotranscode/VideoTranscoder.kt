package jp.juggler.testvideotranscode

import android.annotation.SuppressLint
import android.content.Context
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMetadataRetriever.METADATA_KEY_BITRATE
import android.media.MediaMetadataRetriever.METADATA_KEY_DURATION
import android.media.MediaMetadataRetriever.METADATA_KEY_MIMETYPE
import android.media.MediaMetadataRetriever.METADATA_KEY_SAMPLERATE
import android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_FRAME_COUNT
import android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT
import android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION
import android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH
import android.net.Uri
import android.os.Build
import android.util.Log
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
import kotlin.math.min

/**
 *
 *  実装メモ：
 *
 *  縮小について
 *  - 何も指定しない場合、videoFormatに指定したサイズに自動でリサイズされる
 *
 *  回転について
 *  - 何も指定しない場合、入力の回転情報は出力にそのままコピーされる
 *  - MediaCodecに切り替えるより前と同様の挙動であり、影響はないはず
 *
 *  変換について
 *  - MediaCodecはフレームを勝手に捨てることはないので、今の実装だとフレームレートを下げることはできない
 *  - またビットレートを低めに設定してもあまり小さくならない
 *  - モトがカメラ動画むけなので低ビットレートのチューニングはあまりされてないようだ
 *  - むしろコーデック側の挙動を安定させるためにそれなりの数字を指定するべき
 *  - 960x540 24～30fpsで 1.2M～1.7M bps くらいになる
 */
object VideoTranscoder {

    private const val TAG = ActMain.TAG

    // 動画の長辺と短辺(縦横は関係ない)の制限
    private const val limitSizeLonger = 640
    private const val limitSizeShorter = 360

    // 映像トラックのビットレート指定
    private const val targetVideoBitrate = 300_000

    // 音声トラックのビットレート指定
    private const val targetAudioBitrate = 64_000

    // キーフレーム間の秒数
    private const val targetIFrameInterval = 10

    // 音声トラックのサンプリングレート上限
    private const val targetAudioSampleRate = 44100

    // 変換リクエストごとに採番するID
    private val requestIdSeed = AtomicInteger()

    // MediaTransformer シングルトン
    @SuppressLint("StaticFieldLeak")
    private var transformerNullable: MediaTransformer? = null

    /**
     * MediaTransformer シングルトンを生成する
     * - 特に解放しなくて良いような気がする
     */
    private fun prepareTransformer(context: Context): MediaTransformer {
        // double-check before/after lock
        transformerNullable?.let { return it }
        synchronized(this) {
            transformerNullable?.let { return it }
            // create instance
            return MediaTransformer(context.applicationContext)
                .also { transformerNullable = it }
        }
    }

    private fun MediaMetadataRetriever.string(key: Int) =
        extractMetadata(key)

    private fun MediaMetadataRetriever.int(key: Int) =
        string(key)?.toIntOrNull()

    private fun MediaMetadataRetriever.long(key: Int) =
        string(key)?.toLongOrNull()

    // Android APIのSizeはsetterがないので雑に用意する
    // equalsのためにデータクラスにする
    data class Size(var w: Int, var h: Int) {
        val aspect: Float get() = w.toFloat() / h.toFloat()
        override fun toString() = "[$w,$h]"
    }

    /**
     * 動画の情報
     */
    @Suppress("MemberVisibilityCanBePrivate")
    class VideoInfo(
        val file: File,
        mmr: MediaMetadataRetriever
    ) {
        val mimeType = mmr.string(METADATA_KEY_MIMETYPE)

        val rotation = mmr.int(METADATA_KEY_VIDEO_ROTATION) ?: 0

        val size = Size(
            mmr.int(METADATA_KEY_VIDEO_WIDTH) ?: 0,
            mmr.int(METADATA_KEY_VIDEO_HEIGHT) ?: 0,
        )

        val bitrate = mmr.int(METADATA_KEY_BITRATE)

        val duration = mmr.long(METADATA_KEY_DURATION)
            ?.toFloat()?.div(1000)?.takeIf { it > 0.1f }

        val frameCount = if (Build.VERSION.SDK_INT >= 28) {
            mmr.int(METADATA_KEY_VIDEO_FRAME_COUNT)?.takeIf { it > 0 }
        } else {
            null
        }

        val frameRatio = if (frameCount != null && duration != null) {
            frameCount.toFloat().div(duration)
        } else {
            null
        }

        val audioSampleRate = if (Build.VERSION.SDK_INT >= 31) {
            mmr.int(METADATA_KEY_SAMPLERATE)?.takeIf { it > 0 }
        } else {
            null
        }

        /**
         * 動画のファイルサイズが十分に小さいなら真
         */
        val isSmallEnough: Boolean
            get() {
                val fileSize = file.length()
                // ファイルサイズを取得できないならエラー
                if (fileSize <= 0L) error("too small file. ${file.canonicalPath}")
                // ファイルサイズが500KB以内ならビットレートを気にしない
                if (fileSize < 500_000) return true
                // 時間帳が短すぎる(&ファイルサイズが1MB以上)なら再エンコード必要とする
                if (duration == null || duration < 0.1f) return false
                // bpsを計算
                val bpsActual = fileSize.toFloat().div(duration).times(8).toInt()
                val bpsLimit = (targetVideoBitrate + targetAudioBitrate).plus(1024)
                Log.i(
                    TAG,
                    "isSmallEnough duration=$duration, bps=$bpsActual/$bpsLimit"
                )
                return bpsActual < bpsLimit
            }

        override fun toString() =
            "rotation=$rotation, size=$size, frameRatio=$frameRatio, bitrate=$bitrate, audioSampleRate=$audioSampleRate, mimeType=$mimeType, file=${file.canonicalPath}"
    }

    private val File.videoInfo: VideoInfo
        get() = MediaMetadataRetriever().use { mmr ->
            mmr.setDataSource(canonicalPath)
            VideoInfo(this, mmr)
        }

    /**
     * アスペクト比を維持しつつ上限に合わせた解像度を提案する
     * - 拡大はしない
     */
    private fun scaling(inSize: Size): Size {
        // ゼロ除算対策
        if (inSize.w < 1 || inSize.h < 1) {
            return Size(limitSizeLonger, limitSizeShorter)
        }
        val inAspect = inSize.aspect
        // 入力の縦横に合わせて上限を決める
        val outSize = if (inAspect >= 1f) {
            Size(limitSizeLonger, limitSizeShorter)
        } else {
            Size(limitSizeShorter, limitSizeLonger)
        }
        // 縦横比を比較する
        return if (inAspect >= outSize.aspect) {
            // 入力のほうが横長なら横幅基準でスケーリングする
            // 拡大はしない
            val scale = outSize.w.toFloat() / inSize.w.toFloat()
            if (scale >= 1f) inSize else outSize.apply {
                h = min(h, (scale * inSize.h + 0.5f).toInt())
            }
        } else {
            // 入力のほうが縦長なら縦幅基準でスケーリングする
            // 拡大はしない
            val scale = outSize.h.toFloat() / inSize.h.toFloat()
            if (scale >= 1f) inSize else outSize.apply {
                w = min(w, (scale * inSize.w + 0.5f).toInt())
            }
        }
    }

    /**
     * 出力する映像トラックのMediaFormat
     */
    private fun targetVideoFormat(outSize: Size) =
        MediaFormat.createVideoFormat(
            MediaFormat.MIMETYPE_VIDEO_AVC,
            outSize.w,
            outSize.h
        ).apply {
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, targetIFrameInterval)
            setInteger(MediaFormat.KEY_BIT_RATE, targetVideoBitrate)
        }

    /**
     * 出力する音声トラックのMediaFormat
     */
    private fun targetAudioFormat(inAudioSampleRate: Int?) =
        MediaFormat.createAudioFormat(
            MediaFormat.MIMETYPE_AUDIO_AAC,
            when (inAudioSampleRate) {
                null -> targetAudioSampleRate
                else -> min(inAudioSampleRate, targetAudioSampleRate)
            },
            2
        ).apply {
            setInteger(MediaFormat.KEY_BIT_RATE, targetAudioBitrate)
        }

    /**
     * トランスコード処理のリスナ
     * - 非同期待機を完了させる
     * - デフォルトだとメインスレッドから呼び出される
     * - onProgressが呼ばれる頻度は十分に低い
     */
    private fun resumeListener(
        cont: CancellableContinuation<Unit>,
        onProgress: (Float) -> Unit,
    ) = object : TransformationListener {

        override fun onStarted(id: String) {
        }

        override fun onProgress(id: String, progress: Float) {
            onProgress(progress)
        }

        override fun onCompleted(
            id: String,
            trackTransformationInfos: List<TrackTransformationInfo>?
        ) {
            cont.resumeWith(Result.success(Unit))
        }

        override fun onCancelled(
            id: String,
            trackTransformationInfos: List<TrackTransformationInfo>?
        ) {
            cont.resumeWithException(
                CancellationException("transcode cancelled.")
            )
        }

        override fun onError(
            id: String,
            cause: Throwable?,
            trackTransformationInfos: List<TrackTransformationInfo>?
        ) {
            cont.resumeWithException(
                cause ?: IllegalStateException("no error information.")
            )
        }
    }

    /**
     * 動画をトランスコードする
     * - 変換が必要ないと判断したらoutFileを削除してinFileを返す
     * - 処理失敗時にもoutFileは削除される。例外は再送出される。
     * - 変換処理をキャンセルしたい場合はコルーチンのキャンセルを使う
     *
     * キャンセルの例：
     *  val videoTask = async{ transcodeVideo(...) }
     *  (UIイベントからのキャンセル) videoTask.cancel()
     *  val compressedFile = videoTask.await() // ここでキャンセル例外が投げられる
     */
    suspend fun transcodeVideo(
        context: Context,
        inFile: File,
        outFile: File,
        // 進捗率: 0f～1f
        onProgress: (Float) -> Unit,
    ): VideoInfo {
        try {
            // 入力データの詳細を調べる
            val info = inFile.videoInfo
            Log.i(TAG, info.toString())

            // 出力サイズを決める
            val outSize = scaling(info.size)
            Log.i(TAG, "outSize=$outSize")

            // 入力ファイルサイズや出力サイズによっては変換せず入力データをそのまま使う
            if (info.isSmallEnough && outSize == info.size) {
                Log.i(TAG, "skip transcode and return inFile…")
                outFile.delete()
                return info
            }

            // トランスコード処理を行い、コールバックを非同期待機する
            suspendCancellableCoroutine<Unit> { cont ->
                val mediaTransformer = prepareTransformer(context)
                val requestId = requestIdSeed.incrementAndGet().toString()
                val videoFormat = targetVideoFormat(outSize)
                val audioFormat = targetAudioFormat(info.audioSampleRate)
                val listener = resumeListener(cont, onProgress)
                val options = TransformationOptions.Builder()
                    .setGranularity(MediaTransformer.GRANULARITY_DEFAULT)
                    .build()
                mediaTransformer.transform(
                    requestId,
                    Uri.fromFile(inFile),
                    outFile.canonicalPath,
                    videoFormat,
                    audioFormat,
                    listener,
                    options,
                )
                cont.invokeOnCancellation { mediaTransformer.cancel(requestId) }
            }
            // 正常終了
            val outInfo = outFile.videoInfo
            Log.i(TAG, "$outInfo")
            return outInfo
        } catch (ex: Throwable) {
            Log.w(TAG, "delete outFile due to ${ex.javaClass.simpleName} ${ex.message}")
            outFile.delete()
            throw ex
        }
    }
}
