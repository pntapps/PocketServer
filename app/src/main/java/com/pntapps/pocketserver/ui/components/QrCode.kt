package com.pntapps.pocketserver.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.pntapps.pocketserver.ui.theme.Hud
import kotlin.math.floor

/** Modules of empty border around the code; the QR spec asks for 4 so cameras can find the edges. */
private const val QUIET_ZONE = 4

/**
 * QR code in a HUD frame. The code itself is dark-on-light, not themed: inverted or low-contrast
 * codes fail on many phone cameras.
 */
@Composable
fun QrCode(text: String, modifier: Modifier = Modifier) {
    // Margin 0: ZXing then returns one cell per module and the quiet zone is drawn here.
    val matrix = remember(text) {
        QRCodeWriter().encode(
            text, BarcodeFormat.QR_CODE, 0, 0,
            mapOf(EncodeHintType.MARGIN to 0, EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M),
        )
    }
    Canvas(modifier.aspectRatio(1f).hudFrame(Hud.Cyan, cut = 10.dp).padding(14.dp)) { drawQr(matrix) }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawQr(matrix: BitMatrix) {
    val cells = matrix.width + QUIET_ZONE * 2
    // Whole-pixel modules avoid anti-aliased seams between neighbours, which hurt scanning.
    val cell = floor(size.minDimension / cells).coerceAtLeast(1f)
    val origin = floor((size.minDimension - cell * cells) / 2)
    drawRect(QR_LIGHT, Offset(origin, origin), Size(cell * cells, cell * cells))
    for (y in 0 until matrix.height) {
        var x = 0
        while (x < matrix.width) {
            if (!matrix[x, y]) { x++; continue }
            // Merge horizontal runs into one rect: fewer draw calls, no gaps.
            val start = x
            while (x < matrix.width && matrix[x, y]) x++
            drawRect(
                QR_DARK,
                Offset(origin + (start + QUIET_ZONE) * cell, origin + (y + QUIET_ZONE) * cell),
                Size((x - start) * cell, cell),
            )
        }
    }
}

private val QR_LIGHT = Color(0xFFF2FBFF)
private val QR_DARK = Color(0xFF03060C)
