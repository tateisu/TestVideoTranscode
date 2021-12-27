package jp.juggler.testvideotranscode

import com.otaliastudios.transcoder.Transcoder
import com.otaliastudios.transcoder.TranscoderListener
import com.otaliastudios.transcoder.resize.AtMostResizer
import com.otaliastudios.transcoder.strategy.DefaultAudioStrategy
import com.otaliastudios.transcoder.strategy.DefaultVideoStrategy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import kotlin.coroutines.resumeWithException

object VideoTranscoderNatario {
    private val log = LogTag("VideoTranscoderNatario")

    @Suppress("BlockingMethodInNonBlockingContext")
    suspend fun transcodeVideo(
        inFile: File,
        outFile: File,
        onProgress: (Float) -> Unit,
    ): File = try {
        withContext(Dispatchers.IO) {
            val resultFile = FileInputStream(inFile).use { inStream ->
                // ちょっと発生頻度が多すぎるので間引く
                val progressChannel = Channel<Float>(capacity = Channel.CONFLATED)
                val progressSender = launch(Dispatchers.Main) {
                    try {
                        while (true) {
                            onProgress(progressChannel.receive())
                            delay(1000L)
                        }
                    } catch (ex: ClosedReceiveChannelException) {
                        log.i("progressChannel closed.")
                    } catch (ex: CancellationException) {
                        log.i("progressSender cancelled.")
                    } catch (ex: Throwable) {
                        log.w(ex)
                    }
                }
                try {
                    suspendCancellableCoroutine<File> { cont ->
                        // https://github.com/natario1/Transcoder/pull/160
                        // ワークアラウンドとしてファイルではなくfdを渡す
                        val future = Transcoder.into(outFile.canonicalPath)
                            .addDataSource(inStream.fd)
                            .setVideoTrackStrategy(
                                DefaultVideoStrategy.Builder()
                                    .addResizer(AtMostResizer(540, 960))
                                    .frameRate(15)
                                    .keyFrameInterval(10f)
                                    .bitRate(300_000)
                                    .build()
                            )
                            .setAudioTrackStrategy(
                                DefaultAudioStrategy.Builder()
                                    .channels(1)
                                    .sampleRate(44100)
                                    .bitRate(60_000L)
                                    .build()
                            )

                            .setListener(object : TranscoderListener {
                                override fun onTranscodeProgress(progress: Double) {
                                    val result = progressChannel.trySend(progress.toFloat())
                                    if (!result.isSuccess) {
                                        log.w("trySend $result")
                                    }
                                }

                                override fun onTranscodeCompleted(successCode: Int) {
                                    log.i("onTranscodeCompleted $successCode")
                                    val file = when (successCode) {
                                        Transcoder.SUCCESS_TRANSCODED -> outFile
                                        else /* Transcoder.SUCCESS_NOT_NEEDED */ -> inFile
                                    }
                                    cont.resumeWith(Result.success(file))
                                }

                                override fun onTranscodeCanceled() {
                                    cont.resumeWithException(CancellationException("transcode cancelled."))
                                }

                                override fun onTranscodeFailed(exception: Throwable) {
                                    cont.resumeWithException(exception)
                                }
                            }).transcode()
                        cont.invokeOnCancellation { future.cancel(true) }
                    }
                } finally {
                    progressChannel.close()
                    progressSender.cancelAndJoin()
                }
            }
            if (resultFile != outFile) outFile.delete()
            resultFile
        }
    } catch (ex: Throwable) {
        log.w("delete outFile due to error.")
        outFile.delete()
        throw ex
    }
}
