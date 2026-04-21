# LastOCR 구현 설명

이 문서는 LastOCR 앱을 발표하거나 코드 리뷰할 때 사용할 수 있도록 구현 의도와 처리 흐름을 정리한 기록이다. 특히 ML Kit OCR에서 마침표 Symbol 좌표가 실제 점 위치가 아니라 글자 박스 중앙에 가까운 값으로 나오는 문제를 어떻게 우회했는지 자세히 설명한다.

## 1. 앱의 목적

LastOCR는 Google ML Kit Text Recognition v2 bundled Korean recognizer를 사용해 문서 이미지를 OCR하고, 문서가 원본 방향인지 180도 뒤집힌 방향인지 실험적으로 비교하는 Android 앱이다.

핵심 아이디어는 문장 끝 마침표의 물리적 위치다. 일반적인 정상 방향 문서에서 마침표는 글자 박스 안에서 상대적으로 아래쪽, 즉 baseline 근처에 위치한다. 반대로 문서를 180도 뒤집은 상태에서 OCR 결과를 분석하면, 같은 마침표가 line 내부에서 상대적으로 위쪽에 나타날 가능성이 있다.

따라서 앱은 다음 두 후보를 모두 OCR한다.

- 원본 이미지
- 원본 이미지를 180도 회전한 이미지

각 후보에서 마침표 위치를 분석하고, 마침표가 아래쪽에 더 많이 나타나는 후보를 더 자연스러운 방향으로 본다.

## 2. 주요 파일 구조

### `MainActivity.kt`

앱 진입점이다. `LastOCRTheme` 안에서 `OcrExperimentScreen()`을 표시한다.

### `ui/OcrExperimentScreen.kt`

Compose 기반 단일 화면 UI를 담당한다.

주요 기능:

- 갤러리 이미지 선택
- 카메라 촬영
- OCR 실행 버튼
- 원본 / 180도 회전 오버레이 전환 버튼
- 이미지 위 line polygon과 마침표 marker overlay 표시
- OCR 본문 표시
- 분석 로그 표시 및 복사

오버레이는 사용자가 직접 `원본` 또는 `180도 회전` 버튼으로 선택해서 볼 수 있다. 즉 최종 판정과 상관없이 두 후보의 OCR box와 마침표 위치를 모두 확인할 수 있다.

### `ocr/MlKitOcrRunner.kt`

ML Kit OCR 실행을 담당한다.

사용한 recognizer:

```kotlin
TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())
```

처리 순서:

1. 원본 bitmap을 OCR한다.
2. 원본 bitmap을 180도 회전한다.
3. 회전 bitmap을 OCR한다.
4. 두 OCR 결과를 `PunctuationOrientationAnalyzer`에 넘긴다.
5. 두 후보의 punctuation score를 비교한다.

중요한 점은 analyzer에 OCR 결과뿐 아니라 해당 후보 bitmap도 함께 넘긴다는 것이다.

```kotlin
val original = analyzer.analyze(CandidateKind.ORIGINAL, originalText, bitmap)
val rotated = analyzer.analyze(CandidateKind.ROTATED_180, rotatedText, rotatedBitmap)
```

이렇게 한 이유는 ML Kit가 준 Symbol 좌표만으로는 실제 마침표 위치를 안정적으로 알 수 없었기 때문이다. 실제 이미지 픽셀에서 마침표 잉크 위치를 다시 찾기 위해 bitmap이 필요하다.

### `ocr/PunctuationOrientationAnalyzer.kt`

OCR 결과를 순회하면서 마침표를 수집하고, line 내부 상대 위치를 계산하며, 후보별 점수를 만든다.

담당 기능:

- `TextBlock > Line > Element > Symbol` 전체 순회
- `symbol.text == "."`인 Symbol만 수집
- 각 마침표가 속한 line의 polygon geometry 생성
- 마침표 중심점을 line-local vertical axis에 투영
- `above / below / uncertain` 판정
- 후보별 score 계산
- 원본 후보와 180도 회전 후보 비교
- 사람이 읽기 쉬운 로그 생성

### `ocr/DotInkDetector.kt`

