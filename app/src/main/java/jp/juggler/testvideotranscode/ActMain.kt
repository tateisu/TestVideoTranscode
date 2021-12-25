package jp.juggler.testvideotranscode

import android.app.Application
import android.content.Context
import android.content.Intent
import android.media.MediaCodecList
import android.media.MediaFormat
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
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
import jp.juggler.testvideotranscode.VideoTranscoder.transcodeVideo
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
        const val TAG = "TestVideoTranscode"

        /**
         * 調査のためコーデックを列挙して情報をログに出す
         */
        private fun dumpCodec() {
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
                        Log.d(TAG, "${info.name} color 0x${colorFormat.toString(16)}")
                        // OMX.qcom.video.encoder.avc color 7fa30c04 不明
                        // OMX.qcom.video.encoder.avc color 7f000789 COLOR_FormatSurface
                        // OMX.qcom.video.encoder.avc color 7f420888 COLOR_FormatYUV420Flexible
                        // OMX.qcom.video.encoder.avc color 15 COLOR_Format32bitBGRA8888
                    }
                    caps.videoCapabilities.bitrateRange?.let { range ->
                        Log.d(TAG, "bitrateRange $range")
                    }
                    caps.videoCapabilities.supportedFrameRates?.let { range ->
                        Log.d(TAG, "supportedFrameRates $range")
                    }
                    if (Build.VERSION.SDK_INT >= 28) {
                        caps.encoderCapabilities.qualityRange?.let { range ->
                            Log.d(TAG, "qualityRange $range")
                        }
                    }
                } catch (ex: Throwable) {
                    Log.w(TAG, "${info.name} ${ex.javaClass.simpleName} ${ex.message}")
                    // type is not supported
                }
            }
        }
    }

    class VM(
        application: Application,
        stateHandle: SavedStateHandle
    ) : AndroidViewModel(application) {

        val logText = stateHandle.getLiveData<String>("logText")
        val transcodeTask = MutableLiveData<Deferred<*>?>()
        val compressedPath = stateHandle.getLiveData<String>("compressedPath")
        val compressedFile = compressedPath.map { if (it.isNullOrEmpty()) null else File(it) }
        val progress = MutableLiveData<Float>()

        @Suppress("BlockingMethodInNonBlockingContext")
        fun handleInputUri(context: Context, uri: Uri) = viewModelScope.launch {
            try {
                logText.value = "処理開始"
                progress.value = 0f
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
                                transcodeVideo(
                                    context,
                                    inFile = inFile,
                                    outFile = outFile,
                                    onProgress = { ratio ->
                                        // 特に指定がなければメインスレッドで呼び出される
                                        // linkedin/LiTr の場合、呼び出される頻度は十分に低かった
                                        progress.value = ratio
                                    }
                                )
                            }
                            // UIからのキャンセルに使う
                            transcodeTask.value = task
                            val resultFile = task.await()
                            logText.value = "変換終了"
                            compressedPath.value = resultFile.canonicalPath
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
                Log.e(TAG, "transcode failed", ex)
                if (ex is CancellationException) {
                    logText.value = "キャンセルされた"
                } else {
                    logText.value = "${ex.javaClass.simpleName} ${ex.message}"
                }
            } finally {
                transcodeTask.value = null
            }
        }

        fun openOutput(activity: AppCompatActivity) {
            try {
                val compressedFile = compressedFile.value
                    ?: error("compressedFile is null")

                val uri = FileProvider.getUriForFile(
                    activity,
                    activity.packageName + ".fileprovider",
                    compressedFile
                )
                Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, "video/mp4")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }.let { activity.startActivity(it) }
            } catch (ex: Throwable) {
                ex.printStackTrace()
            }
        }
    }

    private val vm: VM by viewModels()

    private val views by lazy {
        ActMainBinding.inflate(layoutInflater)
    }

    private val arInputPicker = ActivityResultHandler { result ->
        result.takeIf { it.resultCode == RESULT_OK }
            ?.data?.data?.let { vm.handleInputUri(this, it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(views.root)

        arInputPicker.register(this)

        vm.logText.observe(this) {
            views.tvLog.text = it ?: ""
        }
        vm.compressedFile.observe(this) {
            views.btnViewOutput.vg(it != null)
        }
        vm.transcodeTask.observe(this) {
            val converting = it != null
            views.btnChooseInput.vg(!converting)
            views.progress.vg(converting)
            views.btnCancel.vg(converting)

            if (converting) {
                window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            } else {
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }

        vm.progress.observe(this) {
            val max = 100
            val percent = (it ?: 0f).times(max)
            views.progress.max = max
            views.progress.progress = percent.toInt()
            vm.logText.value = "変換中 ${(percent + 0.5f).toInt()}%"
        }
        views.btnCodecInfo.setOnClickListener {
            dumpCodec()
        }
        views.btnChooseInput.setOnClickListener {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT)
            intent.addCategory(Intent.CATEGORY_OPENABLE)
            intent.type = "video/*"
            intent.putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("video/*"))
            arInputPicker.launch(intent)
        }
        views.btnCancel.setOnClickListener {
            vm.transcodeTask.value?.cancel()
        }
        views.btnViewOutput.setOnClickListener {
            vm.openOutput(this)
        }

        // UI部品の表示状態を初期化する
        vm.transcodeTask.value = null
        if (savedInstanceState == null) {
            vm.compressedPath.value = null
        }
    }
}
