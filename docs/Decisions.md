# PriceTrace · Fitness 공통 OCR 결정 로그

이 문서는 PriceTrace 영수증 스캔·OCR 검수 앱에서 장기간 유지할 기능·구조 결정을 기록한다.
단순 구현 내역은 코드와 테스트에 남기고, 사용자가 선택한 방향과 그 선택을 되돌릴 조건을 이 문서에 남긴다.

## 문서 경계

- **기준 작업 트리**: `feat/home-api-settings-nutrition-box`의 2026-08-12 working tree
- **현재 구현**: `generic-parser.v15` + `nutrition-label-parser.v2` / `0.2.0` (새 APK는 SM-A256N 계측 테스트에서 사용; 실제 OCR 정확도는 미검증)
- **주요 근거**: 사용자 피드백, `receipt-scanner` 소스·회귀 테스트, 로컬 Gradle 검증
- **실기기 근거**: Samsung SM-A256N에서 새 debug APK의 홈 API 설정 계측 테스트를 통과했다. 실제 영수증·영양 라벨 OCR 정확도는 별도 검증하지 않았다.
- **표현 규칙**: `사용자 확인`, `저장소 검증`, `강한 추론`, `계획`을 구분한다. 로컬 빌드 성공을 실제 영수증 정확도나 배포 완료로 확대하지 않는다.

## 2026-08-12 검증 기록

| 검사 | 명령/절차 | 환경·경계 | 결과 |
| --- | --- | --- | --- |
| 전체 unit test | `.\gradlew.bat test --rerun-tasks` | Windows, 로컬 합성 입력과 가짜 Gemini·Nutrition HTTP 응답 | 통과: 21 suites, 147 tests, failures/errors/skipped 0 |
| 전체 Gradle 확인 | `.\gradlew.bat test lint assembleDebug assembleDebugAndroidTest :app:compileReleaseKotlin --no-daemon` | 루트 `.env` 6개 값을 BuildConfig/APK에 주입 | 통과: unit test·lint·debug APK·instrumentation APK·debug/release Kotlin compile |
| 새 홈 API 설정 계측 | `.\gradlew.bat :app:connectedDebugAndroidTest '-Pandroid.testInstrumentationRunnerArguments.class=com.pricetrace.receiptocr.ReceiptUiInstrumentedTest#homeOpensApiSettingsForGeminiAndFitnessConnections' --rerun-tasks` | Samsung SM-A256N, Android 16 | 통과: 홈에서 API 설정을 열고 Gemini/Supabase 설정 필드가 표시됨 |
| `.env` BuildConfig 생성 | `:app:generateDebugBuildConfig` + 생성 필드 점검 | 루트 `.env` 6개 값과 parsed key 전체, 실제 값은 출력하지 않음 | 통과: Gemini key·이메일·비밀번호·URL/key·모델 필드 모두 생성 |
| Gemini key 기본값 계측 | `EncryptedGeminiApiKeyStoreInstrumentedTest` | Samsung SM-A256N, BuildConfig fallback + isolated Keystore | 통과: 저장·읽기·삭제 계약 유지 |
| Nutrition 기본값 계측 | `AndroidNutritionSupabaseStoreInstrumentedTest` | Samsung SM-A256N, 명시적 기본 URL/key와 새 APK | 통과: 저장 전 기본 연결값 노출·HTTPS/publishable 검증 경로 확인 |
| 홈 API 설정 계측 | `ReceiptUiInstrumentedTest#homeOpensApiSettingsForGeminiAndFitnessConnections` | Samsung SM-A256N, 새 BuildConfig 자동 입력 | 통과: API 설정 진입과 Gemini/Supabase 필드 표시 |
| 실기기 설치·실행 | `adb install -r` + `monkey` | Samsung SM-A256N, Android 16 | 통과: `Success`, `MainActivity` top resumed, versionCode 18 |
| debug APK | `app/build/outputs/apk/debug/app-debug.apk` | 루트 `.env` 전체 주입 최신 산출물 | 59,060,406 bytes, SHA-256 `8F4DD83CE509F904235B3CE8706B922D68A0D19C26C551F3A7FAC7F20491E297` |
| 전체 connected 계측 | `.\gradlew.bat connectedDebugAndroidTest --rerun-tasks` | Samsung SM-A256N | 전체 성공 아님: 현재 기기에서 Compose/UI 테스트 3건과 Room migration schema asset 누락 1건 실패. 새 API 설정 단독 테스트는 별도 통과 |

새 APK를 사용한 실제 상품 라벨 OCR 정확도, 실제 Gemini 호출, 실제 Nutrition Supabase 로그인/저장과 원격 migration/RLS는 여전히 검증하지 않았다.

## 2026-08-11 검증 기록

| 검사 | 명령/절차 | 환경·경계 | 결과 |
| --- | --- | --- | --- |
| 전체 unit test | `app` + `receipt-scanner` XML 집계 | Windows, 로컬 합성 입력과 가짜 Gemini·Nutrition HTTP 응답 | 통과: 21 suites, 145 tests, failures/errors/skipped 0 (이전 기준) |
| 전체 Gradle 확인 | `.\gradlew.bat test lint assembleDebug assembleDebugAndroidTest :app:compileReleaseKotlin --no-daemon` | API 키 없는 로컬 JDK/Android SDK | 통과: unit test·lint·debug APK·instrumentation APK·debug/release Kotlin compile |
| lint | `app` + `receipt-scanner` debug XML 보고서 | 로컬 정적 분석 | 0 issues |
| Gemini key 정적 검사 | API key·header assignment 패턴 `rg` | source/docs, build 제외 | embedded Gemini key 패턴 없음 |

이전 `0.2.0` debug APK는 58,704,077 bytes이고 SHA-256은 `46013818A8D45DCADDAB651B3DCC4E63EF1EDCFF77F1AB723FD3328C8C36CB43`이다. 새 검증 기록은 위 2026-08-12 섹션을 기준으로 한다.

## 2026-08-08 검증 기록