ML Kit의 Symbol 좌표 문제를 해결하기 위해 추가한 핵심 파일이다.

이 파일은 OCR이 찾은 `"."` Symbol 주변의 실제 bitmap 픽셀을 분석해서, 작은 어두운 connected component를 마침표 잉크 후보로 찾는다.

즉 ML Kit는 `"마침표가 있다"`와 `"대략 이 근처다"`를 알려주는 용도로만 쓰고, 최종 마침표 중심 좌표는 원본 이미지 픽셀에서 다시 찾는다.

### `ocr/model/OcrModels.kt`

분석 결과를 담는 data class와 enum을 정의한다.

중요 모델:

- `DotObservation`
- `CandidateAnalysis`
- `OrientationComparison`
- `LineGeometry`
- `DotCenterSource`

`DotCenterSource`는 마침표 중심 좌표가 어디에서 왔는지 표시한다.

```kotlin
enum class DotCenterSource {
    IMAGE_INK,
    ML_KIT_SYMBOL
}
```

로그에서 `이미지점`이라고 나오면 실제 이미지 픽셀에서 검출한 점이고, `MLKit박스`라고 나오면 이미지 점 검출에 실패해서 ML Kit Symbol box 중심을 fallback으로 쓴 것이다.

### `util/BitmapUtils.kt`

이미지 Uri 디코딩, 카메라 촬영 Uri 생성, 180도 회전을 담당한다.

### `util/OverlayMapper.kt`

ML Kit 좌표는 원본 이미지 픽셀 좌표계이고, Compose 화면의 이미지 표시 크기는 기기 화면에 맞게 변한다. 이 파일은 이미지 픽셀 좌표를 Canvas 좌표로 변환한다.

## 3. 기본 OCR 처리 흐름

사용자가 이미지를 선택하거나 촬영하면 앱은 bitmap을 만든다.

그 후 OCR 실행 버튼을 누르면 `MlKitOcrRunner`가 다음 흐름을 수행한다.

```text
입력 bitmap
   |
   |-- OCR(original)
   |
   |-- rotate180()
          |
          |-- OCR(rotated180)

original OCR result + original bitmap
rotated OCR result  + rotated bitmap
          |
          v
PunctuationOrientationAnalyzer
          |
          v
OrientationComparison
```

분석 결과는 UI에서 다음 형태로 보인다.

- 상단: 최종 후보와 점수
- 중앙: 이미지와 오버레이
- 하단: OCR 본문과 분석 로그

## 4. 처음 접근: ML Kit Symbol `"."` 좌표 사용

초기 구현에서는 ML Kit가 제공하는 Symbol 단위 geometry를 그대로 사용했다.

마침표 중심 계산은 대략 다음 방식이었다.

```kotlin
val center = symbol.cornerPoints.centroidOrNull()
    ?: symbol.boundingBox?.centerPointF()
```

이렇게 구한 중심점을 line-local vertical axis에 투영했다.

line-local 좌표계는 line polygon의 위쪽 edge와 아래쪽 edge를 기준으로 만든다.

```text
line top edge    -> normalizedV = 0.0
line bottom edge -> normalizedV = 1.0
```

이론적으로 정상 방향 문서의 마침표는 baseline 근처에 있으므로 `normalizedV`가 0.5보다 큰 아래쪽 값으로 나올 것이라고 예상했다.

## 5. 발견한 문제: 마침표 좌표가 계속 중앙으로 나옴

실험 결과, `"."` Symbol은 잘 수집되었지만 `normalizedV`가 대부분 `0.45 ~ 0.55` 사이에 몰렸다.

이 현상은 중요한 의미가 있다.

ML Kit가 Symbol을 `"."`로 인식하더라도, `symbol.boundingBox`나 `symbol.cornerPoints`가 실제 잉크로 찍힌 작은 점을 타이트하게 감싸는 것이 아닐 수 있다. 실제로는 다음 중 하나에 가까워 보였다.

- 문자 cell 영역
- OCR 내부 character slot
- line 중앙 높이에 맞춰진 추상적인 Symbol box

