package com.example.lastocr.ui

import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.lastocr.ocr.MlKitOcrRunner
import com.example.lastocr.ocr.PunctuationOrientationAnalyzer
import com.example.lastocr.ocr.model.CandidateAnalysis
import com.example.lastocr.ocr.model.CandidateKind
import com.example.lastocr.ocr.model.OrientationComparison
import com.example.lastocr.ocr.model.OrientationDecision
import com.example.lastocr.util.OverlayMapper
import com.example.lastocr.util.createImageCaptureUri
import com.example.lastocr.util.decodeBitmapFromUri
import com.example.lastocr.util.rotate180
import kotlinx.coroutines.launch

@Composable
fun OcrExperimentScreen() {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    val runner = remember { MlKitOcrRunner() }

    var bitmap by remember { mutableStateOf<Bitmap?>(null) }
    var rotatedBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var comparison by remember { mutableStateOf<OrientationComparison?>(null) }
    var isRunning by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("이미지를 선택한 뒤 OCR을 실행하세요. 실험용: 마침표 Symbol 위치만 사용합니다.") }
    var pendingCameraUri by remember { mutableStateOf<Uri?>(null) }
    var selectedOverlay by remember { mutableStateOf(CandidateKind.ORIGINAL) }

    fun loadImageFromUri(uri: Uri, sourceLabel: String) {
        scope.launch {
            comparison = null
            selectedOverlay = CandidateKind.ORIGINAL
            isRunning = true
            message = "$sourceLabel 이미지를 불러오는 중..."
            runCatching {
                val decoded = decodeBitmapFromUri(context, uri)
                bitmap = decoded
                rotatedBitmap = decoded.rotate180()
            }.onSuccess {
                message = "$sourceLabel 이미지 로드 완료. OCR 실행 버튼을 누르세요."
            }.onFailure { throwable ->
                message = "$sourceLabel 이미지 로드 실패: ${throwable.localizedMessage ?: throwable::class.java.simpleName}"
            }
            isRunning = false
        }
    }

    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        loadImageFromUri(uri, "갤러리")
    }

    val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { saved ->
        val uri = pendingCameraUri
        if (saved && uri != null) {
            loadImageFromUri(uri, "카메라")
        } else {
            message = "카메라 촬영이 취소되었습니다."
        }
    }

    Scaffold { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            TopControls(
                isRunning = isRunning,
                hasImage = bitmap != null,
                comparison = comparison,
                message = message,
                onPickImage = { imagePicker.launch("image/*") },
                onTakePhoto = {
                    runCatching { createImageCaptureUri(context) }
                        .onSuccess { uri ->
                            pendingCameraUri = uri
                            cameraLauncher.launch(uri)
                        }
                        .onFailure { throwable ->
                            message = "카메라 준비 실패: ${throwable.localizedMessage ?: throwable::class.java.simpleName}"
                        }
                },
                onRunOcr = {
                    val source = bitmap ?: return@TopControls
                    scope.launch {
                        isRunning = true
                        selectedOverlay = CandidateKind.ORIGINAL
                        message = "원본과 180도 회전 이미지 OCR 분석 중..."
                        runCatching { runner.runOriginalAndRotated(source) }
                            .onSuccess {
                                comparison = it
                                message = "분석 완료: ${it.orientationDecision}"
                            }
                            .onFailure { throwable ->
                                comparison = null
                                message = "OCR 실패: ${throwable.localizedMessage ?: throwable::class.java.simpleName}"
                            }
                        isRunning = false
                    }
                }
            )

            OverlaySelector(
                comparison = comparison,
                selectedOverlay = selectedOverlay,
                onSelectedOverlayChange = { selectedOverlay = it }
            )

            val overlayBitmap = chooseOverlayBitmap(bitmap, rotatedBitmap, selectedOverlay)
            val overlayAnalysis = chooseOverlayAnalysis(comparison, selectedOverlay)
            ImageOverlayPanel(
                bitmap = overlayBitmap,
                analysis = overlayAnalysis,
                isRunning = isRunning,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            )

            ResultPanels(
                comparison = comparison,
                onCopyLog = {
                    comparison?.logText?.let { clipboard.setText(AnnotatedString(it)) }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 220.dp, max = 360.dp)
            )
        }
    }
}

