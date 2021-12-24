package jp.juggler.testvideotranscode

import android.content.Context
import android.content.Intent
import android.os.Environment
import android.view.View
import androidx.activity.result.ActivityResult
import androidx.activity.result.ActivityResultCallback
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import java.io.File

object Utils {
    fun fileProviderCacheDir(context: Context) =
        (context.externalCacheDir
            ?.takeIf { Environment.getExternalStorageState() == Environment.MEDIA_MOUNTED }
            ?: context.cacheDir
            ?: error("missing cacheDir")
                ).let { File(it, "fileProvider") }
            .apply { mkdirs() }

    fun <T : View> T?.vg(visible: Boolean) =
        if (visible) {
            this?.visibility = View.VISIBLE
            this
        } else {
            this?.visibility = View.GONE
            null
        }

    fun registerActivityResult(
        activity: AppCompatActivity,
        callback: ActivityResultCallback<ActivityResult>
    ): ActivityResultLauncher<Intent> =
        activity.registerForActivityResult(
            ActivityResultContracts.StartActivityForResult(),
            callback
        )
}