즉 ML Kit의 Symbol geometry는 `"."`의 실제 물리적 점 위치라기보다, OCR이 해당 문자를 배치한 텍스트 박스 중심에 가까운 값으로 보였다.

이 때문에 Symbol 중심만 사용하면 마침표가 실제로 위에 있는지 아래에 있는지 구분하기 어려웠다.

## 6. 해결 방향: OCR 좌표가 아니라 이미지 픽셀에서 실제 점 찾기

이 문제를 해결하기 위해 접근을 바꿨다.

ML Kit는 다음 용도로만 사용한다.

1. OCR 텍스트 읽기
2. `"."` Symbol이 존재하는지 확인
3. 해당 마침표가 어느 Line / Element 근처에 있는지 알려주는 대략적인 위치 제공

실제 마침표 중심은 원본 bitmap 픽셀을 직접 분석해서 찾는다.

즉 최종 구조는 다음과 같다.

```text
ML Kit Symbol "." 위치
        |
        v
주변 이미지 crop
        |
        v
grayscale / threshold
        |
        v
connected component 탐색
        |
        v
작고 어두운 component 선택
        |
        v
실제 마침표 잉크 중심 좌표
```

이 작업을 담당하는 파일이 `ocr/DotInkDetector.kt`다.

## 7. `DotInkDetector`의 동작 방식

### 7.1 crop 영역 설정

`DotInkDetector.detect()`는 다음 정보를 받는다.

```kotlin
bitmap: Bitmap
lineGeometry: LineGeometry
symbolBox: Rect?
elementBox: Rect?
fallbackCenter: PointF
```

여기서 `fallbackCenter`는 ML Kit Symbol box 중심이다. 이미지 점 검출에 실패하면 이 좌표를 fallback으로 사용한다.

crop 영역은 마침표가 있을 가능성이 높은 주변만 작게 잘라낸다.

기준:

- Symbol boundingBox가 있으면 그 주변
- 없으면 parent Element boundingBox 주변
- 중심은 ML Kit가 준 Symbol 중심
- crop 크기는 line height에 비례

이렇게 하면 전체 이미지를 처리하지 않고, 마침표가 있을 가능성이 높은 좁은 영역만 분석한다.

실제 crop 코드는 `buildCropRect()`에 있다.

```kotlin
val radiusX = max(8f, lineHeight * 0.9f)
val radiusY = max(8f, lineHeight * 0.75f)
val base = symbolBox ?: elementBox
```

여기서 `lineHeight`를 기준으로 crop 크기를 정하는 이유는 문서 사진마다 해상도와 글자 크기가 다르기 때문이다. 고정 픽셀값으로 crop하면 작은 글자에서는 너무 넓고, 큰 글자에서는 너무 좁아진다. 따라서 line polygon에서 계산한 높이를 기준 단위로 삼는다.

가로 방향은 `lineHeight * 0.9`, 세로 방향은 `lineHeight * 0.75`를 기본 반경으로 둔다.

- 가로를 조금 넓게 잡는 이유: ML Kit Symbol box의 x 위치는 대체로 맞지만, 마침표가 element 끝에 붙어 있고 crop이 너무 좁으면 실제 점이 잘릴 수 있다.
- 세로도 lineHeight에 비례해 잡는 이유: 우리가 찾는 것은 line 내부에서 위/아래 위치이므로, line 높이 주변의 충분한 세로 범위를 봐야 한다.
- 최소값 `8px`을 둔 이유: 매우 작은 lineHeight가 들어와도 crop이 너무 작아서 component 탐색 자체가 실패하지 않게 하기 위해서다.

crop의 좌우 범위는 fallback center뿐 아니라 `symbolBox` 또는 `elementBox`도 함께 고려한다.

```kotlin
val left = min(
    fallbackCenter.x - radiusX,
    (base?.left?.toFloat() ?: fallbackCenter.x) - lineHeight * 0.25f
)
val right = max(
    fallbackCenter.x + radiusX,
    (base?.right?.toFloat() ?: fallbackCenter.x) + lineHeight * 0.25f
)
```

