package com.example.lastocr.ocr

import android.graphics.Bitmap
import android.graphics.PointF
import android.graphics.Rect
import com.example.lastocr.ocr.model.LineGeometry
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class DotInkDetector {
    fun detect(
        bitmap: Bitmap,
        lineGeometry: LineGeometry,
        symbolBox: Rect?,
        elementBox: Rect?,
        fallbackCenter: PointF
    ): PointF? {
        val crop = buildCropRect(
            bitmap = bitmap,
            lineHeight = lineGeometry.lineHeight,
            symbolBox = symbolBox,
            elementBox = elementBox,
            fallbackCenter = fallbackCenter
        ) ?: return null

        val darkPixels = collectDarkPixels(bitmap, crop)
        if (darkPixels.isEmpty()) return null

        val components = findComponents(darkPixels, crop.width(), crop.height(), crop.left, crop.top)
        val maxDotSide = max(3f, lineGeometry.lineHeight * 0.55f)
        val maxDotArea = max(4, (lineGeometry.lineHeight * lineGeometry.lineHeight * 0.22f).toInt())

        return components
            .asSequence()
            .filter { it.area in 2..maxDotArea }
            .filter { it.width <= maxDotSide && it.height <= maxDotSide }
            .minByOrNull { component ->
                val dx = component.center.x - fallbackCenter.x
                val dy = component.center.y - fallbackCenter.y
                dx * dx + dy * dy + abs(component.width - component.height) * 2f
            }
            ?.center
    }

    private fun buildCropRect(
        bitmap: Bitmap,
        lineHeight: Float,
        symbolBox: Rect?,
        elementBox: Rect?,
        fallbackCenter: PointF
    ): Rect? {
        val radiusX = max(8f, lineHeight * 0.9f)
        val radiusY = max(8f, lineHeight * 0.75f)
        val base = symbolBox ?: elementBox

        val left = min(
            fallbackCenter.x - radiusX,
            (base?.left?.toFloat() ?: fallbackCenter.x) - lineHeight * 0.25f
        ).toInt()
        val right = max(
            fallbackCenter.x + radiusX,
            (base?.right?.toFloat() ?: fallbackCenter.x) + lineHeight * 0.25f
        ).toInt()
        val top = (fallbackCenter.y - radiusY).toInt()
        val bottom = (fallbackCenter.y + radiusY).toInt()

        val clipped = Rect(
            left.coerceIn(0, bitmap.width),
            top.coerceIn(0, bitmap.height),
            right.coerceIn(0, bitmap.width),
            bottom.coerceIn(0, bitmap.height)
        )
        return if (clipped.width() < 2 || clipped.height() < 2) null else clipped
    }

    private fun collectDarkPixels(bitmap: Bitmap, crop: Rect): BooleanArray {
        val width = crop.width()
        val height = crop.height()
        val lumas = IntArray(width * height)
        var sum = 0L

        for (y in 0 until height) {
            for (x in 0 until width) {
                val color = bitmap.getPixel(crop.left + x, crop.top + y)
                val r = (color shr 16) and 0xFF
                val g = (color shr 8) and 0xFF
                val b = color and 0xFF
                val luma = (r * 299 + g * 587 + b * 114) / 1000
                lumas[y * width + x] = luma
                sum += luma
            }
        }

        val avg = (sum / max(1, lumas.size)).toInt()
        val threshold = min(140, avg - 28).coerceAtLeast(45)
        return BooleanArray(width * height) { index -> lumas[index] <= threshold }
    }

    private fun findComponents(
        dark: BooleanArray,
        width: Int,
        height: Int,
        offsetX: Int,
        offsetY: Int
    ): List<Component> {
        val visited = BooleanArray(dark.size)
        val queue = IntArray(dark.size)
        val components = mutableListOf<Component>()

        for (index in dark.indices) {
            if (!dark[index] || visited[index]) continue

            var head = 0
            var tail = 0
            queue[tail++] = index
            visited[index] = true

            var minX = width
            var minY = height
            var maxX = 0
            var maxY = 0
            var count = 0
            var sumX = 0L
            var sumY = 0L

            while (head < tail) {
                val current = queue[head++]
                val x = current % width
                val y = current / width
                count++
                sumX += x
                sumY += y
                minX = min(minX, x)
                minY = min(minY, y)
                maxX = max(maxX, x)
                maxY = max(maxY, y)

                for (ny in y - 1..y + 1) {
                    for (nx in x - 1..x + 1) {
                        if (nx !in 0 until width || ny !in 0 until height) continue
                        val next = ny * width + nx
                        if (!dark[next] || visited[next]) continue
                        visited[next] = true
                        queue[tail++] = next
                    }
                }
            }

            components += Component(
                center = PointF(offsetX + sumX.toFloat() / count, offsetY + sumY.toFloat() / count),
                width = (maxX - minX + 1).toFloat(),
                height = (maxY - minY + 1).toFloat(),
                area = count
            )
        }

        return components
    }

    private data class Component(
        val center: PointF,
        val width: Float,
        val height: Float,
        val area: Int
    )
}