| 검사 | 명령/절차 | 환경·경계 | 결과 |
| --- | --- | --- | --- |
| 파서 회귀 | `.\gradlew.bat :receipt-scanner:test --no-daemon --rerun-tasks` | Windows, 로컬 `receipt-scanner` unit test | 통과: 84 tests, failures 0, errors 0 |
| 전체 Gradle 확인 | `.\gradlew.bat test lint assembleDebug assembleDebugAndroidTest --no-daemon` | dirty working tree, 로컬 JDK/Android SDK | 통과: test·lint·debug APK·instrumentation APK 생성 |
| 실기기 설치 | `adb -s R5CWC01BHZP install -r app/build/outputs/apk/debug/app-debug.apk` | Samsung SM-A256N, 기존 앱 데이터 보존 업데이트 | 통과: `Success` |
| 설치 버전·실행 확인 | package `dumpsys`, `am start -W`, activity `dumpsys` | 같은 기기 | `versionCode=15`, `versionName=0.1.14`, `MainActivity` resumed |

새 APK SHA-256은 `0C887E718E237985D340CC4E0CA75A76A500EBD49CD7C20B1F619AD68B68BC09`이다. 이 검증은 설치·실행 경계까지이며, 실제 영수증 재촬영과 OCR 정답 대조는 포함하지 않는다.

## 2026-08-05 검증 기록

| 검사 | 명령/절차 | 환경·경계 | 결과 |
| --- | --- | --- | --- |
| 파서 회귀 | `.\gradlew.bat :receipt-scanner:test --no-daemon --rerun-tasks` | Windows, 로컬 `receipt-scanner` unit test | 통과: 78 tests, failures 0, errors 0 |
| 전체 Gradle 확인 | `.\gradlew.bat test lint assembleDebug assembleDebugAndroidTest --no-daemon` | dirty working tree, 로컬 JDK/Android SDK | 통과: test·lint·debug APK·instrumentation APK 생성 |
| 실기기 설치 | `adb -s <connected-device> install -r app/build/outputs/apk/debug/app-debug.apk` | Samsung SM-A256N, ADB `device` 상태 | 통과: `Success` |
| 설치 버전 확인 | `adb -s <connected-device> shell dumpsys package com.pricetrace.receiptocr` | 같은 기기 package metadata | `versionCode=13`, `versionName=0.1.12` 확인 |

위 검사에서 Google Play Services 모델 다운로드, 촬영·보정, 실제 영수증 OCR, `connectedDebugAndroidTest`, 외부 공유 E2E는 실행하지 않았다.

---

## DEC001: 독립 Android OCR 프로토타입과 `receipt.v2` 계약

### (버전 이름 혹은 브랜치 이름)

`feat/android-receipt-ocr-prototype` / `PriceTrace_OCR_APP`

#### 배경 / 제약

- 기존 PriceTrace 웹 프로젝트와 별도로, 빈 프로젝트에서 Android 전용 촬영·OCR 검수 흐름을 먼저 만들어야 했다.
- OCR 엔진과 Android URI 타입이 PriceTrace 도메인 계약에 침투하면 나중에 Capacitor/서버 연결 비용이 커진다.
- 실제 원격 연결은 아직 승인·검증되지 않았다.

#### 선택지

1. 기존 PriceTrace 앱을 바로 수정해 OCR과 원격 저장까지 한 번에 연결한다.
2. 독립 Kotlin/Compose 앱과 재사용 `:receipt-scanner` 모듈을 만들고 `receipt.v2`를 경계 계약으로 둔다.
3. OCR 문자열만 저장하고 나중에 임의의 JSON으로 변환한다.

#### 결정과 근거

- **사용자 확인**: 독립 Android 앱으로 영수증 스캔·검수부터 시작한다.
- **결정**: 선택지 2. 촬영·OCR adapter는 Android 모듈에 두되 도메인은 `receipt.v2`와 nullable evidence만 노출한다.
- **저장소 검증**: 앱과 `receipt-scanner`가 분리되어 있고 `ReceiptV2`, `ReceiptPublisher`, `LocalOnlyReceiptPublisher` 경계가 존재한다.
- **근거**: 기존 서비스의 변경 위험을 격리하고, 파서 정확도를 합성 fixture와 로컬 테스트로 반복 측정할 수 있다.

#### 재검토 조건

- PriceTrace 쪽에서 Android bridge와 서버 업로드 계약, 인증·RLS, 실패 재시도 정책이 별도로 승인되고 검증될 때 재검토한다.

#### 결과 회고

- **저장소 검증**: 현재 앱은 로컬 초안·검수·JSON 내보내기까지 동작하며 원격 publisher는 `local_only`만 반환한다.
- **계획**: PriceTrace/Supabase 업로드와 표준 상품 연결은 이 결정의 후속 작업이지 현재 완료 범위가 아니다.

---

## DEC002: OCR 결과는 초안이고, 사용자 검수 후에만 확정

### (버전 이름 혹은 브랜치 이름)

`receipt.v2` / `draft` → `user_verified`

#### 배경 / 제약

- 작은 글씨, 분절된 숫자, 지점·주소·상품표의 다양한 배치 때문에 OCR 결과를 곧바로 정답으로 취급할 수 없다.
- 잘못 읽은 상품이나 금액을 자동으로 외부 데이터와 연결하면 원본 근거와 사용자 수정이 사라진다.

#### 선택지

1. OCR 결과를 즉시 확정하고 외부 공유한다.
2. OCR 결과를 삭제하고 모든 값을 수동 입력한다.
3. OCR 초안과 위치 근거를 보존하고, 검수 차단 조건을 통과한 경우에만 확정한다.

#### 결정과 근거

- **사용자 확인**: 필드 검수 화면에서 원본과 대조하고, 정보가 불확실하면 널리 허용하되 확정은 검수 후에 한다.
- **결정**: 선택지 3. 이미지·OCR line/element·bounding box·파서 버전·수정 이력을 보존하며 없는 값은 `null`로 둔다.
- **저장소 검증**: `ReceiptReviewController`, `ReceiptValidator.markUserVerified`, 앱의 `user_verified` 차단 문구가 이 경계를 구현한다.

#### 재검토 조건

- 개인정보 보호, 원본 provenance, 서버 측 검증을 포함한 별도 업로드 계약이 마련될 때 외부 확정 흐름을 재검토한다.

#### 결과 회고

- **저장소 검증**: draft는 앱 전용 저장소에 남고, 필수 검증 전에는 외부 공유가 차단된다.
- **미검증**: 실제 기기에서 전체 촬영→OCR→검수→공유 흐름을 통과한 것은 아직 확인하지 않았다.

