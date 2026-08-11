# PriceTrace · Fitness 공통 OCR Android App

촬영·보정·한국어 OCR·원본 페이지 보존은 하나의 공통 파이프라인으로 사용하고, 홈에서 선택한 세션 타입에 따라 후속 작업만 분기하는 독립 Android 앱입니다. `PriceTrace` 탭은 영수증을 검수해 `receipt.v2` JSON을 만들고, `Fitness App` 탭은 상품 라벨의 영양성분을 검수한 뒤 사용자 인증으로 Nutrition DB의 본인 소유 `private` 식품에 직접 저장합니다. OCR·Gemini·상품명 유사도만으로 값을 확정하거나 PriceTrace 상품을 자동 연결하지 않습니다.

## 현재 증거 경계

이 문서의 구현 주장은 2026-08-12 `feat/home-api-settings-nutrition-box` 작업 트리와 로컬 Gradle 실행을 기준으로 합니다. 계약 대조 기준은 PriceTrace `origin/main` `34c06d0`, FitnessApp `origin/main` `25081ed`입니다. 로컬 빌드 성공은 에뮬레이터, Google Play Services 다운로드, 실제 라벨 정확도, Gemini 실호출 또는 실제 Nutrition DB 저장 성공을 의미하지 않습니다.

| 검증 영역 | 현재 상태 | 근거 |
| --- | --- | --- |
| 로컬 단위 테스트 및 앱 컴파일 | 통과 | `gradlew.bat test --no-daemon` |
| Android lint | 통과 | `gradlew.bat lint --no-daemon` |
| debug APK 생성 | 통과 | `gradlew.bat assembleDebug --no-daemon` |
| instrumentation/UI 테스트 APK 컴파일 | 통과 | `gradlew.bat assembleDebugAndroidTest --no-daemon` |
| 에뮬레이터 UI 테스트 실행 | 미검증 | 연결된 ADB 대상 없음 |
| 실제 Android 기기 검증 | 새 API 설정 계측 1건 통과, 실제 OCR 재검증 전 | 현재 debug APK를 `SM-A256N`에서 사용해 홈→API 설정 진입을 통과했으며 실제 영수증·영양 라벨 정확도는 남음 |
| 행 추가·삭제, 되돌리기, 차액 진단 | 단위 테스트 통과, 실기기 미검증 | `ReceiptReviewControllerTest`, `ReconciliationDiagnosticsTest`, `OcrDigitConfusionTest`; 계측 테스트는 컴파일까지만 확인 |
| 검수 이력 기반 필드 오류율 | 단위 테스트 통과, 실측 표본 없음 | `ReviewAccuracyCalculatorTest`; 실제 영수증으로 계산한 수치는 아직 없음 |
| Room v1→v4 migration | 미검증 | 기존 세션을 PriceTrace로 유지하는 `MIGRATION_3_4`와 계측 테스트 소스는 구현·컴파일했지만 기기에서 실행하지 않음 |
| 문서 스캐너 구성요소 다운로드 | 미검증 | 실제 기기/Google Play Services 실행 안 함 |
| 실제 영수증 정확도 측정 | 정량 미측정 | 사용자 수동 표본에서 결함을 확인했지만 개인정보 원문을 저장소 테스트 fixture로 사용하지 않음 |
| Gemini 교정 계약·정책 | 단위 테스트 통과 | 전송 최소화, 허용 필드, OCR 근거, stale 값, 산술 보존식, 개별 승인 규칙을 합성 입력으로 검증 |
| Gemini Developer API 직접 연결 | 구현·가짜 HTTP 단위 테스트 통과, 실호출 미검증 | 키 없는 로컬 테스트만 실행했으며 실제 키·네트워크·할당량은 사용하지 않음 |
| PriceTrace 서버 연결 | 미구현 | `receipt.v2` 로컬 저장·공유까지만 제공하며 `LocalOnlyReceiptPublisher` 유지 |
| Fitness Nutrition DB 직접 저장 | 구현·가짜 HTTP 단위 테스트 통과, 실연결 미검증 | 비밀번호 로그인, 암호화 토큰, 401 refresh 1회, owner/private 행, revision 충돌 방지를 합성 응답으로 검증 |

