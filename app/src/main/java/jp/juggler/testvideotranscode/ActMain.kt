package jp.juggler.testvideotranscode

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.net.Uri
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
import java.lang.ref.WeakReference

class ActMain : AppCompatActivity() {
    companion object {
        private val log = LogTag("ActMain")
    }

    class VM(
        appArg: Application,
        stateHandle: SavedStateHandle
    ) : AndroidViewModel(appArg) {

        val transcodeBusy = MutableLiveData(false)
        private var refTranscodeTask: WeakReference<Deferred<*>>? = null
        val progressPercent = MutableLiveData(0)
        private val compressedPath = stateHandle.getLiveData<String>("compressedPath", null)
        private val compressedMimeType = stateHandle.getLiveData<String>("compressedMimeType", null)
        val compressedFile = compressedPath.map { if (it.isNullOrEmpty()) null else File(it) }

        val logText = stateHandle.getLiveData<String>("logText", null)

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
                transcodeBusy.value = true
                var outFile: File
                var tempFile: File
                withContext(Dispatchers.IO) {
                    // ファイル名を決定する
                    val fileProviderCacheDir = Utils.fileProviderCacheDir(context)
                    outFile = File.createTempFile("output", ".mp4", fileProviderCacheDir)
                    tempFile = File.createTempFile("temp", ".bin", fileProviderCacheDir)
                    // content:// URLの内容をローカルにコピーする
                    (context.contentResolver.openInputStream(uri)
                        ?: error("contentResolver.openInputStream returns null. uri=$uri"))
                        .use { src -> FileOutputStream(tempFile).use { dst -> src.copyTo(dst) } }
                }
                try {
                    val task = async {
                        when (buttonId) {
                            R.id.rbLitr -> VideoTranscoderLiTr.transcodeVideo(
                                context,
                                inFile = tempFile,
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
                                tempFile,
                                outFile,
                                onProgress = { ratio ->
                                    val percent = (ratio * 100f + 0.5f).toInt()
                                    progressPercent.value = percent
                                    logText.value = "変換中 ${percent}%"
                                }
                            )
                            R.id.rbSili -> VideoTranscoderSili.transcodeVideo(
                                context,
                                tempFile,
                                outFile,
                            )
                            else -> error("incorrect button id $buttonId")
                        }
                    }
                    // UIからのキャンセルに使う
                    refTranscodeTask = WeakReference(task)
                    val result = task.await()
                    val timeEnd = SystemClock.elapsedRealtime()
                    val resultInfo = result.videoInfo
                    val text = "変換終了 ${timeEnd - timeStart}ms $resultInfo"
                    logText.value = text
                    log.i(text)
                    compressedPath.value = result.canonicalPath
                    compressedMimeType.value = resultInfo.mimeType
                } finally {
                    // 保持し続ける必要がないファイルを削除する
                    arrayOf(tempFile, outFile).forEach {
                        if (it.canonicalPath != compressedPath.value) {
                            it.delete()
                        }
                    }
                }
            } catch (ex: Throwable) {
                log.e(ex)
                logText.value = when (ex) {
                    is CancellationException -> "キャンセルされた"
                    else -> "${ex.javaClass.simpleName} ${ex.message}"
                }
            } finally {
                transcodeBusy.value = false
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

        fun cancelTranscode() {
            refTranscodeTask?.get()?.cancel()
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
            transcodeBusy.observe(activity) {
                val busy = it ?: false
                views.btnChooseInput.vg(!busy)
                views.progress.vg(busy)
                views.btnCancel.vg(busy)
                val mask = WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                window.setFlags(if (busy) mask else 0, mask)
            }
            compressedFile.observe(activity) {
                views.btnViewOutput.vg(it != null)
            }
        }

        views.run {
            btnCodecInfo.setOnClickListener { VideoInfo.dumpCodec() }
            btnChooseInput.setOnClickListener { openPicker() }
            btnCancel.setOnClickListener { vm.cancelTranscode() }
            btnViewOutput.setOnClickListener { vm.openOutput(activity) }
        }
    }
}