---

## DEC003: 단순 문자열 OCR 대신 구역·표 구조를 먼저 해석

### (버전 이름 혹은 브랜치 이름)

`generic-parser.v13`

#### 배경 / 제약

- 판매처명은 안내문에서 잘못 뽑히고, 상품 여러 줄이 하나로 합쳐지는 문제가 반복됐다.
- 사용자는 “단순 OCR 기능만으로는 너무 고난도인가?”를 확인한 뒤, 구역별 구조로 작동을 시작하도록 선택했다.

#### 선택지

1. OCR이 반환한 전체 문자열에서 전역 정규식으로 필드를 찾는다.
2. OCR 문자열을 LLM/외부 서비스에 보내 의미를 추출한다.
3. OCR line/element의 위치를 이용해 header·상품표·합계·결제 구역을 나누고 구역별 규칙을 적용한다.

#### 결정과 근거

- **사용자 확인**: 구역별 의도에 맞게 작동하되 조건은 널널하게 유지한다.
- **결정**: 선택지 3. 구역을 먼저 정하고 각 구역에서만 후보를 수집한다.
- **저장소 검증**: `ReceiptSections`, `ReceiptFieldExtractor`, `ReceiptLineItemExtractor`, `ReceiptParser`가 이 흐름을 사용하며 파서 버전은 `generic-parser.v13`이다.
- **근거**: 전역 검색보다 위치·행·열 관계를 이용하면 안내문·카드번호·사업자정보를 상품이나 합계로 오인할 범위를 줄일 수 있다.

#### 재검토 조건

- 영수증 표본에서 구역 경계가 반복적으로 실패하거나 판매처별 전용 layout profile이 충분히 확보되면 공통 규칙과 profile 분리 여부를 재검토한다.

#### 결과 회고

- **저장소 검증**: 합성 회귀 테스트가 판매처·합계·상품표·안내문 경계를 독립적으로 검증한다.
- **한계**: 다단 표·로고형 판매처·극단적으로 긴 영수증은 여전히 실제 기기 표본으로 확인해야 한다.

---

## DEC004: 전역적으로 빡빡한 정규식 대신 필드별 완화 규칙

### (버전 이름 혹은 브랜치 이름)

필드 추출 정책: 판매처·지점·사업자번호·주소·구매시각·통화·최종 결제금액

#### 배경 / 제약

- 사용자는 “조건은 널널하되, 정보는 확실하게”를 반복해서 요구했다.
- 라벨 사이의 공백, 숫자 분절, 작은 글씨, `OO점` 같은 지점 표기 변형을 한 규칙으로 처리하면 누락 또는 과잉 추출이 생긴다.

#### 선택지

1. 모든 필드에 동일한 엄격 정규식을 적용한다.
2. 후보를 넓게 수집하되 구역·공간·라벨·형식·provenance를 함께 평가하고, 불확실한 값은 `null`로 남긴다.
3. 판매처별 문자열 사전을 하드코딩한다.

#### 결정과 근거

- **사용자 확인**: 넓은 후보를 허용하되 엉뚱한 문장을 확정하지 않는 방향을 선택했다.
- **결정**: 선택지 2. 라벨 공백은 복원하되 후보 범위는 해당 필드 구역과 인접 행으로 제한한다.
- **강한 추론**: 정확성은 정규식 하나의 엄격함보다 후보를 찾는 범위와 원본 위치 근거를 함께 제한할 때 더 안정적이다.

#### 재검토 조건

- 익명화된 표본에서 필드별 false positive/false negative를 분리 측정할 수 있게 되면 필드별 threshold와 fallback 행 수를 수치로 조정한다.

#### 결과 회고

- **저장소 검증**: 사업자번호·주소·시간·통화·합계가 서로 다른 extractor와 validation 경로를 사용한다.
- **미측정**: 현재 저장소에는 실제 영수증 표본 기반 precision/recall 수치가 없다.

---

## DEC005: 판매처명은 전체 브랜드 블록을 보존하고 옆의 작은 글씨를 제외

### (버전 이름 혹은 브랜치 이름)

예: `GS THE FRESH`

#### 배경 / 제약

- 같은 행에 판매처명과 작은 부가정보가 붙어 있을 때 `GS`만 남거나 옆 문구까지 판매처명에 포함되는 문제가 있었다.
- `마트` 같은 부분 문자열만 검색하면 안내문·하단 문구가 판매처로 선택될 수 있다.

#### 선택지

1. 첫 번째 영문/한글 토큰만 판매처명으로 사용한다.
2. `마트` 등 키워드가 있는 가장 가까운 문자열을 사용한다.
3. 상단 이름 블록에서 글자 높이·수평 연속성·중앙 정렬을 평가해 같은 크기의 브랜드 토큰을 공백 포함으로 묶고 작은 인접 토큰은 제외한다.

#### 결정과 근거

- **사용자 확인**: `GS THE FRESH` 전체를 보존하되 옆의 작은 정보는 판매처명에서 제외한다.
- **결정**: 선택지 3. 이름 블록의 dominant height와 연속 요소를 우선하고 부분 문자열은 보조 신호로만 사용한다.
- **저장소 검증**: `dominant size merchant tokens exclude smaller adjacent text`, `spaced merchant brand ... infer krw` 회귀 테스트가 전체 브랜드와 공백을 확인한다.

#### 재검토 조건

- 브랜드가 여러 줄로 배치되거나 로고와 실제 상호의 글자 높이가 다른 표본이 늘면 이름 블록 profile과 사용자 선택 UI를 재검토한다.

#### 결과 회고

- **저장소 검증**: `GS THE FRESH`가 하나의 판매처명으로 유지되고 인접 작은 텍스트가 제외되는 합성 케이스가 통과했다.
- **미검증**: 실제 `GS THE FRESH` 영수증 촬영 결과의 재현은 실기기 체크리스트에 남아 있다.

---

## DEC006: 주소·지점·사업자번호·구매시각은 헤더의 시작·형식 근거를 분리

### (버전 이름 혹은 브랜치 이름)

헤더 metadata 추출 정책

#### 배경 / 제약

