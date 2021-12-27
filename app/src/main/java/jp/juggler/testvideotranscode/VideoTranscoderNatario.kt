package jp.juggler.testvideotranscode

import com.otaliastudios.transcoder.Transcoder
import com.otaliastudios.transcoder.TranscoderListener
import com.otaliastudios.transcoder.resize.AtMostResizer
import com.otaliastudios.transcoder.strategy.DefaultAudioStrategy
import com.otaliastudios.transcoder.strategy.DefaultVideoStrategy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.conflate
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
                val progressState = MutableSharedFlow<Float>(
                    extraBufferCapacity = 1,
                    onBufferOverflow = BufferOverflow.DROP_OLDEST
                )
                val progressSender = launch(Dispatchers.Main) {
                    progressState.conflate().collect {
                        onProgress(it)
                        delay(1000L)
                    }
                }
                try {
                    suspendCancellableCoroutine<File> { cont ->
                        val future = Transcoder.into(outFile.canonicalPath)
                            .addDataSource(inStream.fd)
                            .setAudioTrackStrategy(
                                DefaultAudioStrategy.Builder()
                                    .channels(2)
                                    .sampleRate(44100)
                                    .bitRate(60_000L)
                                    .build()
                            )
                            .setVideoTrackStrategy(
                                DefaultVideoStrategy.Builder()
                                    .addResizer(AtMostResizer(540, 960))
                                    .frameRate(10)
                                    .bitRate(300_000)
                                    .build()
                            )
                            .setListener(object : TranscoderListener {
                                override fun onTranscodeProgress(progress: Double) {
                                    // ちょっと発生頻度が多すぎるので間引く
                                    if (!progressState.tryEmit(progress.toFloat())) {
                                        log.w("tryEmit failed.")
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
                    progressSender.cancel()
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