## 두 워크플로

| 공통 단계 | PriceTrace | Fitness App |
| --- | --- | --- |
| 홈 선택 | `pricetrace_receipt` 세션 | `fitness_nutrition` 세션 |
| 촬영·갤러리·보정 | 동일한 ML Kit Document Scanner | 동일한 ML Kit Document Scanner |
| 페이지·OCR 보존 | 동일한 Room 세션, 앱 전용 파일, `OcrDocument` | 동일한 Room 세션, 앱 전용 파일, `OcrDocument` |
| 파싱 | `generic-parser.v15` | `nutrition-label-parser.v2` (영양정보 박스 범위) |
| 사용자 확정 | receipt 필드·상품 행·합계 검수 | 상품명·브랜드 직접 입력, 기준량·필수 7종·선택 4종 검수 |
| 후속 처리 | `user_verified receipt.v2` 로컬 저장/공유 | Fitness 사용자 토큰으로 `nutrition_foods` private 행 저장 |

Fitness 전송 계약, 설정 절차와 실패 경계는 [Fitness 영양성분 워크플로](docs/FITNESS_NUTRITION_WORKFLOW.md)에 정리했습니다.

## 구현 범위

- ML Kit Document Scanner의 full mode로 촬영, 갤러리 선택, 경계 수정, 원근·회전·필터 보정, JPEG, 최대 12페이지를 요청합니다.
- 긴 영수증은 같은 세션에 구간을 추가하며 기존 최대 `page_index` 뒤에 이어 붙입니다.
- 한국어 Text Recognition v2 결과를 Android/ML Kit 타입에서 분리된 block, line, element, bounding box DTO로 변환합니다.
- `generic-parser.v15`는 OCR 행을 상단 판매처·상품표·합계 요약·결제 영역으로 나눕니다. 판매처·지점·사업자번호·주소는 상단, 상품명·단가·수량·행 금액은 상품표, 소계·세금·할인·수수료는 합계 요약, 최종 금액·결제수단은 결제 영역의 후보를 우선합니다. 주소는 첫 지역 토큰부터 시작하고, 상품명 셀은 숫자 열 경계에서 잘라 단가·수량·금액을 별도 셀로 유지합니다. OCR 블록 순서가 헤더 metadata를 앞뒤로 섞어도 판매처 후보를 인접 행에서 다시 찾고, 브랜드 옆 연도 슬로건은 판매처명에서 제외합니다.
- `nutrition-label-parser.v2`는 `영양정보`/`영양성분` 또는 Nutrition Facts 헤더 이후 같은 페이지의 영양성분 영역만 보수적으로 검사하고 원재료·알레르기·보관 영역에서 멈춥니다. 나트륨부터 단백질까지 필수 7종과 라벨에 존재하는 선택 4종을 자동 입력하되, 상품명·브랜드는 자동 추정하지 않고 빈 값으로 남겨 사용자가 원본에서 직접 입력합니다. OCR 줄이 분리된 경우 인접 줄을 합쳐 라벨과 수치를 함께 찾으며, 영역을 식별하지 못한 경우 경고와 함께 영양성분 후보 줄만 사용합니다.
- 판매처는 상단 이름 블록의 행 높이·중앙 정렬·순서를 우선하고, `마트` 같은 부분 문자열은 보조 신호로만 사용합니다. `26`·`,`·`700`처럼 여러 행이나 인접 상품표 박스로 찢어진 금액도 원본 근거를 보존하며 하나의 정수로 복원합니다.
- 같은 판매처 행에 작은 부가정보가 붙으면 가장 큰 글자 높이의 연속 요소 묶음을 우선합니다. 상단 주소가 두 개의 작은 OCR 행으로 끊겨도 행정구역과 도로명 구조가 이어질 때만 결합합니다.
- 상품 표에서는 `상품명`/`품명`, `단가`, `수량`, `금액` 헤더의 실제 x좌표를 열 경계로 사용합니다. 한 OCR element에 여러 숫자가 합쳐져도 좌표 비율로 토큰을 다시 나누고, 열 거리와 `수량 × 단가 = 행 금액` 보존식을 함께 사용해 각 숫자의 역할을 결정합니다. 독립 숫자는 상품명 근거에서 제외합니다.
- 단가 열이나 명시적 단가가 없는 영수증은 행 금액과 수량으로 단가를 역산하지 않습니다. `unit_price_amount_minor`를 `null`로 보존하고, 상품명·명시적 수량·행 금액 근거가 있으면 검수를 계속할 수 있습니다.
- 표 헤더 한 글자가 잘못 인식되거나 헤더가 누락돼도 OCR element의 숫자 꼬리를 검사합니다. 인쇄된 두 숫자가 행 금액 보존식을 만족하거나 중량 상품의 1원 이내 반올림 범위일 때만 단가·수량으로 배정합니다. 같은 열의 새 상품명은 이전 상품명에 무제한 누적하지 않고 별도 저신뢰 상품으로 남기며, 포인트·회원번호·사업자정보·결제 이후 안내문은 상품 영역에서 제외합니다.
- 금액을 풀지 못한 상품 행에 단가·수량 열 숫자가 남아 있으면 다음 정상 상품과 합치지 않고 별도 검수 행으로 보존합니다. 상품명 양끝의 `*`·`＊`·`※` 표식은 제거하고, 수량 열의 작은 `1`이 `I`·`l`과 표식 조합으로 인식된 경우에만 수량 `1` 후보로 복원합니다.
- 수량 열의 `S`·`Z`·`O` 계열 숫자 혼동은 열 위치만으로 확정하지 않고 `수량 × 단가 = 행 금액`이 맞을 때만 채택합니다. 수량 셀이 읽히지 않아도 단가·수량·금액 헤더가 모두 있고 단가와 행 금액이 정확한 1~100 정수 비율이면 낮은 신뢰도의 계산 근거로 수량을 복원합니다. `1ks` 같은 규격은 숫자 뒤 단위 문맥에서만 `1kg`로 고치며 원 OCR 문자열은 provenance에 남깁니다.
- 파서 버전은 `document.source.notes`에 기록합니다. 이전 파서로 저장한 세션은 자동 덮어쓰기하지 않고 재분석 안내를 표시하며, 내보내기 manifest에는 실제 초안의 파서 버전만 기록합니다.
- 시간대 근거가 없는 현지시각은 `issued_at`을 만들어 내지 않고 `document.source.notes`의 `purchase_local_time=HH:mm:ss`로 보존해 검수 화면에 표시합니다.
- `과세물품가액`·`면세물품가액`은 세금 합계로 간주하지 않으며, 세금은 부가세·VAT·세액처럼 명시된 행만 채웁니다.
- 검수 화면은 이미지 확대, OCR bounding box 연결, 저신뢰 행 강조, 필드·행 편집, 합계 reconciliation, `user_verified` 차단 규칙을 제공합니다.
- 선택적 Gemini 교정 제안기는 사용자가 버튼을 누른 경우에만 아직 사용자가 확인하지 않은 기존 OCR 상품 행 최대 12개, 그 행이 참조하며 민감정보 형태 필터를 통과한 OCR 문자열, 각 line bounding box 주변 JPEG crop 최대 8개를 Gemini Developer API에 직접 전달합니다. Interactions 요청은 `store=false`이며, 전체 영수증 이미지와 판매처·주소·전화번호·사업자번호·카드·거래 식별 필드는 요청 계약에 포함하지 않습니다.
- Gemini API 키는 홈의 `API 설정` 또는 교정 화면에서 입력받아 Android Keystore의 AES-GCM 키로 암호화해 기기에 저장합니다. APK·Gradle·Git에는 넣지 않고 현재 키를 화면에 다시 표시하지 않습니다. 직접 API를 호출하는 모바일 앱은 production secret boundary가 아니므로 현재 방식은 개인 사용 범위입니다.
- Gemini는 상품명·수량·단가·행 금액의 후보만 만들 수 있습니다. 앱이 현재 값, source line, 허용 필드, 중복 제안, 값 형식, `수량 × 단가 = 행 금액`을 다시 검사하며, 통과한 후보도 사용자가 하나씩 적용해야 합니다. 적용 후 신뢰도는 낮게 유지되고 최종 `user_verified` 검수는 그대로 필요합니다.
- OCR이 통째로 놓친 행은 사용자가 직접 추가할 수 있고, 안내문을 상품으로 오인식한 행은 삭제할 수 있습니다. 직접 입력한 행은 OCR 근거 대신 `user_entered:<line_id>` 표식을 `source_line_references`에 남기고, 검수 화면과 내보낸 JSON 모두에서 "OCR 근거 없음"으로 구분됩니다. 이 표식은 확정을 막지 않고 경고로만 표시하며, 근거가 아예 없는 행은 계속 차단합니다. 삭제한 행은 전체 JSON이 수정 이력에 남습니다.
- 모든 검수 편집은 되돌리기·다시 적용을 지원합니다. 되돌리기는 이력을 지우지 않고 역방향 수정을 새 행으로 추가하므로, 이미 저장된 수정 이력과 어긋나지 않습니다.
- 합계가 맞지 않으면 차액 원인 후보를 제시합니다. 각 후보의 금액은 초안의 숫자에서 산술로 정해지며(`행 금액 + 차액`), 열화된 감열지에서 섞이기 쉬운 숫자 모양·자릿수 누락·자리 뒤바뀜 규칙은 그 후보를 보여줄지 판단하는 데만 사용합니다. `수량 × 단가` 불일치, 최종 합계 자체의 오인식, 중복 인식된 행, 할인·세금·수수료 행 누락, 누락된 행도 함께 진단합니다. 어떤 후보도 자동 적용하지 않으며, 사용자가 원본과 대조해 적용하면 일반 수정과 동일하게 기록됩니다.
- 검수 진행 상태(확인 완료 행 수, 차단·경고 건수)를 필드·행·합계 화면에 표시하고, 확인이 필요한 행만 보는 필터를 제공합니다.
- Room은 공통 세션·페이지·수정 이력·OCR/검수/내보내기/업로드 상태와 OCR 초안 생성 시각·실제 검수 완료 시각을 보존합니다. schema v4의 `workflow_type`, `display_title`, `workflow_draft_storage_key`가 후속 파서와 초안만 구분하며, 기존 v1~v3 세션은 migration에서 PriceTrace 세션으로 유지합니다.
- 이미지와 JSON은 앱 전용 파일 저장소에 두고 `AtomicFile`로 교체해, 덮어쓰기 도중 실패해도 직전 정상 초안을 보존합니다.
- 동일한 JSON은 정렬된 canonical JSON으로 같은 revision SHA-256과 idempotency key를 생성합니다.
- Android 공유 시트에는 `FileProvider`로 `receipt.json` 하나만 전달합니다. raw OCR은 기본 제외이며 이미지와 `ocr-debug.json`은 공유하지 않습니다.
- 평가 화면은 확정한 영수증의 검수 수정 이력에서 **필드별 오류율을 자동 계산합니다.** 정답을 따로 전사할 필요가 없습니다. 사용자가 고친 값이 곧 "OCR이 무엇을 틀렸는지"의 라벨이기 때문입니다. 주소·상품명·수량 등 필드마다 관측 수, 오류율, 틀리게 읽음/못 읽음/없는 값 생성 구분, 평균 수정 문자 수를 보여주고, 수정을 가장 많이 유발한 필드 순으로 정렬합니다. 검수 소요 시간 중앙값도 함께 계산해 "인식률"이 아니라 "검수 비용"을 볼 수 있게 합니다.
- 정확도 보고서는 건수와 비율만 담아 파일로 저장·공유할 수 있습니다. 판매처명·주소·상품명과 OCR 원문은 보고서에 포함되지 않습니다.
- 이 오류율은 사용자가 발견한 오류만 세므로 실제 오류율의 하한이며, 화면과 문서에 그렇게 표시합니다.
- CER, 문서 경계 검출률처럼 전체 전사 정답이 필요한 지표는 기존 수동 표본 입력으로 계산합니다.