@Composable
private fun OverlaySelector(
    comparison: OrientationComparison?,
    selectedOverlay: CandidateKind,
    onSelectedOverlayChange: (CandidateKind) -> Unit
) {
    if (comparison == null) return

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("오버레이 보기:", style = MaterialTheme.typography.labelMedium)
        OutlinedButton(
            onClick = { onSelectedOverlayChange(CandidateKind.ORIGINAL) },
            enabled = selectedOverlay != CandidateKind.ORIGINAL
        ) {
            Text("원본")
        }
        OutlinedButton(
            onClick = { onSelectedOverlayChange(CandidateKind.ROTATED_180) },
            enabled = selectedOverlay != CandidateKind.ROTATED_180
        ) {
            Text("180도 회전")
        }
    }
}

@Composable
private fun TopControls(
    isRunning: Boolean,
    hasImage: Boolean,
    comparison: OrientationComparison?,
    message: String,
    onPickImage: () -> Unit,
    onTakePhoto: () -> Unit,
    onRunOcr: () -> Unit
) {
    Surface(
        tonalElevation = 1.dp,
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = onPickImage, enabled = !isRunning) {
                    Text("갤러리 선택")
                }
                Button(onClick = onTakePhoto, enabled = !isRunning) {
                    Text("사진 촬영")
                }
                Button(onClick = onRunOcr, enabled = hasImage && !isRunning) {
                    Text("OCR 실행")
                }
                if (isRunning) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                }
            }
            Text(
                text = comparison?.let {
                    "판정=${it.orientationDecision}  original=${it.original.punctuationScore.format4()}  rotated=${it.rotated180.punctuationScore.format4()}  gap=${it.scoreGap.format4()}"
                } ?: message,
                style = MaterialTheme.typography.bodyMedium
            )
            Text(
                text = "dot v: 위 < ${PunctuationOrientationAnalyzer.DEFAULT_ABOVE_THRESHOLD}, 아래 > ${PunctuationOrientationAnalyzer.DEFAULT_BELOW_THRESHOLD}, 점수 차이 < ${PunctuationOrientationAnalyzer.DEFAULT_SCORE_GAP_THRESHOLD}면 보류",
                style = MaterialTheme.typography.labelSmall
            )
            comparison?.let {
                Text(
                    text = "dots: original=${it.original.totalDotCount} (B${it.original.belowCount}/A${it.original.aboveCount}/U${it.original.uncertainCount})  rotated=${it.rotated180.totalDotCount} (B${it.rotated180.belowCount}/A${it.rotated180.aboveCount}/U${it.rotated180.uncertainCount})",
                    style = MaterialTheme.typography.labelSmall
                )
            }
        }
    }
}

@Composable
private fun ImageOverlayPanel(
    bitmap: Bitmap?,
    analysis: CandidateAnalysis?,
    isRunning: Boolean,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.28f), RoundedCornerShape(8.dp)),
        contentAlignment = Alignment.Center
    ) {
        if (bitmap == null) {
            Text("선택된 이미지 없음", color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = "Selected document image",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit
            )
            if (analysis != null) {
                AnalysisOverlay(
                    bitmap = bitmap,
                    analysis = analysis,
                    modifier = Modifier.fillMaxSize()
                )
                Legend(
                    label = "overlay: ${analysis.kind.label}",
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(8.dp)
                )
            }
        }
        if (isRunning) {
            Surface(color = MaterialTheme.colorScheme.surface.copy(alpha = 0.72f), shape = RoundedCornerShape(8.dp)) {
                Text("분석 중...", modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp))
            }
        }
    }
}

