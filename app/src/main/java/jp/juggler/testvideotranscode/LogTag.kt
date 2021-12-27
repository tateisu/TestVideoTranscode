package jp.juggler.testvideotranscode

import android.util.Log

class LogTag(
    private val prefix: String,
) {
    companion object {
        private const val TAG = "TestVideoTranscode"
    }

    fun v(msg: String) = Log.v(TAG, "$prefix>$msg")
    fun d(msg: String) = Log.d(TAG, "$prefix>$msg")
    fun i(msg: String) = Log.i(TAG, "$prefix>$msg")
    fun w(msg: String) = Log.w(TAG, "$prefix>$msg")
    fun e(msg: String) = Log.e(TAG, "$prefix>$msg")

    fun w(ex: Throwable, msg: String = "error.") = Log.w(TAG, "$prefix>$msg", ex)
    fun e(ex: Throwable, msg: String = "error.") = Log.e(TAG, "$prefix>$msg", ex)

}