이렇게 한 이유는 ML Kit의 `"."` Symbol box가 실제 점보다 중앙에 잡히는 문제가 있었기 때문이다. Symbol 중심만 기준으로 crop하면 실제 잉크 점이 crop 바깥에 걸칠 수 있다. 그래서 가능한 경우 `symbolBox`, 없으면 `elementBox`의 좌우 영역까지 포함해 crop을 조금 더 안전하게 잡는다.

마지막으로 crop은 bitmap 경계 안으로 clamp한다.

```kotlin
Rect(
    left.coerceIn(0, bitmap.width),
    top.coerceIn(0, bitmap.height),
    right.coerceIn(0, bitmap.width),
    bottom.coerceIn(0, bitmap.height)
)
```

그리고 width 또는 height가 2px보다 작으면 분석할 수 없으므로 `null`을 반환한다.

### 7.2 grayscale 변환과 threshold

crop 안의 각 픽셀을 luma 값으로 변환한다.

```kotlin
val luma = (r * 299 + g * 587 + b * 114) / 1000
```

그 후 평균 밝기보다 충분히 어두운 픽셀을 글자/마침표 후보로 본다.

```kotlin
val threshold = min(140, avg - 28).coerceAtLeast(45)
```

이 방식은 완벽한 문서 이진화 알고리즘은 아니지만, 실험용 앱에서는 다음 장점이 있다.

- 밝은 종이 위 검은 글자에 잘 맞는다.
- 이미지마다 전체 밝기가 달라도 어느 정도 적응한다.
- 구현이 단순해서 로그와 오버레이로 실패 여부를 확인하기 쉽다.

threshold 값은 두 가지 기준을 섞어서 정했다.

```kotlin
val threshold = min(140, avg - 28).coerceAtLeast(45)
```

의미는 다음과 같다.

- `avg - 28`: crop 평균보다 충분히 어두운 픽셀만 글자/점 후보로 본다.
- `min(140, ...)`: 평균이 높은 밝은 종이에서도 threshold가 너무 높아져 배경 노이즈까지 글자로 잡히지 않게 제한한다.
- `coerceAtLeast(45)`: 사진이 어둡거나 crop 평균이 낮아도 threshold가 지나치게 낮아져 실제 검은 점까지 놓치지 않게 한다.

이 단계의 출력은 `BooleanArray`다.

```kotlin
return BooleanArray(width * height) { index -> lumas[index] <= threshold }
```

각 값은 해당 crop 픽셀이 “충분히 어두운 픽셀인가”를 의미한다.

이 앱은 발표와 실험을 위한 구현이므로 Otsu threshold나 Sauvola 같은 고급 문서 이진화는 넣지 않았다. 대신 threshold 기준을 단순하게 유지해서, 검출이 실패했을 때 왜 실패했는지 코드와 로그로 추적하기 쉽도록 했다.

### 7.3 connected component 탐색

어두운 픽셀들을 8-neighbor 방식으로 묶어 connected component를 찾는다.

각 component에 대해 다음 값을 계산한다.

- 중심점
- 너비
- 높이
- 픽셀 개수

이 중 마침표처럼 보이는 작은 component만 후보로 남긴다.

필터 조건:

```kotlin
area >= 2
area <= lineHeight * lineHeight * 0.22
width <= lineHeight * 0.55
height <= lineHeight * 0.55
```

즉 너무 큰 글자 획이나 문장 일부는 제외하고, 작은 점에 가까운 어두운 덩어리를 찾는다.

탐색은 BFS 방식이다. crop 안의 모든 어두운 픽셀을 돌면서 아직 방문하지 않은 픽셀을 만나면 queue에 넣고, 주변 8방향 픽셀을 확장한다.

```kotlin
for (ny in y - 1..y + 1) {
    for (nx in x - 1..x + 1) {
        if (nx !in 0 until width || ny !in 0 until height) continue
        val next = ny * width + nx
        if (!dark[next] || visited[next]) continue
        visited[next] = true
        queue[tail++] = next
    }
}
```

8-neighbor를 사용한 이유는 마침표가 아주 작은 점이고, 사진/리사이즈/안티앨리어싱 때문에 픽셀이 대각선으로만 이어져 보일 수 있기 때문이다. 4-neighbor만 사용하면 실제 하나의 점이 여러 component로 쪼개질 수 있다.