## 사용자 흐름

1. 필요하면 홈의 `API 설정`에서 Gemini 키와 Fitness Nutrition Supabase URL·publishable/anon key를 저장하고 Fitness 계정으로 로그인합니다.
2. 저장 세션 목록에서 촬영 또는 갤러리를 시작합니다.
3. ML Kit가 보정한 이미지를 앱 전용 저장소에 보존하고 순서를 확인합니다.
4. 필요한 경우 같은 영수증 구간을 더 추가합니다.
5. OCR을 실행하고 필드와 각 행을 원본/bounding box와 대조합니다. 확인이 필요한 행만 걸러 보거나, 놓친 행을 추가하고 잘못 인식된 행을 삭제할 수 있습니다.
6. 필요하면 `Gemini 교정 제안` 화면에서 후보를 요청한 뒤, 근거를 보고 필요한 후보만 개별 적용합니다. 이 단계는 선택 사항입니다.
7. 합계와 필수 조건을 확인합니다. 차액이 남으면 제시된 원인 후보를 원본과 대조해 적용하거나, 불일치가 의도된 경우 구체적인 검수 사유를 기록합니다.
8. 초안 JSON은 언제든 앱 내부에 보존할 수 있습니다.
9. 모든 차단 조건을 통과한 경우에만 `user_verified`로 확정하고 로컬 저장/공유합니다.

