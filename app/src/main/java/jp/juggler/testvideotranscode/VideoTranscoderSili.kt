package jp.juggler.testvideotranscode

import android.content.Context
import android.net.Uri
import com.iceteck.silicompressorr.SiliCompressor
import jp.juggler.testvideotranscode.VideoInfo.Companion.videoInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.concurrent.thread
import kotlin.coroutines.resumeWithException

object VideoTranscoderSili {
    private val log = LogTag("VideoTranscoderSili")

    suspend fun transcodeVideo(
        context: Context,
        inFile: File,
        outFile: File,
    ): File = withContext(Dispatchers.IO) {
        // 一時ファイルの名称を決定できないので、間に1段フォルダを作らないと中断時の後処理ができない
        var tempDir: File? = null
        try {
            tempDir = outFile.parentFile
                ?.let { File(it, "temp${System.currentTimeMillis()}") }
                ?.also { it.mkdirs() }
                ?: error("can't make tempDir")

            val info = inFile.videoInfo
            val outSize = info.size.scaleTo(960, 540)

            val outPath = suspendCancellableCoroutine<String> { cont ->
                val thread = thread(start = true) {
                    try {
                        val path = SiliCompressor
                            .with(context)
                            .compressVideo(
                                Uri.fromFile(inFile),
                                tempDir.canonicalPath,
                                outSize.w,
                                outSize.h,
                                300_000,
                            )
                        cont.resumeWith(Result.success(path))
                    } catch (ex: InterruptedException) {
                        log.w(ex)
                        cont.resumeWithException(CancellationException("interrupted.", ex))
                    } catch (ex: Throwable) {
                        log.w(ex)
                        cont.resumeWithException(ex)
                    }
                }
                cont.invokeOnCancellation {
                    try {
                        thread.interrupt()
                    } catch (ignored: Throwable) {
                    }
                }
            }
            if (!this.isActive) {
                throw CancellationException("transcode cancelled.")
            }
            // 出力ファイル名を指定に合わせてリネーム
            File(outPath).renameTo(outFile)
            outFile
        } catch (ex: Throwable) {
            log.w("delete outFile due to error.")
            outFile.delete()
            throw ex
        } finally {
            tempDir?.delete()
        }
    }
}
