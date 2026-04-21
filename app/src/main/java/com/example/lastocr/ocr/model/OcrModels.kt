package com.example.lastocr.ocr.model

import android.graphics.PointF
import android.graphics.Rect

enum class CandidateKind(val label: String) {
    ORIGINAL("ORIGINAL"),
    ROTATED_180("ROTATED_180")
}

enum class DotPositionDecision(val label: String) {
    ABOVE("ABOVE"),
    BELOW("BELOW"),
    UNCERTAIN("UNCERTAIN")
}

enum class OrientationDecision {
    ORIGINAL,
    ROTATED_180,
    UNCERTAIN
}

data class LineGeometry(
    val blockIndex: Int,
    val lineIndexInBlock: Int,
    val text: String,
    val boundingBox: Rect?,
    val cornerPoints: List<PointF>,
    val origin: PointF,
    val u: PointF,
    val v: PointF,
    val lineHeight: Float
)

data class DotObservation(
    val symbolText: String,
    val symbolBoundingBox: Rect?,
    val symbolCornerPoints: List<PointF>,
    val parentElementText: String,
    val parentLineText: String,
    val blockIndex: Int,
    val lineIndexInBlock: Int,
    val center: PointF,
    val normalizedV: Float,
    val decision: DotPositionDecision
)

data class CandidateAnalysis(
    val kind: CandidateKind,
    val recognizedText: String,
    val blockCount: Int,
    val lineCount: Int,
    val elementCount: Int,
    val lines: List<LineGeometry>,
    val dots: List<DotObservation>,
    val belowCount: Int,
    val aboveCount: Int,
    val uncertainCount: Int,
    val punctuationScore: Float,
    val logText: String
) {
    val totalDotCount: Int = dots.size
}

data class OrientationComparison(
    val original: CandidateAnalysis,
    val rotated180: CandidateAnalysis,
    val orientationDecision: OrientationDecision,
    val scoreGap: Float,
    val reason: String,
    val logText: String
)
