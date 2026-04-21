# LastOCR

Google ML Kit Text Recognition v2 bundled Korean recognizer로 문서 이미지를 OCR하고, Symbol 단위 `"."` 좌표를 이용해 원본과 180도 회전 후보를 비교하는 실험용 앱입니다.

## 사용 방법

1. Android Studio에서 프로젝트를 엽니다.
2. 앱을 실행합니다.
3. `갤러리 선택`으로 문서 이미지를 고르거나 `사진 촬영`으로 새 이미지를 촬영합니다.
4. `OCR 실행`을 누르면 선택/촬영한 원본 bitmap과 180도 회전 bitmap을 각각 OCR합니다.
5. 화면 중앙에서 Line polygon과 마침표 판정 오버레이를 확인합니다.
6. 하단 로그에서 결론, 후보별 점수, 각 마침표의 위/아래/애매 판정과 해당 줄 텍스트를 확인합니다.

## 판정 방식

- 이번 버전은 정확한 최종 방향 판별기가 아니라, bundled 모델에서 Symbol 좌표가 실제로 나오는지와 마침표 위치 휴리스틱이 동작하는지 확인하는 실험입니다.
- 다른 feature는 쓰지 않습니다. N-gram, 페이지 번호, 문장 연결성 등은 의도적으로 제외했습니다.
- 각 Line의 `cornerPoints`로 line-local vertical axis를 만들고, `"."` Symbol 중심점을 그 축에 투영해 `normalizedV`를 계산합니다.
- 기본 threshold는 `normalizedV < 0.49`면 ABOVE, `normalizedV > 0.51`면 BELOW, 그 사이는 UNCERTAIN입니다. 마침표 중심이 line 중앙 근처에 몰리는 OCR 결과를 보기 위해 애매 구간을 좁게 둔 실험용 기본값입니다.
- 후보별 점수는 `(belowCount - aboveCount) / max(1, dotCount)`이고, 두 후보의 점수 차가 `0.15` 미만이면 UNCERTAIN입니다.

## 빌드 확인

```bash
./gradlew :app:assembleDebug
```
