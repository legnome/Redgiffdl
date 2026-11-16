package fr.perso.redgifsdl

import android.app.Activity
import android.os.Build
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager

/**
 * Discrétion : écran masqué dans les applis récentes (aperçu noir) et captures d'écran bloquées.
 */
fun Activity.applyPrivacy() {
    window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
}

/** Bord à bord (imposé à partir d'Android 15) : décale le contenu sous les barres système. */
fun View.applySystemBarInsets() {
    val baseL = paddingLeft
    val baseT = paddingTop
    val baseR = paddingRight
    val baseB = paddingBottom
    setOnApplyWindowInsetsListener { v, insets ->
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val b = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime())
            v.setPadding(baseL + b.left, baseT + b.top, baseR + b.right, baseB + b.bottom)
        } else {
            @Suppress("DEPRECATION")
            v.setPadding(
                baseL + insets.systemWindowInsetLeft, baseT + insets.systemWindowInsetTop,
                baseR + insets.systemWindowInsetRight, baseB + insets.systemWindowInsetBottom,
            )
        }
        insets
    }
}