각 component를 탐색하면서 다음 값을 누적한다.

```kotlin
count
sumX
sumY
minX
minY
maxX
maxY
```

이 값들로 component 중심과 크기를 만든다.

```kotlin
center = PointF(offsetX + sumX.toFloat() / count, offsetY + sumY.toFloat() / count)
width = maxX - minX + 1
height = maxY - minY + 1
area = count
```

여기서 `offsetX`, `offsetY`를 더하는 이유는 component 탐색은 crop 내부 좌표로 수행되지만, 이후 line-local 좌표 계산은 원본 bitmap 전체 좌표계에서 해야 하기 때문이다. 따라서 최종 center는 crop 내부 좌표가 아니라 원본 이미지 픽셀 좌표로 복원한다.

component 필터는 lineHeight에 비례한다.

```kotlin
val maxDotSide = max(3f, lineGeometry.lineHeight * 0.55f)
val maxDotArea = max(4, (lineGeometry.lineHeight * lineGeometry.lineHeight * 0.22f).toInt())
```

이 필터의 목적은 “마침표처럼 작은 덩어리”만 남기는 것이다.

- `area in 2..maxDotArea`: 너무 작은 단일 노이즈와 너무 큰 글자 획을 줄인다.
- `width <= maxDotSide`: 긴 가로 획이나 글자 일부를 제외한다.
- `height <= maxDotSide`: 세로 획이나 글자 일부를 제외한다.

마침표는 일반적으로 lineHeight보다 훨씬 작기 때문에, lineHeight의 일정 비율보다 큰 component는 마침표 후보에서 제외한다.

### 7.4 최종 점 선택

후보 component가 여러 개 있으면 ML Kit Symbol 중심과 가장 가까운 component를 선택한다.

이유는 OCR이 `"."`를 찾은 위치 자체는 대략 맞기 때문이다. 다만 세로 중심이 실제 점과 맞지 않을 뿐이다.

따라서 ML Kit 좌표는 “검색 시작점”으로 쓰고, 실제 좌표는 이미지 component에서 얻는다.

선택 기준은 다음과 같다.

```kotlin
minByOrNull { component ->
    val dx = component.center.x - fallbackCenter.x
    val dy = component.center.y - fallbackCenter.y
    dx * dx + dy * dy + abs(component.width - component.height) * 2f
}
```

첫 번째 기준은 fallback center와의 거리다. ML Kit가 준 Symbol 중심이 실제 점의 세로 위치는 틀릴 수 있어도, `"."`가 있는 대략적인 x/y 근처는 알려준다고 가정한다. 따라서 그 주변에서 가장 가까운 작은 component를 우선 선택한다.

두 번째로 `abs(component.width - component.height) * 2f`를 더한다. 마침표는 대체로 폭과 높이가 비슷한 작은 덩어리다. 폭과 높이 차이가 큰 component는 글자의 획, 쉼표 꼬리, 먼지, 선분일 가능성이 더 높다. 이 항목은 거리 점수에 약한 shape penalty를 주는 역할을 한다.

이 방식은 복잡한 OCR 후처리보다 단순하지만, 지금 앱의 목적에는 잘 맞는다.

- ML Kit가 찾은 `"."` 위치 근처만 본다.
- 실제 어두운 픽셀 component를 찾는다.
- 작은 점처럼 생긴 후보를 고른다.
- 실패하면 fallback한다.

결국 이 로직의 핵심은 ML Kit를 완전히 버리는 것이 아니라, ML Kit의 장점과 이미지 처리의 장점을 나누는 것이다.

```text
ML Kit:
  "."가 어느 line/element 근처에 있는지 알려줌

DotInkDetector:
  그 근처 원본 이미지에서 실제 점 픽셀 중심을 찾음
```

이 분리 덕분에 ML Kit Symbol geometry가 중앙에 몰리는 문제를 피하면서도, 전체 이미지에서 무작정 점을 찾는 위험은 줄일 수 있다.

## 8. fallback 구조

이미지 픽셀에서 마침표를 못 찾는 경우도 있다.

