package com.example.lastocr.ocr

import android.graphics.Bitmap
import com.example.lastocr.ocr.model.CandidateKind
import com.example.lastocr.ocr.model.OrientationComparison
import com.example.lastocr.util.rotate180
import com.google.android.gms.tasks.Task
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.suspendCancellableCoroutine

class MlKitOcrRunner(
    private val analyzer: PunctuationOrientationAnalyzer = PunctuationOrientationAnalyzer()
) {
    suspend fun runOriginalAndRotated(bitmap: Bitmap): OrientationComparison = withContext(Dispatchers.Default) {
        val recognizer = TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())
        try {
            val originalText = recognizer.process(InputImage.fromBitmap(bitmap, 0)).await()
            val rotatedBitmap = bitmap.rotate180()
            val rotatedText = recognizer.process(InputImage.fromBitmap(rotatedBitmap, 0)).await()
            val original = analyzer.analyze(CandidateKind.ORIGINAL, originalText, bitmap)
            val rotated = analyzer.analyze(CandidateKind.ROTATED_180, rotatedText, rotatedBitmap)
            analyzer.compare(original, rotated)
        } finally {
            recognizer.close()
        }
    }
}

private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { continuation ->
    addOnSuccessListener { result -> continuation.resume(result) }
    addOnFailureListener { exception -> continuation.resumeWithException(exception) }
    addOnCanceledListener { continuation.cancel() }
}
