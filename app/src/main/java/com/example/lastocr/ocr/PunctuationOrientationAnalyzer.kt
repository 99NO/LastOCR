package com.example.lastocr.ocr

import android.graphics.Point
import android.graphics.PointF
import android.graphics.Rect
import com.example.lastocr.ocr.model.CandidateAnalysis
import com.example.lastocr.ocr.model.CandidateKind
import com.example.lastocr.ocr.model.DotObservation
import com.example.lastocr.ocr.model.DotPositionDecision
import com.example.lastocr.ocr.model.LineGeometry
import com.example.lastocr.ocr.model.OrientationComparison
import com.example.lastocr.ocr.model.OrientationDecision
import com.google.mlkit.vision.text.Text
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

class PunctuationOrientationAnalyzer(
    private val aboveThreshold: Float = DEFAULT_ABOVE_THRESHOLD,
    private val belowThreshold: Float = DEFAULT_BELOW_THRESHOLD,
    private val scoreGapThreshold: Float = DEFAULT_SCORE_GAP_THRESHOLD
) {
    fun analyze(kind: CandidateKind, result: Text): CandidateAnalysis {
        val lines = mutableListOf<LineGeometry>()
        val dots = mutableListOf<DotObservation>()
        var elementCount = 0
        var lineCount = 0

        result.textBlocks.forEachIndexed { blockIndex, block ->
            block.lines.forEachIndexed { lineIndex, line ->
                lineCount += 1
                val geometry = buildLineGeometry(
                    blockIndex = blockIndex,
                    lineIndexInBlock = lineIndex,
                    text = line.text,
                    boundingBox = line.boundingBox,
                    cornerPoints = line.cornerPoints
                )
                lines += geometry

                line.elements.forEach { element ->
                    elementCount += 1
                    element.symbols.forEach { symbol ->
                        if (symbol.text == ".") {
                            dots += buildDotObservation(
                                symbolText = symbol.text,
                                symbolBoundingBox = symbol.boundingBox,
                                symbolCornerPoints = symbol.cornerPoints,
                                parentElementText = element.text,
                                parentLineText = line.text,
                                blockIndex = blockIndex,
                                lineIndexInBlock = lineIndex,
                                geometry = geometry
                            )
                        }
                    }
                }
            }
        }

        val belowCount = dots.count { it.decision == DotPositionDecision.BELOW }
        val aboveCount = dots.count { it.decision == DotPositionDecision.ABOVE }
        val uncertainCount = dots.count { it.decision == DotPositionDecision.UNCERTAIN }
        val punctuationScore = (belowCount - aboveCount).toFloat() / max(1, dots.size)
        val analysis = CandidateAnalysis(
            kind = kind,
            recognizedText = result.text,
            blockCount = result.textBlocks.size,
            lineCount = lineCount,
            elementCount = elementCount,
            lines = lines,
            dots = dots,
            belowCount = belowCount,
            aboveCount = aboveCount,
            uncertainCount = uncertainCount,
            punctuationScore = punctuationScore,
            logText = ""
        )
        return analysis.copy(logText = buildCandidateLog(analysis))
    }

    fun compare(original: CandidateAnalysis, rotated180: CandidateAnalysis): OrientationComparison {
        val gap = original.punctuationScore - rotated180.punctuationScore
        val decision = when {
            abs(gap) < scoreGapThreshold -> OrientationDecision.UNCERTAIN
            gap > 0f -> OrientationDecision.ORIGINAL
            else -> OrientationDecision.ROTATED_180
        }
        val reason = when (decision) {
            OrientationDecision.UNCERTAIN -> "score_gap_below_threshold"
            else -> "punctuation_score_higher"
        }
        val finalLog = buildString {
            appendLine("[결론]")
            appendLine("방향 후보: ${decision.toKoreanLabel()}")
            appendLine("이유: ${reason.toKoreanReason()}")
            appendLine("점수 차이: ${gap.format2()} (기준 ${scoreGapThreshold.format2()})")
            appendLine()
            appendLine("[점수 비교]")
            appendLine("원본: ${original.punctuationScore.format2()}  ${original.simpleCountText()}")
            appendLine("180도 회전: ${rotated180.punctuationScore.format2()}  ${rotated180.simpleCountText()}")
            appendLine()
            appendLine(original.logText)
            appendLine()
            appendLine(rotated180.logText)
        }
        return OrientationComparison(
            original = original,
            rotated180 = rotated180,
            orientationDecision = decision,
            scoreGap = gap,
            reason = reason,
            logText = finalLog
        )
    }

    private fun buildDotObservation(
        symbolText: String,
        symbolBoundingBox: Rect?,
        symbolCornerPoints: Array<Point>?,
        parentElementText: String,
        parentLineText: String,
        blockIndex: Int,
        lineIndexInBlock: Int,
        geometry: LineGeometry
    ): DotObservation {
        val symbolCorners = symbolCornerPoints.toPointFList()
        val center = symbolCorners.centroidOrNull()
            ?: symbolBoundingBox?.centerPointF()
            ?: PointF(geometry.origin.x, geometry.origin.y)

        // v is the line-local vertical axis from the top edge toward the bottom edge.
        // Projecting the dot center onto v makes the score robust to skewed line polygons.
        val relative = PointF(center.x - geometry.origin.x, center.y - geometry.origin.y)
        val normalizedV = dot(relative, geometry.v) / max(1f, geometry.lineHeight)
        val decision = when {
            normalizedV < aboveThreshold -> DotPositionDecision.ABOVE
            normalizedV > belowThreshold -> DotPositionDecision.BELOW
            else -> DotPositionDecision.UNCERTAIN
        }

        return DotObservation(
            symbolText = symbolText,
            symbolBoundingBox = symbolBoundingBox,
            symbolCornerPoints = symbolCorners,
            parentElementText = parentElementText,
            parentLineText = parentLineText,
            blockIndex = blockIndex,
            lineIndexInBlock = lineIndexInBlock,
            center = center,
            normalizedV = normalizedV,
            decision = decision
        )
    }

    private fun buildLineGeometry(
        blockIndex: Int,
        lineIndexInBlock: Int,
        text: String,
        boundingBox: Rect?,
        cornerPoints: Array<Point>?
    ): LineGeometry {
        val corners = cornerPoints.toPointFList().ifEmpty {
            boundingBox?.toCornerPoints().orEmpty()
        }
        val safeCorners = corners.ifEmpty { listOf(PointF(0f, 0f), PointF(1f, 0f), PointF(1f, 1f), PointF(0f, 1f)) }
        val topLeft = safeCorners.getOrElse(0) { safeCorners.first() }
        val topRight = safeCorners.getOrElse(1) { topLeft }
        val bottomRight = safeCorners.getOrElse(2) { topRight }
        val bottomLeft = safeCorners.getOrElse(3) { topLeft }

        val topMid = midpoint(topLeft, topRight)
        val bottomMid = midpoint(bottomLeft, bottomRight)
        val leftMid = midpoint(topLeft, bottomLeft)
        val rightMid = midpoint(topRight, bottomRight)

        val u = normalize(PointF(rightMid.x - leftMid.x, rightMid.y - leftMid.y))
        val rawV = PointF(bottomMid.x - topMid.x, bottomMid.y - topMid.y)
        val lineHeight = max(1f, length(rawV))
        val v = normalize(rawV)

        return LineGeometry(
            blockIndex = blockIndex,
            lineIndexInBlock = lineIndexInBlock,
            text = text,
            boundingBox = boundingBox,
            cornerPoints = safeCorners,
            origin = topMid,
            u = u,
            v = v,
            lineHeight = lineHeight
        )
    }

    private fun buildCandidateLog(analysis: CandidateAnalysis): String = buildString {
        appendLine("[${analysis.kind.toKoreanLabel()}]")
        appendLine("점수: ${analysis.punctuationScore.format2()}  ${analysis.simpleCountText()}")
        appendLine("읽은 줄: ${analysis.lineCount}개")
        if (analysis.dots.isEmpty()) {
            appendLine("마침표 Symbol을 찾지 못했습니다.")
            return@buildString
        }
        appendLine("마침표 위치:")
        analysis.dots.forEachIndexed { index, dot ->
            appendLine(
                "${index + 1}. ${dot.decision.toKoreanLabel()} " +
                    "(v=${dot.normalizedV.format2()})  ${dot.parentLineText.compactLine()}"
            )
        }
    }

    companion object {
        const val DEFAULT_ABOVE_THRESHOLD = 0.49f
        const val DEFAULT_BELOW_THRESHOLD = 0.51f
        const val DEFAULT_SCORE_GAP_THRESHOLD = 0.15f
    }
}

