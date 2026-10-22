// Minimal stand-in for the upstream logging object. The upstream version routes to
// native; here we just wrap android.util.Log so ParamPackage / InputHandler keep
// compiling unchanged.

package org.citron.citron_emu.utils

import android.util.Log as AndroidLog

object Log {
    var gameLaunched = false

    fun debug(message: String) = AndroidLog.d("Citron", message)
    fun warning(message: String) = AndroidLog.w("Citron", message)
    fun info(message: String) = AndroidLog.i("Citron", message)
    fun error(message: String) = AndroidLog.e("Citron", message)
    fun critical(message: String) = AndroidLog.e("Citron", message)
}
