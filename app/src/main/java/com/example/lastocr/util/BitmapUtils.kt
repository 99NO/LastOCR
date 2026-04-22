package com.example.lastocr.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.core.content.FileProvider
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val MAX_LONG_SIDE_FOR_OCR: Int = 3072

suspend fun decodeBitmapFromUri(context: Context, uri: Uri): Bitmap = withContext(Dispatchers.IO) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        val source = ImageDecoder.createSource(context.contentResolver, uri)
        ImageDecoder.decodeBitmap(source) { decoder, _, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            decoder.isMutableRequired = false
        }
    } else {
        @Suppress("DEPRECATION")
        MediaStore.Images.Media.getBitmap(context.contentResolver, uri)
    }.asArgb8888().downscaleForOcr()
}

fun Bitmap.rotate180(): Bitmap {
    val matrix = Matrix().apply { postRotate(180f) }
    return Bitmap.createBitmap(this, 0, 0, width, height, matrix, true)
}

fun createImageCaptureUri(context: Context): Uri {
    val imageDir = File(context.cacheDir, "images").apply { mkdirs() }
    val imageFile = File.createTempFile("ocr_capture_", ".jpg", imageDir)
    return FileProvider.getUriForFile(
        context,
        "${context.packageName}.fileprovider",
        imageFile
    )
}

private fun Bitmap.asArgb8888(): Bitmap {
    if (config == Bitmap.Config.ARGB_8888) return this
    return copy(Bitmap.Config.ARGB_8888, false)
}

private fun Bitmap.downscaleForOcr(): Bitmap {
    val longSide = maxOf(width, height)
    if (longSide <= MAX_LONG_SIDE_FOR_OCR) return this

    val scale = MAX_LONG_SIDE_FOR_OCR.toFloat() / longSide
    val targetWidth = (width * scale).toInt().coerceAtLeast(1)
    val targetHeight = (height * scale).toInt().coerceAtLeast(1)
    return Bitmap.createScaledBitmap(this, targetWidth, targetHeight, true)
}