@Composable
private fun AnalysisOverlay(
    bitmap: Bitmap,
    analysis: CandidateAnalysis,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier) {
        val mapper = OverlayMapper(bitmap.width, bitmap.height, Size(size.width, size.height))
        val labelPaint = android.graphics.Paint().apply {
            color = android.graphics.Color.WHITE
            textSize = 24f
            isAntiAlias = true
            setShadowLayer(4f, 0f, 0f, android.graphics.Color.BLACK)
        }

        analysis.lines.forEach { line ->
            val points = line.cornerPoints.map { mapper.map(it) }
            if (points.size >= 4) {
                val path = Path().apply {
                    moveTo(points[0].x, points[0].y)
                    points.drop(1).forEach { lineTo(it.x, it.y) }
                    close()
                }
                drawPath(path, color = Color(0xFF16A34A), style = Stroke(width = 2f))
            }
        }

        analysis.dots.forEachIndexed { index, dot ->
            val center = mapper.map(dot.center)
            val color = when (dot.decision.label) {
                "ABOVE" -> Color(0xFFDC2626)
                "BELOW" -> Color(0xFF2563EB)
                else -> Color(0xFFEAB308)
            }
            drawCircle(color = color, radius = 7f, center = center)
            drawCircle(color = Color.White, radius = 8f, center = center, style = Stroke(width = 2f))
            val label = (index + 1).toString()
            drawContext.canvas.nativeCanvas.drawText(label, center.x + 10f, center.y - 8f, labelPaint)
        }
    }
}

@Composable
private fun Legend(label: String, modifier: Modifier = Modifier) {
    Surface(
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.86f),
        shape = RoundedCornerShape(8.dp),
        modifier = modifier
    ) {
        Column(modifier = Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium)
            LegendItem(Color(0xFFDC2626), "red = above dot")
            LegendItem(Color(0xFF2563EB), "blue = below dot")
            LegendItem(Color(0xFFEAB308), "yellow = uncertain dot")
        }
    }
}

@Composable
private fun LegendItem(color: Color, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(modifier = Modifier.size(10.dp).background(color, RoundedCornerShape(2.dp)))
        Spacer(modifier = Modifier.width(6.dp))
        Text(text, fontSize = 11.sp)
    }
}

@Composable
private fun ResultPanels(
    comparison: OrientationComparison?,
    onCopyLog: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        TextPanel(
            title = "OCR 본문",
            text = comparison?.displayText().orEmpty(),
            modifier = Modifier.weight(1f)
        )
        LogPanel(
            text = comparison?.logText.orEmpty(),
            onCopyLog = onCopyLog,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun TextPanel(title: String, text: String, modifier: Modifier = Modifier) {
    Surface(shape = RoundedCornerShape(8.dp), tonalElevation = 1.dp, modifier = modifier.fillMaxSize()) {
        Column(modifier = Modifier.padding(10.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = text.ifBlank { "OCR 실행 후 표시됩니다." },
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState()),
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

@Composable
private fun LogPanel(text: String, onCopyLog: () -> Unit, modifier: Modifier = Modifier) {
    Surface(shape = RoundedCornerShape(8.dp), tonalElevation = 1.dp, modifier = modifier.fillMaxSize()) {
        Column(modifier = Modifier.padding(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("분석 로그", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                OutlinedButton(onClick = onCopyLog, enabled = text.isNotBlank()) {
                    Text("로그 복사")
                }
            }
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = text.ifBlank { "분석 로그가 여기에 표시됩니다." },
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState()),
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
            )
        }
    }
}

private fun chooseOverlayBitmap(
    original: Bitmap?,
    rotated: Bitmap?,
    selectedOverlay: CandidateKind
): Bitmap? = when (selectedOverlay) {
    CandidateKind.ORIGINAL -> original
    CandidateKind.ROTATED_180 -> rotated ?: original
}

private fun chooseOverlayAnalysis(
    comparison: OrientationComparison?,
    selectedOverlay: CandidateKind
): CandidateAnalysis? = when (selectedOverlay) {
    CandidateKind.ORIGINAL -> comparison?.original
    CandidateKind.ROTATED_180 -> comparison?.rotated180
}

private fun OrientationComparison.displayText(): String = when (orientationDecision) {
    OrientationDecision.ORIGINAL -> original.recognizedText
    OrientationDecision.ROTATED_180 -> rotated180.recognizedText
    OrientationDecision.UNCERTAIN -> buildString {
        appendLine("[ORIGINAL]")
        appendLine(original.recognizedText)
        appendLine()
        appendLine("[ROTATED_180]")
        appendLine(rotated180.recognizedText)
    }
}

private fun Float.format4(): String = String.format("%.4f", this)