- 주소가 작은 글씨라는 이유로 상호·사업자번호·기타 헤더를 처음부터 함께 담는 문제가 있었다.
- `OO점`, 공백이 섞인 사업자등록번호, 라벨이 분절된 주소·시각은 단일 line 정규식으로 놓치기 쉽다.

#### 선택지

1. 헤더 전체를 주소 후보로 사용한다.
2. 라벨이 있는 한 줄만 사용한다.
3. 주소는 첫 지역 토큰(`서울`, `부산`, `…시/도/군/구`)부터 시작해 연속된 도로명·번지 행만 결합하고, 지점·사업자번호·시각은 각자의 형식과 근접 행으로 별도 추출한다.

#### 결정과 근거

- **사용자 확인**: 주소는 지역 이름부터 시작해야 하며 앞의 상호·사업자번호가 들어가면 안 된다고 선택했다.
- **결정**: 선택지 3. 시작 토큰이 없으면 억지로 주소를 만들지 않고 `null`로 남긴다.
- **저장소 검증**: `address starts at the first regional token inside a dense header row`와 metadata 오염 방지 회귀 테스트가 통과했다. `ReceiptFieldExtractor.sanitizeAddress`가 이 경계를 구현한다.

#### 재검토 조건

- 도로명 주소가 지역 토큰 없이 영문·건물명만으로 인쇄되거나 주소가 여러 블록으로 분리되는 표본이 확인되면 주소 profile과 사용자 검수 보조를 추가한다.

#### 결과 회고

- **저장소 검증**: dense header에서 주소가 `서울특별시 ...`부터 시작하고 앞의 상호·번호가 제거된다.
- **미검증**: 작은 글씨와 실제 카메라 보정까지 포함한 기기 OCR 결과는 별도 표본 검증이 필요하다.

---

## DEC007: 상품표는 x좌표 열 경계로 상품명·단가·수량·금액을 분리

### (버전 이름 혹은 브랜치 이름)

상품표 규칙: `상품명`/`품명` · `단가` · `수량` · `금액`

#### 배경 / 제약

- 상품 두 줄이 한 상품으로 합쳐지고, 상품명 뒤 숫자·특수기호가 상품명에 붙으며, 수량은 자주 누락됐다.
- 단순 line 순서만 보면 한 OCR element 안에 붙은 여러 숫자의 역할을 구분할 수 없다.

#### 선택지

1. OCR line을 통째로 하나의 상품명으로 저장한다.
2. 숫자 개수와 순서만 보고 단가·수량·금액을 추정한다.
3. 표 헤더의 실제 x좌표를 열 경계로 삼고, 각 OCR element를 셀에 배정한 뒤 행 단위로 결합한다.

#### 결정과 근거

- **사용자 확인**: 상품명 아래로 각 영역을 세로로 구분해 이름과 숫자를 명확히 처리하도록 선택했다.
- **결정**: 선택지 3. 이름 셀의 후행 숫자·`*`/`＊`/`※` 표식을 제거하고 숫자는 가까운 수량·단가·금액 열로 보낸다.
- **저장소 검증**: `ReceiptLineItemExtractor`가 header alias, x좌표, 복합 numeric element 분할, `quantity × unit price = row amount` 보존식을 사용한다. 상품명 분리·동일 열 누적 방지·안내문 차단 회귀 테스트가 통과했다.

#### 재검토 조건

- 표 헤더가 전혀 없고 열 x좌표도 안정적이지 않은 다단/세로쓰기 영수증이 반복되면 판매처별 profile 또는 수동 셀 보정 UI를 검토한다.

#### 결과 회고

- **저장소 검증**: 상품명에 옆 숫자가 섞이지 않고, 연속된 상품명이 하나의 pending item으로 무제한 누적되지 않는다.
- **미검증**: 실제 상품표의 행 precision/recall은 표본 수집 후 `docs/ACCURACY_EVALUATION.md` 절차로 측정해야 한다.

---

## DEC008: 단가가 없는 상품은 `null`로 보존하고 역산하지 않음

### (버전 이름 혹은 브랜치 이름)

`unit_price_amount_minor = null`

#### 배경 / 제약

- 사용자는 단가가 인쇄되지 않은 영수증도 존재한다고 명시했다.
- 금액 ÷ 수량으로 단가를 만들어 넣으면 할인·중량·반올림을 잘못된 사실로 확정할 수 있다.

#### 선택지

1. 단가를 행 금액과 수량으로 항상 역산한다.
2. 단가가 없으면 상품 행 전체를 버린다.
3. 명시적 단가가 있을 때만 보존식을 검사하고, 없으면 단가는 `null`인 채 수량·행 금액·근거를 보존한다.

#### 결정과 근거

- **사용자 확인**: 단가 누락을 정상적인 영수증 변형으로 취급한다.
- **결정**: 선택지 3. `unit_price_amount_minor`를 추정값으로 채우지 않는다.
- **저장소 검증**: `table without unit price preserves explicit quantity and line amount`, `compound quantity and amount keep absent unit price null` 테스트와 `ReceiptValidation`이 이 정책을 확인한다.

#### 재검토 조건

- 영수증에 명시된 “개당”, “중량당” 단가와 할인 규칙을 별도 provenance로 안정적으로 읽을 수 있을 때만 제한적인 역산을 다시 검토한다.

#### 결과 회고

- **저장소 검증**: 단가 열이 없는 표에서 수량과 행 금액은 남고 단가는 빈칸이다.
- **장기 효과**: 누락 증거를 거짓 정밀도로 바꾸지 않아 사용자 검수가 가능한 상태로 남는다.

---

## DEC009: 분절 최종 금액은 공간·쉼표 근거로 복원하고 세금과 분리

### (버전 이름 혹은 브랜치 이름)

예: `26, 760` → `26760 KRW`

#### 배경 / 제약

- OCR이 최종 결제금액을 `26`, `,`, `760`으로 나누거나 `26, 700`을 `26` 또는 `700`으로만 반환했다.
- 세금이 없는 영수증에서 과세물품가액을 세금으로 잘못 채우는 문제가 있었다.

#### 선택지

1. 가장 큰 숫자 하나를 최종 금액으로 사용한다.
2. 모든 숫자를 이어 붙이고 마지막 숫자를 세금으로도 재사용한다.
3. 최종/승인 라벨과 인접성·쉼표 경계를 만족하는 숫자 조각만 하나로 복원하고, 명시적 세금 라벨이 없으면 세금은 `null`로 둔다.

