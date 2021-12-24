package jp.juggler.testvideotranscode

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.*
import java.util.concurrent.atomic.AtomicReference

class ActMain : AppCompatActivity() {
    companion object{
        const val TAG="TestVideoTranscode"
    }

    class ProgressInfo(val time:Long,val ratio:Double)

    class VM(
        application: Application,
        stateHandle: SavedStateHandle
    ) : AndroidViewModel(application) {

        val logText = stateHandle.getLiveData<String>("logText")
        val transcodeTask = MutableLiveData<Deferred<*>?>()
        val compressedPath = stateHandle.getLiveData<String>("compressedPath")
        val compressedFile = compressedPath.map { if (it.isNullOrEmpty()) null else File(it) }
        val progress = MutableLiveData<Double>()

        @Suppress("BlockingMethodInNonBlockingContext")
        fun handleInputUri(context: Context, uri: Uri) = viewModelScope.launch {
            try {
                logText.value = "処理開始"
                progress.value = 0.0
                compressedPath.value = null
                withContext(Dispatchers.IO) {
                    val fileProviderCacheDir = Utils.fileProviderCacheDir(context)
                    val outFile = File.createTempFile("output", ".mp4", fileProviderCacheDir)
                    val inFile = File.createTempFile("input", ".bin", fileProviderCacheDir)
                    FileOutputStream(inFile).use { outStream ->
                        (context.contentResolver.openInputStream(uri)
                            ?: error("contentResolver.openInputStream returns null. uri=$uri"))
                            .use { it.copyTo(outStream) }
                    }
                    withContext(Dispatchers.Main){
                        var lastProgress = 0.0
                        var lastProgressCount =0
                        val task = async {
                            transcodeVideo(
                                inFile = inFile,
                                outFile = outFile,
                                onProgress = { time,ratio->
                                    ++lastProgressCount
                                    lastProgress = ratio
                                }
                            )
                        }.also {
                            // UIからキャンセルしたい時に使う
                            transcodeTask.value = it
                        }
                        launch(Dispatchers.Main){
                            while( task.isActive) {
                                progress.value = lastProgress
                                delay(1000L)
                            }
                        }
                        val resultFile = task.await()
                        logText.value = "変換終了"
                        compressedPath.value = resultFile.canonicalPath
                    }
                }
            } catch (ex: Throwable) {
                Log.e(TAG,"transcode failed",ex)
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
                Intent.createChooser(
                    Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(uri, "video/mp4")
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    },
                    "どのアプリで開く?"
                ).let { activity.startActivity(it) }
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
            views.btnChooseInput.isEnabled = !converting
            views.progress.vg(converting)
            views.btnCancel.vg(converting)
        }
        vm.progress.observe(this) {
            val percent = (it ?: 0.0) * 100.0
            views.progress.max = 100
            views.progress.progress = percent.toInt()
            vm.logText.value = "変換中 ${percent}%"
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

        if (savedInstanceState == null) {
            // 初期状態の表示を作る
            vm.compressedPath.value = null
            vm.transcodeTask.value = null
        }
    }
}
