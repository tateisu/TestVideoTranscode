package jp.juggler.testvideotranscode

import android.annotation.SuppressLint
import android.content.Context
import android.media.MediaFormat
import android.net.Uri
import com.linkedin.android.litr.MediaTransformer
import com.linkedin.android.litr.TransformationListener
import com.linkedin.android.litr.TransformationOptions
import com.linkedin.android.litr.analytics.TrackTransformationInfo
import jp.juggler.testvideotranscode.VideoInfo.Companion.videoInfo
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
 *  - デジカメで撮影したフルHD以上 17M bps とかのデータよりは全然軽いので良しとしたい
 */
object VideoTranscoderLiTr {

    private val log = LogTag("VideoTranscoderLiTr")

    // 動画の長辺と短辺の制限。横長でも縦長でも適応する
    // フルHDの半分らしい
    private const val limitSizeLonger = 960
    private const val limitSizeShorter = 540

    // トランスコードせずにすませる、許容可能なビットレート(bits per second)
    private const val limitBps = 1_800_000

    // 映像トラックのビットレート指定
    // MediaCodecだと指定を上回る結果になる事が多い
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
    ): File {
        try {
            // 入力データの詳細を調べる
            val info = inFile.videoInfo
            log.i(info.toString())

            // 出力サイズを決める
            val outSize = info.size.scaleTo(limitSizeLonger, limitSizeShorter)
            log.i("outSize=$outSize")

            // 入力ファイルサイズや出力サイズによっては変換せず入力データをそのまま使う
            if (info.isSmallEnough(limitBps) && outSize == info.size) {
                log.i("skip transcode and return inFile…")
                outFile.delete()
                return inFile
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
            return outFile
        } catch (ex: Throwable) {
            log.w("delete outFile due to error.")
            outFile.delete()
            throw ex
        }
    }
}