#### 결정과 근거

- **사용자 확인**: 결제 금액 전체를 자동 인식하고 세금 내역으로 오인하지 않도록 선택했다.
- **결정**: 선택지 3. 실제 표/결제 영역의 spatial adjacency와 comma boundary를 통과한 조합만 허용하며 카드 승인금액은 최종 금액 fallback으로만 사용한다.
- **저장소 검증**: `same row split label and amount`, `split grouped line amount`, `fragmented total context`, `sequential payment total is not reused as an absent tax amount` 회귀 테스트가 통과했다.

#### 재검토 조건

- 분절 숫자가 인접 상품 열과 겹치는 판매처별 표본에서 오탐이 확인되면 후보 보존식과 결제 구역 경계를 조정한다. 실제 세금 라벨 변형이 확인될 때만 세금 alias를 추가한다.

#### 결과 회고

- **저장소 검증**: `26,760`이 하나의 정수로 유지되고 통화 문맥이 있으면 `KRW`가 채워진다. `과세물품가액`만 있는 경우 세금은 채워지지 않는다.
- **미검증**: 실제 영수증에서의 통화·최종 금액 정확도는 기기 표본 측정 전까지 주장하지 않는다.

---

## DEC010: 표 안내문·회원/카드/사업자 정보는 상품 행에서 제외

### (버전 이름 혹은 브랜치 이름)

상품 행 false positive 방지 규칙

#### 배경 / 제약

- 마트 안내문이나 결제 이후 문장이 상품명으로 추출되고, 사업자·회원·카드 번호가 상품 숫자로 섞였다.
- 조건을 강화하면 정상적인 저가 상품이나 단가가 없는 상품까지 삭제할 위험이 있다.

#### 선택지

1. 상품명처럼 보이는 모든 문장을 상품으로 만든다.
2. 숫자가 적은 행을 모두 제거한다.
3. 안내문 키워드·결제 이후 경계·metadata 행은 제외하되, 표 안에서 이름 또는 행 금액 근거가 있는 저신뢰 행은 별도 검수 행으로 보존한다.

#### 결정과 근거

- **사용자 확인**: 엉뚱한 문장을 상품으로 확정하지 않되, 규칙을 지나치게 빡빡하게 만들지는 않도록 선택했다.
- **결정**: 선택지 3. `isNonProductDescription`와 구역 경계로 명백한 문장만 차단하고 애매한 행은 confidence/provenance와 함께 남긴다.
- **저장소 검증**: `header payment notice does not cut off the later item table`, `loose numeric discovery does not turn receipt metadata into products` 및 name-only table 회귀 테스트가 통과했다.

#### 재검토 조건

- 실제 표본에서 false positive가 특정 안내문 패턴에 집중되거나 정상 상품 recall이 낮아질 때, 전역 차단이 아니라 해당 구역·패턴만 조정한다.

#### 결과 회고

- **저장소 검증**: 안내문이 뒤의 상품표를 잘라내지 않고, metadata-only 행이 상품으로 생성되지 않는다.
- **미측정**: 실제 표본의 precision/recall 수치는 아직 없다.

---

## DEC011: 표준 상품 자동 연결은 보류하고 로컬 검수 경계를 유지

### (버전 이름 혹은 브랜치 이름)

`LocalOnlyReceiptPublisher` / `catalog_namespace = null`

#### 배경 / 제약

- 상품명이 비슷하다는 이유만으로 PriceTrace 표준 상품을 자동 연결하면 SKU·판매처·규격이 다른 상품을 합칠 수 있다.
- 현재 목표는 OCR 필드와 상품 행의 원본 검수이며, 원격 DB·로그인·RLS 계약은 구현 범위가 아니다.

#### 선택지

1. 상품명 문자열이 비슷하면 표준 상품을 자동 연결한다.
2. 상품 검색을 먼저 완성한 뒤 OCR 결과를 확정한다.
3. OCR 행과 merchant SKU/provenance만 보존하고 표준 상품 연결은 별도 승인 흐름으로 보류한다.

#### 결정과 근거

- **사용자 확인**: 우선 판매처·금액·상품 영역의 오인식을 해결하고 자동 연결은 뒤로 둔다.
- **결정**: 선택지 3. `catalog_namespace`와 표준 product ID를 근거 없이 채우지 않는다.
- **저장소 검증**: `LocalOnlyReceiptPublisher`만 구현되어 있고, 파서 테스트는 표준 상품을 추론하지 않는 것을 확인한다.

#### 재검토 조건

- 판매처·SKU·규격·승인된 mapping proposal을 포함한 표준 상품 연결 계약과 사람의 승인 UI가 마련될 때 재검토한다.

#### 결과 회고

- **저장소 검증**: 현재 결과는 로컬 receipt.v2 초안/검수에 한정된다.
- **계획**: 원격 등록, 자동 상품 mapping, Supabase/RLS는 별도 결정과 실환경 증거 없이는 완료로 표시하지 않는다.

---

## DEC012: 실기기 설치와 실제 OCR 검증을 별도 증거로 기록

### (버전 이름 혹은 브랜치 이름)

`0.1.12` / `versionCode 13` debug APK

#### 배경 / 제약

- Gradle 테스트와 APK 생성만으로는 카메라·Google Play Services·ML Kit 모델·실제 영수증 OCR이 검증되지 않는다.
- 사용자는 반복해서 새 기기 연결과 실기기 설치를 요청했다.

#### 선택지

1. 로컬 빌드 성공을 실기기 완료로 간주한다.
2. 연결된 기기에 APK를 설치하고 패키지 버전을 확인한 뒤, 촬영/OCR 정확도는 별도 체크리스트로 남긴다.
3. 실제 영수증 한 장이 잘 읽히면 전체 기기 검증으로 간주한다.

#### 결정과 근거

- **사용자 확인**: 문서 작업 전에 연결된 실기기 설치부터 수행한다.
- **결정**: 선택지 2. 설치 증거와 OCR 정확도 증거를 분리한다.
- **런타임 검증**: 2026-08-05 `adb` 대상 `SM-A256N`에 `app-debug.apk` 설치가 `Success`였고, `dumpsys package`에서 `versionCode=13`, `versionName=0.1.12`를 확인했다.
- **저장소 검증**: 로컬 Gradle 테스트·lint·APK 패키징은 별도 명령으로 확인하며, 실제 OCR 표본 성공률은 아직 기록하지 않는다.

