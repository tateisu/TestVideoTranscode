package jp.juggler.testvideotranscode

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.media.MediaCodecList
import android.media.MediaFormat
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.WindowManager
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.map
import androidx.lifecycle.viewModelScope
import jp.juggler.testvideotranscode.Utils.vg
import jp.juggler.testvideotranscode.VideoInfo.Companion.videoInfo
import jp.juggler.testvideotranscode.databinding.ActMainBinding
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

class ActMain : AppCompatActivity() {
    companion object {
        private val log = LogTag("ActMain")
    }

    class VM(
        appArg: Application,
        stateHandle: SavedStateHandle
    ) : AndroidViewModel(appArg) {

        val logText = stateHandle.getLiveData<String>("logText", null)
        private val compressedPath = stateHandle.getLiveData<String>("compressedPath", null)
        private val compressedMimeType = stateHandle.getLiveData<String>("compressedMimeType", null)
        val compressedFile = compressedPath.map { if (it.isNullOrEmpty()) null else File(it) }
        val transcodeTask = MutableLiveData<Deferred<*>?>(null)
        val progressPercent = MutableLiveData(0)

        private val app: Application
            get() = super.getApplication()

        @Suppress("BlockingMethodInNonBlockingContext")
        fun handleInputUri(uri: Uri, buttonId: Int) = viewModelScope.launch {
            try {
                val timeStart = SystemClock.elapsedRealtime()
                val context = app
                logText.value = "処理開始"
                progressPercent.value = 0
                compressedPath.value = null
                withContext(Dispatchers.IO) {
                    val fileProviderCacheDir = Utils.fileProviderCacheDir(context)
                    val outFile = File.createTempFile("output", ".mp4", fileProviderCacheDir)
                    val inFile = File.createTempFile("input", ".bin", fileProviderCacheDir)
                    try {
                        FileOutputStream(inFile).use { outStream ->
                            (context.contentResolver.openInputStream(uri)
                                ?: error("contentResolver.openInputStream returns null. uri=$uri"))
                                .use { it.copyTo(outStream) }
                        }
                        withContext(Dispatchers.Main) {
                            val task = async {
                                when (buttonId) {
                                    R.id.rbLitr -> VideoTranscoderLiTr.transcodeVideo(
                                        context,
                                        inFile = inFile,
                                        outFile = outFile,
                                        onProgress = { ratio ->
                                            // 特に指定がなければメインスレッドで呼び出される
                                            // linkedin/LiTr の場合、呼び出される頻度は十分に低かった
                                            val percent = (ratio * 100f + 0.5f).toInt()
                                            progressPercent.value = percent
                                            logText.value = "変換中 ${percent}%"
                                        }
                                    )
                                    R.id.rbNatario -> VideoTranscoderNatario.transcodeVideo(
                                        inFile,
                                        outFile,
                                        onProgress = { ratio ->
                                            val percent = (ratio * 100f + 0.5f).toInt()
                                            progressPercent.value = percent
                                            logText.value = "変換中 ${percent}%"
                                        }
                                    )
                                    R.id.rbSili -> VideoTranscoderSili.transcodeVideo(
                                        context,
                                        inFile,
                                        outFile,
                                    )
                                    else ->error("incorrect button id $buttonId")
                                }
                            }
                            // UIからのキャンセルに使う
                            transcodeTask.value = task
                            val result = task.await()
                            val timeEnd = SystemClock.elapsedRealtime()
                            val resultInfo = result.videoInfo
                            logText.value = "変換終了 ${timeEnd-timeStart}ms $resultInfo"
                            compressedPath.value = result.canonicalPath
                            compressedMimeType.value = resultInfo.mimeType
                        }
                    } finally {
                        // 保持し続ける必要がないファイルは削除する
                        arrayOf(inFile, outFile).forEach {
                            if (it.canonicalPath != compressedPath.value) {
                                it.delete()
                            }
                        }
                    }
                }
            } catch (ex: Throwable) {
                log.e(ex)
                if (ex is CancellationException) {
                    logText.value = "キャンセルされた"
                } else {
                    logText.value = "${ex.javaClass.simpleName} ${ex.message}"
                }
            } finally {
                transcodeTask.value = null
            }
        }

        fun openOutput(activity: Activity) {
            try {
                val compressedFile = compressedFile.value
                    ?: error("compressedFile is null")

                val uri = FileProvider.getUriForFile(
                    activity,
                    activity.packageName + ".fileprovider",
                    compressedFile
                )
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, compressedMimeType.value ?: "video/avc")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                activity.startActivity(intent)
            } catch (ex: Throwable) {
                log.e(ex)
            }
        }

        /**
         * 調査のためコーデックを列挙して情報をログに出す
         */
        fun dumpCodec() {
            val mcl = MediaCodecList(MediaCodecList.REGULAR_CODECS)
            for (info in mcl.codecInfos) {
                try {
                    if (!info.isEncoder) continue
                    val caps = try {
                        info.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC) ?: continue
                    } catch (ex: Throwable) {
                        continue
                    }

                    for (colorFormat in caps.colorFormats) {
                        log.i( "${info.name} color 0x${colorFormat.toString(16)}")
                        // OMX.qcom.video.encoder.avc color 7fa30c04 不明
                        // OMX.qcom.video.encoder.avc color 7f000789 COLOR_FormatSurface
                        // OMX.qcom.video.encoder.avc color 7f420888 COLOR_FormatYUV420Flexible
                        // OMX.qcom.video.encoder.avc color 15 COLOR_Format32bitBGRA8888
                    }
                    caps.videoCapabilities.bitrateRange?.let { range ->
                        log.i( "bitrateRange $range")
                    }
                    caps.videoCapabilities.supportedFrameRates?.let { range ->
                        log.i( "supportedFrameRates $range")
                    }
                    if (Build.VERSION.SDK_INT >= 28) {
                        caps.encoderCapabilities.qualityRange?.let { range ->
                            log.i( "qualityRange $range")
                        }
                    }
                } catch (ex: Throwable) {
                    log.w(ex)
                    // type is not supported
                }
            }
        }
    }

    private val vm: VM by viewModels()

    private val views by lazy {
        ActMainBinding.inflate(layoutInflater)
    }

    private val arInputPicker = ActivityResultHandler { result ->
        result.takeIf { it.resultCode == RESULT_OK }
            ?.data?.data?.let {
                vm.handleInputUri(it, views.rgTranscoder.checkedRadioButtonId)
            }
    }

    private fun openPicker() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "video/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("video/*"))
        }
        arInputPicker.launch(intent)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(views.root)

        val activity = this

        arInputPicker.register(activity)

        vm.run {

            logText.observe(activity) {
                views.tvLog.text = it ?: ""
            }
            progressPercent.observe(activity) {
                views.progress.progress = it ?: 0
            }
            transcodeTask.observe(activity) {
                val converting = it != null
                views.btnChooseInput.vg(!converting)
                views.progress.vg(converting)
                views.btnCancel.vg(converting)
                val mask = WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                window.setFlags(if (converting) mask else 0, mask)
            }
            compressedFile.observe(activity) {
                views.btnViewOutput.vg(it != null)
            }
        }

        views.run {
            btnCodecInfo.setOnClickListener { vm.dumpCodec() }
            btnChooseInput.setOnClickListener { openPicker() }
            btnCancel.setOnClickListener { vm.transcodeTask.value?.cancel() }
            btnViewOutput.setOnClickListener { vm.openOutput(activity) }
        }
    }
}