private fun CandidateAnalysis.simpleCountText(): String =
    "마침표 ${totalDotCount}개 (아래 $belowCount / 위 $aboveCount / 애매 $uncertainCount)"

private fun CandidateKind.toKoreanLabel(): String = when (this) {
    CandidateKind.ORIGINAL -> "원본"
    CandidateKind.ROTATED_180 -> "180도 회전"
}

private fun OrientationDecision.toKoreanLabel(): String = when (this) {
    OrientationDecision.ORIGINAL -> "원본"
    OrientationDecision.ROTATED_180 -> "180도 회전"
    OrientationDecision.UNCERTAIN -> "판정 보류"
}

private fun DotPositionDecision.toKoreanLabel(): String = when (this) {
    DotPositionDecision.ABOVE -> "위"
    DotPositionDecision.BELOW -> "아래"
    DotPositionDecision.UNCERTAIN -> "애매"
}

private fun String.toKoreanReason(): String = when (this) {
    "score_gap_below_threshold" -> "두 후보 점수 차이가 작습니다."
    "punctuation_score_higher" -> "마침표가 아래쪽에 더 많이 나온 후보입니다."
    else -> this
}

private fun String.compactLine(maxLength: Int = 42): String {
    val oneLine = replace(Regex("\\s+"), " ").trim()
    return if (oneLine.length <= maxLength) "\"$oneLine\"" else "\"${oneLine.take(maxLength - 1)}...\""
}

private fun Array<Point>?.toPointFList(): List<PointF> =
    this?.map { PointF(it.x.toFloat(), it.y.toFloat()) }.orEmpty()

private fun Rect.centerPointF(): PointF = PointF(exactCenterX(), exactCenterY())

private fun Rect.toCornerPoints(): List<PointF> = listOf(
    PointF(left.toFloat(), top.toFloat()),
    PointF(right.toFloat(), top.toFloat()),
    PointF(right.toFloat(), bottom.toFloat()),
    PointF(left.toFloat(), bottom.toFloat())
)

private fun List<PointF>.centroidOrNull(): PointF? {
    if (isEmpty()) return null
    return PointF(sumOf { it.x.toDouble() }.toFloat() / size, sumOf { it.y.toDouble() }.toFloat() / size)
}

private fun midpoint(a: PointF, b: PointF): PointF = PointF((a.x + b.x) / 2f, (a.y + b.y) / 2f)

private fun dot(a: PointF, b: PointF): Float = a.x * b.x + a.y * b.y

private fun length(point: PointF): Float = sqrt(point.x * point.x + point.y * point.y)

private fun normalize(point: PointF): PointF {
    val len = length(point)
    return if (len < 0.0001f) PointF(0f, 1f) else PointF(point.x / len, point.y / len)
}

private fun Float.format2(): String = String.format("%.2f", this)