#### 재검토 조건

- 기기에서 문서 스캐너 구성요소 준비, 촬영·보정, OCR, 필드 검수, 복원, 공유 체크리스트를 모두 실행한 뒤에만 “실기기 검증 완료” 상태로 갱신한다.

#### 결과 회고

- **완료**: 설치와 패키지 버전 확인.
- **미완료**: 실제 영수증 OCR 정확도, Google Play Services 모델 다운로드, `connectedDebugAndroidTest`, 외부 공유 E2E.
- 상세 절차는 [`DEVICE_TEST_CHECKLIST.md`](DEVICE_TEST_CHECKLIST.md)와 [`ACCURACY_EVALUATION.md`](ACCURACY_EVALUATION.md)에 따른다.

---

## DEC013: 수량과 상품명 OCR 보정은 문맥으로 증명되는 범위에 제한

### (버전 이름 혹은 브랜치 이름)

`generic-parser.v15` / `0.1.14`

#### 배경 / 제약

- 실제 사용 피드백에서 판매처·총액·상품 행은 안정됐지만, 수량 글자가 `I`·`S`처럼 인식되거나 아예 누락되는 사례가 남았다.
- `제스프리골드 → 제스프리플드`, `1kg → 1ks`처럼 상품명과 규격의 한 글자 오독도 확인됐다.
- 모든 유사 문자를 전역 치환하면 정상 상품명·SKU·중량 표기를 손상시키고, OCR 원문과 다른 값을 확정할 위험이 있다.

#### 선택지

1. `S → 5`, `플 → 골`, `s → g` 같은 전역 치환을 적용한다.
2. OCR 문자열은 전혀 보정하지 않고 사용자가 모두 직접 고친다.
3. 수량은 열 위치와 금액 보존식으로 검증하고, 단위는 숫자 뒤 규격 문맥, 상품명은 검증된 전체 단어의 유일한 편집거리 1 후보에서만 보정한다.

#### 결정과 근거

- **사용자 확인**: 현재 정확한 판매처·총액·상품 행 동작을 유지하면서 수량과 문자 인식의 남은 오독을 다듬는다.
- **결정**: 선택지 3. 수량 글자 후보는 명시적 수량 열과 `수량 × 단가 = 행 금액`이 함께 맞을 때만 채택한다.
- **결정**: 수량 셀이 읽히지 않아도 단가·수량·금액 열이 명시되고 단가와 행 금액이 정확한 1~100 정수 비율일 때만 낮은 신뢰도로 수량을 계산 복원한다. 분수·반올림·불일치는 `null`로 둔다.
- **결정**: `숫자 + ks/k5`만 `kg`로 정규화하고, 상품명은 전체 한글 토큰이 등록 힌트와 유일하게 한 글자 차이일 때만 고친다. 원 OCR 문자열은 provenance에 보존한다.
- **저장소 검증**: 직접 인쇄 수량, 보존식으로 확인된 OCR 글자 수량, 보존식에 맞지 않아 거부된 글자 수량, 정확한 금액 비율로 복원한 누락 수량, 상품명·kg 보정과 원문 provenance 회귀 테스트를 포함해 84 unit tests가 통과했다.

#### 재검토 조건

- 실제 영수증 표본에서 같은 오독이 반복되면 원문·정답 쌍을 개인정보 제거 fixture로 추가한 뒤 힌트를 확장한다.
- 힌트가 커지거나 둘 이상의 후보가 경쟁하기 시작하면 앱 내부 정적 목록 대신 버전이 있는 검수 사전과 사용자 승인 UI로 분리한다.
- 계산 복원 수량이 할인·묶음·중량 상품에서 오탐을 만들면 판매처/표 형식별로 비활성화하거나 검수 전용 제안으로 낮춘다.

#### 결과 회고

- **저장소 검증**: 합성 회귀 입력에서 `제스프리플드 1ks`는 표시값 `제스프리골드 1kg`로 보정되며 provenance에는 원문이 남는다. `S` 수량은 금액 보존식이 맞을 때만 `5`가 되고, 불일치하면 `null`이다.
- **런타임 검증**: 2026-08-08 `0.1.14` APK를 `SM-A256N`에 업데이트 설치했고 package metadata와 resumed `MainActivity`를 확인했다.
- **미측정**: ML Kit 자체 문자 인식률과 실제 영수증 수량 정확도는 기기 표본 재검증 전까지 완료로 간주하지 않는다.

---

## DEC014: Gemini는 OCR 후처리 제안기로만 사용하고 자동 확정하지 않음

### (버전 이름 혹은 브랜치 이름)

`gemini-receipt-correction.v1` / `0.1.15`

#### 배경 / 제약

- 규칙 파서로 복원하기 어려운 상품명·수량 문자 오독은 남지만, 전체 영수증을 LLM이 다시 파싱하게 하면 기존에 안정된 필드와 provenance를 손상시킬 수 있다.
- 실제 영수증에는 주소·전화번호·사업자번호·결제 정보가 포함될 수 있고, Gemini 무료 tier의 데이터 사용 조건은 유료 tier와 다를 수 있다.
- AI 응답은 확률적이며 구조화 출력이어도 OCR 근거와 산술 일관성이 자동으로 보장되지 않는다.

#### 선택지

1. 전체 영수증 이미지와 OCR 원문을 Gemini에 보내 완성 JSON을 교체한다.
2. Gemini를 사용하지 않고 규칙과 정적 사전만 계속 확장한다.
3. 기존 OCR 상품 행과 좁은 이미지 근거만 보내 교정 후보를 받고, 앱 검증과 사용자 개별 승인을 거친다.

#### 결정과 근거