## 프로젝트 구조

```text
app/                 Compose 앱, 워크플로 상태, Gemini/Fitness HTTPS adapter·Keystore, FileProvider 공유
receipt-scanner/     재사용 Android Library
  capture/           스캔 경계와 ML Kit Android adapter
  ocr/               모든 워크플로가 공유하는 OCR interface와 순수 DTO
  workflow/          세션 워크플로 식별자
  parser/            범용 규칙 파서와 ParserProfile
  nutrition/         Fitness 영양 라벨 파서, 검증, 로컬/서버 JSON 계약
  domain/            receipt.v2 모델, 검증, 평가 계산
  storage/           Room metadata와 앱 전용 파일
  review/            사용자 수정 이력과 검수 상태
  correction/        vendor-neutral AI 제안 계약, prompt, 검증 정책
  export/            strict/canonical receipt.v2, manifest, private OCR debug
  publisher/         LocalOnly 구현과 향후 서버 경계
docs/                평가·기기 검증·개인정보·연결 설계
examples/            합성 receipt.v2 예제
```

ML Kit, Room entity, Android URI는 `receipt.v2` 도메인 모델이나 `ReceiptPublisher` 계약에 포함되지 않습니다. Android의 `Activity`/`IntentSender`는 문서 스캐너 adapter 진입부에만 남습니다.

