package com.example.lastocr.util

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import android.graphics.PointF

data class OverlayMapper(
    private val imageWidth: Int,
    private val imageHeight: Int,
    private val containerSize: Size
) {
    private val scale: Float = minOf(
        containerSize.width / imageWidth.coerceAtLeast(1),
        containerSize.height / imageHeight.coerceAtLeast(1)
    )
    private val drawnWidth: Float = imageWidth * scale
    private val drawnHeight: Float = imageHeight * scale
    private val offsetX: Float = (containerSize.width - drawnWidth) / 2f
    private val offsetY: Float = (containerSize.height - drawnHeight) / 2f

    fun map(point: PointF): Offset = Offset(
        x = offsetX + point.x * scale,
        y = offsetY + point.y * scale
    )
}