예:

- 사진이 흐림
- 마침표가 너무 작음
- 글자와 붙어 있음
- crop 안에 노이즈가 많음
- 배경이 어둡거나 대비가 낮음

이 경우 앱은 크래시하거나 분석을 중단하지 않고 ML Kit Symbol 중심으로 fallback한다.

```kotlin
val center = imageInkCenter ?: mlKitCenter
```

그리고 중심 좌표 출처를 기록한다.

```kotlin
val centerSource = if (imageInkCenter != null) {
    DotCenterSource.IMAGE_INK
} else {
    DotCenterSource.ML_KIT_SYMBOL
}
```

로그에서는 다음처럼 표시된다.

```text
1. below (v=0.72, 이미지점)  "문장입니다."
2. uncertain (v=0.50, MLKit박스)  "검출 실패한 문장..."
```

이 로그를 보면 어떤 마침표가 실제 이미지 픽셀 기반인지, 어떤 마침표가 fallback인지 바로 알 수 있다.

## 9. line-local 좌표 계산

마침표 중심을 찾은 후에는 해당 점이 line 내부에서 위쪽인지 아래쪽인지 계산한다.

### 9.1 visual top/bottom 기준 재정렬

처음에는 ML Kit `cornerPoints` 순서를 그대로 사용했다. 하지만 위/아래 판정에는 OCR polygon 순서보다 실제 이미지 화면에서의 위/아래가 더 중요하다.

그래서 현재는 line corner 4개를 y좌표 기준으로 다시 정렬한다.

- y가 작은 두 점: visual top edge
- y가 큰 두 점: visual bottom edge

이 처리는 `toVisualTopBottomCorners()`에서 한다.

```kotlin
val byY = take(4).sortedWith(compareBy<PointF> { it.y }.thenBy { it.x })
val top = byY.take(2).sortedBy { it.x }
val bottom = byY.drop(2).sortedBy { it.x }
```

이렇게 만든 corner 순서:

```text
topLeft, topRight, bottomRight, bottomLeft
```

### 9.2 v축 계산

line의 위쪽 edge 중앙과 아래쪽 edge 중앙을 구한다.

```kotlin
val topMid = midpoint(topLeft, topRight)
val bottomMid = midpoint(bottomLeft, bottomRight)
```

그리고 `topMid -> bottomMid` 방향을 line-local vertical axis로 사용한다.

```kotlin
val rawV = PointF(bottomMid.x - topMid.x, bottomMid.y - topMid.y)
val lineHeight = max(1f, length(rawV))
val v = normalize(rawV)
```

### 9.3 normalizedV 계산

마침표 중심점에서 topMid를 뺀 벡터를 v축에 투영한다.

```kotlin
val relative = PointF(center.x - geometry.origin.x, center.y - geometry.origin.y)
val normalizedV = (dot(relative, geometry.v) / max(1f, geometry.lineHeight))
    .coerceIn(0f, 1f)
```

의미:

- `0.0`에 가까움: line 안에서 위쪽
- `1.0`에 가까움: line 안에서 아래쪽
- `0.5` 근처: 중앙

## 10. above / below / uncertain 판정

현재 threshold는 다음과 같다.

```kotlin
DEFAULT_ABOVE_THRESHOLD = 0.49f
DEFAULT_BELOW_THRESHOLD = 0.51f
```

판정:

```kotlin
normalizedV < 0.49 -> ABOVE
normalizedV > 0.51 -> BELOW
else -> UNCERTAIN
```

로그에서는 영어로 표시한다.

```text
above
below
uncertain
```

## 11. 후보별 점수 계산

각 후보에 대해 다음 개수를 센다.

- belowCount
- aboveCount
- uncertainCount

후보 점수는 다음과 같다.

```kotlin
punctuationScore = (belowCount - aboveCount) / max(1, totalDotCount)
```

의미:

- 점수가 높을수록 마침표가 아래쪽에 많이 잡힌 후보
- 점수가 낮거나 음수면 마침표가 위쪽에 많이 잡힌 후보
- dot가 없으면 0점

원본 점수와 180도 회전 점수를 비교해서 더 높은 후보를 자연스러운 방향으로 본다.