## 빌드

필요 조건:

- Android Studio 또는 JDK 17 이상
- Android SDK Platform 37
- Windows에서는 저장소의 `gradlew.bat` 사용

```powershell
.\gradlew.bat test --no-daemon
.\gradlew.bat lint --no-daemon
.\gradlew.bat assembleDebug --no-daemon
.\gradlew.bat assembleDebugAndroidTest --no-daemon
```

생성된 APK:

```text
app/build/outputs/apk/debug/app-debug.apk
```

### 로컬 `.env` 빌드 기본값

저장소 루트의 `.env`가 있으면 `assembleDebug`/`assembleRelease` 설정 단계에서 다음 값만 읽어 앱 기본값으로 연결합니다.

- `NUTRITION_SUPABASE_URL`
- `NUTRITION_SUPABASE_ANON_KEY` (publishable/anon key만 허용)
- `GEMINI_MODEL`

URL과 publishable/anon key는 앱의 Fitness 연결 카드에 자동으로 표시되므로 별도 재입력이 필요 없습니다. `GEMINI_API_KEY`, `EMAIL`, `PASSWORD`는 APK·`BuildConfig`에 넣지 않습니다. 이 값들은 APK에서 추출 가능한 비밀이므로 Gemini API 키는 홈의 `API 설정`에 저장하고, Fitness 계정은 같은 화면에서 로그인합니다. `.env`와 `.env.*`는 Git에서 무시됩니다.

`.env`가 없거나 안전한 Nutrition 쌍이 없으면 기존의 빈 설정으로 빌드하며, 잘못된 URL·service-role/secret key는 빌드 단계에서 거부합니다. 빌드 로그에는 비밀값을 출력하지 않습니다.

