package fr.perso.redgifsdl

import android.app.Activity
import android.os.Build
import android.view.View
import android.view.WindowInsets

/**
 * Discrétion : aperçu masqué dans les applis récentes (Android 13+).
 * Les captures d'écran restent autorisées (pas de FLAG_SECURE).
 */
fun Activity.applyPrivacy() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) setRecentsScreenshotEnabled(false)
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
