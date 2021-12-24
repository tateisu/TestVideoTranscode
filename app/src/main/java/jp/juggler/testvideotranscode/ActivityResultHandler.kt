package jp.juggler.testvideotranscode

import android.content.Intent
import androidx.activity.result.ActivityResult
import androidx.activity.result.ActivityResultCallback
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity

class ActivityResultHandler(val callback: ActivityResultCallback<ActivityResult>) {
    private var launcher: ActivityResultLauncher<Intent>? = null

    fun register(activity: AppCompatActivity) {
        launcher = activity.registerForActivityResult(
            ActivityResultContracts.StartActivityForResult(),
            callback
        )
    }

    fun launch(intent: Intent) {
        (launcher ?: error("ActivityResultHandler: not registered to activity!"))
            .launch(intent)
    }
}