- **결정**: 선택지 3. Gemini는 `description`, `quantity`, `unit_price_amount_minor`, `net_amount_minor`의 제안기이며 새 행·삭제·합계·판매처 필드는 다루지 않는다.
- **결정**: 사용자가 버튼을 누른 경우에만 아직 사용자 확인 전인 최대 12개 기존 상품 행, 민감정보 형태 필터를 통과한 source line 문자열, 각 line bounding box 주변 JPEG crop 최대 8개를 전송한다. 전체 영수증 이미지는 전송하지 않는다.
- **결정**: 현재 값 일치, 허용 field path, 알려진 source line, 중복, 값 형식, `수량 × 단가 = 행 금액` 보존식을 앱에서 검사한다. 거부된 응답은 초안에 반영하지 않는다.
- **결정**: 통과 후보도 개별 `적용`이 필요하고 낮은 신뢰도로 남긴다. provider, model, prompt version과 source line을 수정 provenance에 기록하며 최종 `user_verified` 절차를 유지한다.
- **당시 결정**: vendor-neutral 계약·정책은 `receipt-scanner/correction`, Firebase Gemini SDK·App Check·이미지 crop은 `app` adapter에 둔다. 이 provider 선택은 `0.1.16`의 DEC015에서 직접 Gemini API 방식으로 대체했다.

#### 재검토 조건

- 개인정보 제거 표본에서 규칙 파서 baseline 대비 필드 오류율 또는 검수 시간이 유의미하게 개선되지 않으면 기능을 기본 비활성 또는 제거한다.
- 누락 행 복원이나 판매처 필드 교정이 필요해도 기존 계약을 넓히지 않고 별도 threat model, 데이터 최소화와 승인 UI를 먼저 설계한다.
- 모델명·가격·무료 tier 데이터 조건·App Check 정책이 바뀌면 배포 전에 provider 설정과 사용자 고지를 다시 검토한다.

#### 결과 회고

- **저장소 검증**: 전송 최소화, 허용/거부 field path, source line, stale 값, 산술 보존식, 중복 후보, 승인 provenance와 UI 요청 경계를 합성 테스트로 검증했다.
- **미검증**: 당시 Firebase 실연결은 실행하지 않았고, 이후 adapter를 제거했다. 실제 Gemini 응답, 실제 영수증 crop 및 정확도 개선은 DEC015에서도 아직 실행하지 않았다.

---

## DEC015: 개인용 Gemini API 키를 런타임에 저장하고 직접 호출함

### (버전 이름 혹은 브랜치 이름)

`gemini-direct-interactions.v1` / `0.1.16`

#### 배경 / 제약

- 사용자는 Firebase AI Logic가 아니라 이미 보유한 Gemini API 키를 직접 연결하도록 명시적으로 요청했다.
- API 키를 소스, Gradle property 또는 `BuildConfig`에 넣으면 APK와 Git에서 추출될 수 있다.
- 반대로 모바일 클라이언트의 런타임 암호화 저장도 production server 수준의 비밀 보관을 제공하지 않는다.
- 기존의 데이터 최소화, 구조화 후보, 앱 재검증과 사용자 승인 경계는 provider 교체와 무관하게 유지해야 한다.

#### 선택지

1. Firebase AI Logic와 App Check를 유지한다.
2. API 키를 Gradle/`BuildConfig`에 넣고 Gemini API를 직접 호출한다.
3. 앱 화면에서 키를 입력받아 Android Keystore로 암호화 저장하고 직접 호출하되 개인 사용으로 제한한다.
4. 인증과 사용자별 quota가 있는 backend proxy를 먼저 만든다.

#### 결정과 근거

- **결정**: 사용자의 현재 개인용 앱 범위에서 선택지 3을 구현한다. 공개 배포 범위가 생기면 선택지 4로 전환한다.
- **결정**: 키는 빌드 입력으로 받지 않는다. 앱 화면에서만 입력받고 Android Keystore AES-GCM key로 암호화한 ciphertext와 IV만 private SharedPreferences에 저장한다. 현재 키는 화면에 다시 표시하지 않는다.
- **결정**: `POST /v1beta/interactions`를 platform HTTPS로 호출하고 키는 `x-goog-api-key` header에만 넣는다. request JSON, provenance와 오류 메시지에는 넣지 않는다.
- **결정**: 요청에 JSON Schema 응답 형식을 지정하고 `store=false`, 동기식 non-streaming으로 호출한다. 완료된 `model_output` text만 해석한다.
- **결정**: 401/403은 인증, 429는 할당량, timeout/IO는 네트워크, 5xx와 기타 HTTP는 provider 실패로 분리한다. provider error body를 화면·로그에 전달하지 않는다.
- **결정**: Firebase BoM, Firebase AI, App Check와 Google Services plugin을 제거한다. vendor-neutral correction 계약과 정책은 그대로 유지한다.

#### 재검토 조건

- APK를 다른 사용자에게 배포하거나 공용 저장소에 release artifact를 올리기 전에 backend proxy, 사용자 인증, API 제한, 사용자별 quota와 비용 경보로 전환한다.
- Gemini가 Interactions endpoint·응답 step 계약·모델 ID를 바꾸면 protocol fixture와 실제 호출을 함께 갱신한다.
- Android Keystore 키 무효화나 기기 이전 요구가 생기면 재입력 UX와 biometric gate를 별도 설계한다.

#### 결과 회고

- **저장소 검증**: 요청 body의 `store=false`, structured schema, 이미지 inline 형식과 키 비포함, 완료 응답 mapping, 401/429 분류를 가짜 HTTP 단위 테스트로 확인했다. 기존 correction 정책과 사용자 개별 승인 테스트도 유지했다.
- **보안 경계**: APK·소스에 API 키를 넣지 않았고 저장 키 삭제 경로를 제공한다. Android Keystore 사용만으로 rooted/debuggable 기기에서의 추출 불가능성을 주장하지 않는다.
- **미검증**: 실제 사용자 키 저장·복원, Gemini 네트워크 응답, quota, 기기 crop, 무료 tier 조건과 정확도 개선은 아직 실환경에서 확인하지 않았다.

## DEC016: 촬영·OCR은 공통 세션으로 통일하고 후속 계약만 워크플로별로 분기함

### (버전 이름 혹은 브랜치 이름)

`multi-workflow-ocr.v1` / `0.2.0`

#### 배경 / 제약

