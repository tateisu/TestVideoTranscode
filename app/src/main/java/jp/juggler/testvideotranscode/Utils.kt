package jp.juggler.testvideotranscode

import android.content.Context
import android.os.Environment
import android.view.View
import java.io.File

object Utils {
    fun fileProviderCacheDir(context: Context) =
        (context.externalCacheDir
            ?.takeIf { Environment.getExternalStorageState() == Environment.MEDIA_MOUNTED }
            ?: context.cacheDir
            ?: error("missing cacheDir"))
            .let { File(it, "fileProvider") }
            .apply { mkdirs() }

    fun <T : View> T?.vg(visible: Boolean) =
        if (visible) {
            this?.visibility = View.VISIBLE
            this
        } else {
            this?.visibility = View.GONE
            null
        }
}