단, 점수 차이가 너무 작으면 `UNCERTAIN`이다.

```kotlin
abs(originalScore - rotatedScore) < 0.15 -> UNCERTAIN
```

## 12. 오버레이 표시

`OcrExperimentScreen.kt`의 `AnalysisOverlay()`가 오버레이를 그린다.

표시 내용:

- line polygon
- 마침표 중심점
- 마침표 번호
- 색상

색상:

- red: above
- blue: below
- yellow: uncertain

마침표 옆에는 `1`, `2`, `3`처럼 몇 번째 마침표인지 번호를 표시한다. 이전에는 line index를 `0:0`처럼 표시했지만, 발표와 디버깅에 방해되어 제거했다.

## 13. 현재 로그 형식

현재 로그는 의도적으로 간단하게 만들었다.

예:

```text
[결론]
방향 후보: 원본
점수 차이: 0.40 (기준 0.15)

[점수 비교]
원본: 0.60  마침표 5개 (below 4 / above 1 / uncertain 0)
180도 회전: -0.20  마침표 5개 (below 2 / above 3 / uncertain 0)

[원본]
점수: 0.60  마침표 5개 (below 4 / above 1 / uncertain 0)
읽은 줄: 12개
마침표 위치:
1. below (v=0.72, 이미지점)  "문장입니다."
2. above (v=0.31, 이미지점)  "다른 문장입니다."

[180도 회전]
점수: -0.20  마침표 5개 (below 2 / above 3 / uncertain 0)
읽은 줄: 12개
마침표 위치:
1. above (v=0.28, 이미지점)  "문장입니다."
```

로그에서 중요한 부분은 다음이다.

- `v`: line 내부 세로 위치
- `이미지점`: 실제 bitmap pixel에서 찾은 마침표
- `MLKit박스`: 이미지점 검출 실패 후 ML Kit Symbol box 중심 사용
- `below / above / uncertain`: 해당 마침표의 위치 판정

## 14. 이 구현의 의미

처음 가설은 “ML Kit Symbol `"."` 좌표를 쓰면 마침표 위치를 알 수 있을 것”이었다.

하지만 실험 결과, ML Kit Symbol 좌표는 실제 마침표 잉크 위치를 충분히 반영하지 않았다. 대부분 line 중앙 근처로 나왔고, 이것만으로는 방향 판정이 어렵다는 것을 확인했다.

그래서 구현을 다음 방향으로 바꿨다.

```text
ML Kit Symbol geometry 직접 사용
        ↓
ML Kit은 마침표 후보 탐색용으로만 사용
        ↓
실제 위치는 원본 이미지 픽셀에서 connected component로 검출
```

이 변경 덕분에 앱은 단순히 OCR box 중심을 보는 것이 아니라, 실제 사진 위의 작은 점 위치를 찾아 line 내부 위/아래를 계산할 수 있게 되었다.

## 15. 한계와 향후 개선

현재 `DotInkDetector`는 실험용 1차 구현이다.

한계:

- 밝은 배경 / 어두운 글자 문서에 가장 잘 맞는다.
- 마침표가 글자 획과 붙어 있으면 component 분리가 어려울 수 있다.
- 노이즈가 많은 사진에서는 작은 먼지나 인쇄 결함을 마침표로 선택할 수 있다.
- axis-aligned crop을 사용하므로 매우 기울어진 문서에서는 crop 범위가 최적이 아닐 수 있다.

개선 가능성:

- line-local 좌표계에 맞춘 회전 crop
- adaptive threshold 고도화
- component shape ratio 강화
- parent element의 마지막 문자 주변만 더 정밀하게 crop
- 마침표 외 `,`, `?`, `!`, `。` 등 baseline punctuation 확장
- 이미지점 검출 성공률과 fallback 비율을 별도 통계로 표시

하지만 현재 목적은 완벽한 최종 판별기가 아니라, bundled ML Kit OCR에서 얻은 정보와 실제 이미지 픽셀을 결합해 마침표 위치 기반 실험을 검증하는 것이다. 이 목적에는 현재 구조가 적합하다.
