package jp.juggler.testvideotranscode

import android.media.MediaMetadataRetriever
import android.os.Build
import java.io.File
import kotlin.math.min

/**
 * 動画の情報
 */
@Suppress("MemberVisibilityCanBePrivate")
class VideoInfo(
    val file: File,
    mmr: MediaMetadataRetriever
) {
    companion object {
        private val log = LogTag("VideoInfo")

        val File.videoInfo: VideoInfo
            get() = MediaMetadataRetriever().use { mmr ->
                mmr.setDataSource(canonicalPath)
                VideoInfo(this, mmr)
            }

        private fun MediaMetadataRetriever.string(key: Int) =
            extractMetadata(key)

        private fun MediaMetadataRetriever.int(key: Int) =
            string(key)?.toIntOrNull()

        private fun MediaMetadataRetriever.long(key: Int) =
            string(key)?.toLongOrNull()

    }

    val mimeType = mmr.string(MediaMetadataRetriever.METADATA_KEY_MIMETYPE)

    val rotation = mmr.int(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION) ?: 0

    val size = Size(
        mmr.int(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH) ?: 0,
        mmr.int(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT) ?: 0,
    )

    val bitrate = mmr.int(MediaMetadataRetriever.METADATA_KEY_BITRATE)

    val duration = mmr.long(MediaMetadataRetriever.METADATA_KEY_DURATION)
        ?.toFloat()?.div(1000)?.takeIf { it > 0.1f }

    val frameCount = if (Build.VERSION.SDK_INT >= 28) {
        mmr.int(MediaMetadataRetriever.METADATA_KEY_VIDEO_FRAME_COUNT)?.takeIf { it > 0 }
    } else {
        null
    }

    val frameRatio = if (frameCount != null && duration != null) {
        frameCount.toFloat().div(duration)
    } else {
        null
    }

    val audioSampleRate = if (Build.VERSION.SDK_INT >= 31) {
        mmr.int(MediaMetadataRetriever.METADATA_KEY_SAMPLERATE)?.takeIf { it > 0 }
    } else {
        null
    }

    val actualBps by lazy {
        val fileSize = file.length()

        // ファイルサイズを取得できないならエラー
        if (fileSize <= 0L) return@lazy null

        // 時間帳が短すぎるなら算出できない
        if (duration == null || duration < 0.1f) return@lazy null

        // bpsを計算
        fileSize.toFloat().div(duration).times(8).toInt()
    }

    /**
     * 動画のファイルサイズが十分に小さいなら真
     */
    fun isSmallEnough(limitBps: Int): Boolean {
        val fileSize = file.length()
        // ファイルサイズを取得できないならエラー
        if (fileSize <= 0L) error("too small file. ${file.canonicalPath}")
        // ファイルサイズが500KB以内ならビットレートを気にしない
        if (fileSize < 500_000) return true

        // ファイルサイズからビットレートを計算できなかったなら再エンコード必要
        val actualBps = this.actualBps ?: return false

        // bpsを計算
        log.i("isSmallEnough duration=$duration, bps=$actualBps/$limitBps")
        return actualBps <= limitBps
    }

    override fun toString() =
        "rotation=$rotation, size=$size, frameRatio=$frameRatio, bitrate=${actualBps ?: bitrate}, audioSampleRate=$audioSampleRate, mimeType=$mimeType, file=${file.canonicalPath}"

}
