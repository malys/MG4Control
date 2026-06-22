package com.mg4.control.util

import android.graphics.Bitmap

/** Flavor offline : ZXing n'est pas embarqué → aucun QR généré. */
object QrCode {
    @Suppress("UNUSED_PARAMETER")
    fun generate(content: String, sizePx: Int): Bitmap? = null
}