- PriceTrace 영수증과 Fitness 상품 라벨은 촬영, ML Kit 보정, 페이지 저장, 한국어 OCR, 원본 복원까지 같은 기능을 사용한다.
- 반면 출력 계약과 검수 조건은 다르다. PriceTrace는 `receipt.v2`, Fitness는 Nutrition DB `nutrition_foods`와 필수 7종 계약을 소유한다.
- Fitness와 PriceTrace는 별도 DB/인증 경계를 유지하며, 상품명 유사도는 정확한 product-nutrition link를 대신할 수 없다.
- 실제 Nutrition DB migration/RLS 적용 상태와 사용자 자격증명은 저장소에 없으므로 로컬 구현 완료와 실연결 완료를 구분해야 한다.

#### 선택지

1. Fitness용 별도 스캐너 앱과 OCR 저장소를 복제한다.
2. receipt 모델에 영양성분 필드를 섞어 하나의 거대 JSON으로 만든다.
3. 공통 session/page/OCR을 유지하고 `workflow_type` 뒤의 parser, review, publisher만 분리한다.

#### 결정과 근거

- **결정**: 선택지 3. Room schema v4에 `workflow_type`, `display_title`, `workflow_draft_storage_key`를 추가한다. v1~v3 기존 행은 `pricetrace_receipt`로 migration한다.
- **결정**: ML Kit engine의 공식 이름은 `OcrEngine`/`MlKitDocumentOcrEngine`으로 일반화하고 기존 receipt 이름은 source-compatible alias로 보존한다.
- **결정**: `fitness_nutrition`은 `nutrition-label-parser.v2`와 `fitness-nutrition-draft.v1`을 사용한다. 영양정보 헤더부터 원재료·알레르기 영역 전까지만 성분을 자동 포착하고, 상품명·브랜드는 자동 추정하지 않아 사용자가 직접 입력한다. 모르는 영양소는 0이 아니라 null이며 필수 7종이 없으면 전송을 차단한다.
- **결정**: Gemini API 키와 Fitness Nutrition Supabase 연결은 홈의 단일 `API 설정` 화면에서 관리하고, 교정·영양 검수 화면은 같은 설정 카드와 저장 콜백을 재사용한다. Supabase 클라이언트 키는 publishable/anon 범위로 제한하고 사용자 인증 토큰 없이 private 행을 전송하지 않는다.
- **결정**: 루트 `.env`는 빌드 시 Nutrition HTTPS URL·publishable/anon key와 Gemini 모델명만 안전한 기본값으로 주입한다. `GEMINI_API_KEY`, 이메일·비밀번호는 APK/`BuildConfig`에 넣지 않고 기존 Keystore·로그인 입력 흐름을 유지한다. URL이 HTTPS가 아니거나 service-role/secret key이면 빌드를 중단한다.
- **결정**: 사용자 확정 후 별도 Nutrition Supabase URL·publishable/anon key와 Fitness 사용자 Bearer token으로 본인 소유 `private` 식품만 저장한다. 비밀번호는 저장하지 않고 access/refresh token만 Android Keystore로 암호화한다.
- **결정**: stable document food ID, source reference와 revision 조건을 사용한다. 다른 source 또는 원격 선행 수정은 무조건 덮어쓰지 않는다.
- **결정**: OCR evidence와 원본 이미지는 서버 payload에서 제외한다. `catalog_product_id`, `standard_product_id`, `product_nutrition_links`, 공개 전환은 이 앱이 만들지 않는다.

#### 재검토 조건

- 세 번째 워크플로가 추가되면서 ViewModel 분기가 계속 커지면 workflow coordinator와 화면 모듈을 별도 feature module로 분리한다.
- 실제 라벨에서 단일 규칙 파서가 표의 여러 기준 열을 안정적으로 구분하지 못하면 AI 자동 확정이 아니라 근거가 표시되는 후보 제안 계약을 별도로 설계한다.
- 공개 배포, 다중 사용자 전환 또는 장기 오프라인 요구가 생기면 backend mediation, account binding, WorkManager retry queue를 검토한다.

#### 결과 회고

- **저장소 검증**: 합성 라벨 파싱, null 보존, 필수 계약, local JSON, 가짜 로그인/refresh, private insert, revision PATCH, 충돌 차단 테스트를 추가했다.
- **계약 근거**: PriceTrace `origin/main` `34c06d0`, FitnessApp `origin/main` `25081ed`의 코드·migration을 대조했다. PriceTrace의 새 커밋은 문서만 변경했고, FitnessApp의 새 개발 탭·검증 단일식품 seed는 기존 Nutrition Supabase 저장 계약을 변경하지 않았다.
- **미검증**: `0.2.0` 실기기 UI, 실제 라벨 정확도, 실제 Nutrition 계정 로그인, 원격 migration/RLS와 Fitness App pull 종단간 흐름은 아직 실행하지 않았다.

---

## DEC017: 개인용 `.env`의 모든 값을 빌드와 앱 시작에 자동 주입함

### (버전 이름 혹은 브랜치 이름)

`dotenv-build-bootstrap.v1` / `0.2.0`

#### 배경 / 제약

- 사용자는 루트 `.env`의 Gemini API 키, Fitness Supabase URL/key, 이메일, 비밀번호, 모델을 별도 입력 없이 빌드에 반영하기를 명시적으로 요청했다.
- `BuildConfig`와 APK의 문자열은 디컴파일·런타임 계측으로 추출할 수 있으므로 개인용/내부용으로만 허용한다.

#### 결정과 근거

- **결정**: `.env`가 없거나 6개 필수 키 중 하나라도 없거나 비어 있으면 Gradle 빌드를 실패시킨다.
- **결정**: 알려진 6개 값과 `.env`의 모든 parsed key를 `BuildConfig` 문자열 필드로 생성한다. 실제 값은 Gradle 로그에 출력하지 않는다.
- **결정**: 앱은 Nutrition URL/key를 기본 연결로 사용하고, 앱 시작 시 `.env` 이메일/비밀번호로 자동 로그인을 시도하며, Gemini API 키를 직접 API 호출에 사용한다. API 설정 화면에도 같은 값이 채워진다.
- **결정**: 사용자가 입력한 Gemini 키는 기존 Android Keystore 저장값으로 우선한다. 저장 키 삭제 후에는 빌드 기본 키가 다시 사용된다.

#### 재검토 조건

- APK를 외부 사용자에게 배포하거나 저장소/릴리스 artifact를 공개할 때는 `.env` BuildConfig 주입을 제거하고 backend proxy, 사용자별 인증, quota 및 키 회전으로 전환한다.