Gemini는 선택 기능입니다. API 키 없이도 기존 로컬 OCR 앱은 동작하며 AI 요청만 비활성화됩니다. 키를 소스나 Gradle에 넣지 말고 [Gemini 교정 제안 설정](docs/GEMINI_CORRECTION_SETUP.md)의 앱 내 저장 절차와 개인 사용 경계를 따릅니다.

실제 기기 또는 에뮬레이터가 연결된 환경에서 UI 테스트를 실행하려면:

```powershell
.\gradlew.bat connectedDebugAndroidTest --no-daemon
```

## 주요 버전과 선택 이유

| 구성요소 | 버전/설정 | 역할과 비용 |
| --- | --- | --- |
| compileSdk / targetSdk / minSdk | 37 / 37 / 24 | Android 17 API 및 target 동작을 선택했지만 실제 Android 17 기기 검증은 남음 |
| Android Gradle Plugin / Gradle | 9.3.1 / 9.6.1 | API 37 지원 조합, AGP 9 built-in Kotlin, 검증된 wrapper checksum 사용 |
| Kotlin / Compose BOM | 2.4.10 / 2026.06.01 | Compose compiler plugin과 UI 버전 정렬 |
| Activity / Lifecycle | 1.13.0 / 2.11.0 | Activity Result와 lifecycle-aware Flow 수집 |
| Fragment | 1.8.9 | ML Kit transitive 버전을 Activity Result 호환 stable release로 정렬 |
| Room / KSP | 2.8.4 / 2.3.9 | 공통 metadata 보존; schema v4와 migration 유지 비용 발생 |
| ML Kit Document Scanner | 16.0.0 | Play Services 기반 보정 UI; 지원 기기와 최초 구성요소 다운로드에 의존 |
| ML Kit Korean Text Recognition | 16.0.1 | 한국어·영문·숫자 OCR; 앱 크기와 처리 메모리 증가 |
| Gemini transport | Interactions API v1beta / platform HTTPS | Firebase SDK 없이 직접 호출; 키를 기기에서 관리하고 공개 배포 시 backend 전환 필요 |
| Gemini model | `.env`의 `GEMINI_MODEL` 또는 `gemini-3.5-flash-lite` | 기존 OCR 상품 행의 구조화 교정 후보만 생성; 사용자 승인 전에는 초안을 바꾸지 않음 |
| Kotlin Coroutines / Serialization | 1.11.0 / 1.11.0 | Flow·비동기 처리와 strict `receipt.v2` codec; cancellation/codec 유지 비용 |

선정 근거는 [의존성 기록](docs/DEPENDENCIES.md)에 공식 문서 링크와 함께 정리했습니다.

## 문서

- [정확도 평가 방법](docs/ACCURACY_EVALUATION.md)
- [실제 기기 테스트 체크리스트](docs/DEVICE_TEST_CHECKLIST.md)
- [개인정보 및 보안 경계](docs/PRIVACY_BOUNDARIES.md)
- [Gemini 교정 제안 설정](docs/GEMINI_CORRECTION_SETUP.md)
- [Fitness 영양성분 검수·DB 저장](docs/FITNESS_NUTRITION_WORKFLOW.md)
- [PriceTrace Capacitor 연결 설계](docs/PRICETRACE_CAPACITOR_INTEGRATION.md)
- [알려진 제한사항](docs/KNOWN_LIMITATIONS.md)
- [합성 receipt.v2 예제](examples/receipt.v2.example.json)

## 완료의 의미

저장소 수준에서는 공통 촬영→OCR 뒤 PriceTrace 또는 Fitness로 분기하는 상태 흐름, 선택적 Gemini 교정, Fitness 사용자 인증·private DB 저장 adapter와 APK 패키징이 구현되어 있습니다. 실제 완료를 입증하려면 `0.2.0` APK에서 두 탭의 촬영·복원·검수, 실제 Gemini 호출, 실제 Nutrition 프로젝트 로그인·RLS·insert/update를 각각 실행해야 합니다. 로컬 테스트만으로 실기기 정확도, 원격 migration 적용, AI 개선 폭 또는 운영 DB 저장 성공을 주장하지 않습니다.
