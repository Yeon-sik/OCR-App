# Structured purchase options와 V2 mode diagnostic 완료 보고

검사일: 2026-10-11. 구현 기준은 이 작업 시작 시 실제 checkout HEAD
`7d78ec3425f5aa886e1202d2aa909086818f52dc` (`feat/preserve-restaurant-branch`)다.
local main은 `da6423fb2ff920af114f600848c9e095e438ad18`, origin/main은
`d8e64f8af433b27234d86e86faf8766133f109b1`로 확인했으며, 현재 HEAD의 구현과
테스트를 기준으로 작업했다. 아래 SHA는 검사 대상 기록이며 과거 문서의 SHA를
현재 구현이나 배포 상태의 근거로 사용하지 않았다.

## 1. Root cause A/B

A: V4/V5 구매 행은 단일 `option_text`만 보존했고, typed scalar registry만으로는
여러 옵션의 목록 구조를 추가·삭제·재정렬할 수 없었다. 또한 수신기용 500자 제한이
canonical domain에 있어 길이가 긴 원본 옵션 사실까지 거부했다.

B: V2 merchant/packaged_product의 여러 불변식을 message 없는 compound
`require`로 검사해 `Failed requirement.`만 노출했다. text-backed 상품을 잘못된
merchant mode로 보내면 mode 검사 전에 artifact decode가 실패할 수도 있었다.

## 2. Changed files

이 작업의 변경 파일은 28개다. 작업 시작 전에 존재했던 Android manifest/launcher
icon 변경과 `design/app-icon` 작업은 이 목록에 포함하지 않으며 수정하지 않았다.

| 파일 (repository root 기준) | 변경 |
| --- | --- |
| `core/src/main/java/com/pricetrace/receiptscanner/ingestion/IngestionModels.kt` | typed PurchaseOption, parent validation, PT compatibility reason |
| `core/src/main/java/com/pricetrace/receiptscanner/ingestion/PriceTracePurchaseOptions.kt` | deterministic flatten과 sink representability 검사 (신규) |
| `core/src/main/java/com/pricetrace/receiptscanner/ingestion/YeonsikOcrV4Json.kt` | strict option_items codec; V5 재사용 |
| `core/src/main/java/com/pricetrace/receiptscanner/ingestion/YeonsikOcrV2Json.kt` | mode별 actionable diagnostics |
| `core/src/main/java/com/pricetrace/receiptscanner/publisher/PurchaseEvidenceV4Models.kt` | PT option_text adapter |
| `core/src/main/java/com/pricetrace/receiptscanner/review/PurchaseOptionListMutation.kt` | Add/Delete/Reorder 닫힌 명령 (신규) |
| `core/src/main/java/com/pricetrace/receiptscanner/review/CanonicalFieldRegistry.kt` | 옵션 scalar allow-list |
| `core/src/main/java/com/pricetrace/receiptscanner/review/CanonicalReviewController.kt` | 목록 mutation, stable key/audit, invalid draft 오류 유지 |
| `core/src/main/java/com/pricetrace/receiptscanner/review/ReviewViewModel.kt` | parent별 ordered option 표시 |
| `app/src/main/java/com/pricetrace/receiptocr/CanonicalPurchaseOptionEditor.kt` | Android parent별 옵션 controls (신규) |
| `app/src/main/java/com/pricetrace/receiptocr/ReceiptScreens.kt` | editor 연결과 confirm/submit gate |
| `app/src/main/java/com/pricetrace/receiptocr/ReceiptAppViewModel.kt` | typed list callback |
| `app/src/main/java/com/pricetrace/receiptocr/ReceiptOcrApp.kt` | callback wiring |
| `app/src/main/java/com/pricetrace/receiptocr/AndroidCanonicalJsonValidator.kt` | invalid draft confirm/submit 차단과 오류 유지 |
| `desktop-app/src/main/kotlin/com/yeonsik/ingestion/desktop/CollectorReview.kt` | Desktop parent별 옵션 controls와 action gate |
| `desktop-app/src/main/kotlin/com/yeonsik/ingestion/desktop/DesktopIngestionController.kt` | invalid draft verify/submit 차단과 오류 유지 |
| `core/src/test/java/com/pricetrace/receiptscanner/ingestion/StructuredPurchaseOptionsTest.kt` | 옵션 domain/codec/controller/PT/revision 회귀 (신규) |
| `core/src/test/java/com/pricetrace/receiptscanner/ingestion/YeonsikOcrV2ModeDiagnosticTest.kt` | V2 invariants/import 회귀 (신규) |
| `app/src/test/java/com/pricetrace/receiptocr/AndroidCanonicalJsonValidatorTest.kt` | JSON/bundle diagnostics와 원본 보존/invalid review |
| `desktop-app/src/test/kotlin/com/yeonsik/ingestion/desktop/DesktopIngestionRegressionTest.kt` | JSON/bundle diagnostics와 저장/복원/invalid review |
| `desktop-app/src/test/kotlin/com/yeonsik/ingestion/desktop/DesktopWorkspaceUiTest.kt` | 실제 Compose 버튼 add/edit/reorder/delete |
| `docs/YEONSIK_OCR_V4_PURCHASE_EVIDENCE.md` | 옵션 계약과 PT 경계 |
| `docs/YEONSIK_OCR_V5_RESTAURANT_PURCHASE.md` | parent link/영양/섭취 분리 |
| `docs/YEONSIK_OCR_INGESTION_V2.md` | mode 계약과 bundle 책임 |
| `examples/yeonsik-ocr.v4.purchase.example.json` | 명시적 빈 option_items |
| `examples/yeonsik-ocr.v5.restaurant-purchase.example.json` | 명시적 빈 option_items |
| `examples/yeonsik-ocr.v5.restaurant-purchase-options.example.json` | 세 옵션 전체 예제 (신규) |
| `docs/validation/2026-10-11-options-mode-validation.md` | 이 보고서 (신규) |

## 3. Final option canonical model

`PurchaseRecordLine.optionItems: List<PurchaseOption> = emptyList()`를 추가했다.
`PurchaseOption`은 `optionKey: String`, `description: String`,
`quantity: Double? = null`, `amountKrw: Long? = null`의 typed model이다.

```json
{
  "option_text": null,
  "option_items": [
    {"option_key": "option-1", "description": "보통", "quantity": 1, "amount_krw": 0},
    {"option_key": "option-2", "description": "현미잡곡밥 180g (약275kcal → 순탄수 61g)", "quantity": 1, "amount_krw": 0},
    {"option_key": "option-3", "description": "리뷰이벤트 계란후라이 1개 추가 (순단백질7g)", "quantity": 1, "amount_krw": 0}
  ]
}
```

예제의 1/0은 관측값을 명시한 fixture다. 누락값은 각각 null로 유지한다. key와
description은 non-blank, key는 parent 내부 unique, quantity는 null 또는 양의 유한
수, amount는 null 또는 0 이상 정수다. non-empty options는 `option_text=null`을
요구한다. 배열 순서와 parent 종속성을 유지하며 별도 purchase/menu 행을 만들지 않는다.
schema version은 기존 V2/V4/V5를 유지한다. 이번 변경은 option/root에 user_verified,
authority UUID나 Evidence DB identity를 추가하지 않는다.

## 4. Manual review add/edit/delete/reorder

Android와 Desktop에서 구매 행별 새 옵션 설명/추가, 기존 설명·수량·금액의 typed
편집과 null 처리, 삭제, 위/아래 이동을 제공한다. 구조 변경은
`PurchaseOptionListMutation.Add/Delete/Reorder`만 받는다. scalar 편집은 기존
allow-list registry를 유지하며 `option_key`와 evidence/source는 editable field가 아니다.

추가 시 parent 내부 `option-N` local key를 생성한다. 기존 옵션과 목록 audit의
previous/new snapshots에 나타난 key를 예약해 삭제·undo/redo·controller 재생성 후에도
다른 옵션에 재사용하지 않는다. line key/index alias는 같은 canonical audit path로
정규화한다. 목록 변경은 ordered JSON snapshots와 `user_modified` provenance로 기록한다.

모든 실제 mutation은 full canonical validation을 통과해야 commit된다. 잘못된 값은
canonical/audit/revision에 저장하지 않는다. 여러 필드 오류는 함께 유지하고, 다른
정상 편집이나 reorder로 지우지 않는다. 같은 값을 고치거나 해당 옵션을 삭제하면
그 오류만 해소한다. UI와 controller 양쪽에서 confirm/verify/submit을 차단한다.

원본 attachment bytes, manifest binding/hash, source/user_text와 purchase evidence는
변경하지 않는다. 수동 추가는 producer evidence를 만들지 않는다. V5 nutrition links는
기존 `purchase_record_client_key + purchase_line_key + nutrition_client_key`를 유지한다.
옵션의 영양처럼 보이는 문자열을 provenance나 영양 수치로 승격하지 않으며, nutrition과
consumption을 생성·재계산·수정하지 않는다.

## 5. Legacy option compatibility

`option_items` 누락은 `[]`로 decode한다. 새 encoder는 항상 배열을 출력한다.
`option_text`만 있는 canonical과 기존 fixture는 계속 valid하다. explicit null이나
잘못된 option_items shape, unexpected authority field와 잘못된 값은 거부한다.
V5는 V4 purchase model/codec을 재사용하며 중복 구현하지 않았다.

legacy 텍스트를 자동 파싱·대체하지 않는다. 사용자가 legacy 옵션 텍스트를 지운 뒤에만
구조화 옵션을 추가할 수 있다. canonical 옵션 텍스트의 기존 500자 domain 제한은
수신기 compatibility 검사로 옮겨 전체 원문을 보존한다.

## 6. PT flatten/fail-closed

실제 로컬 PriceTrace HEAD `64473abe6c2203678d082435303b6578154730b2`의
`supabase/migrations/20260911140000_purchase_price_observation_v4.sql`과 후속 migration을
확인했다. 검사한 receiver 계약은 single `option_text`, PostgreSQL character length
500자이며 `option_items`를 받지 않는다.

OCR-App adapter가 source order로 `설명 ×수량 [금액 KRW]`를 ` | `로 연결한다.
quantity는 decimal plain text, null 수량/금액 suffix는 생략하고 explicit 0은 유지한다.
legacy optionText는 공백까지 그대로 전달한다. structured options는 canonical에 남는다.

500 Unicode code points 초과는
`pricetrace_v4_option_text_max_500_characters_required`, NUL/단독 surrogate는
`pricetrace_v4_option_text_unrepresentable`로 해당 PT record를 제출 불가로 판정한다.
payload 생성도 이를 거부한다. truncate/round/replace하지 않는다. 호환 가능한 sibling
record와 CashOS/Fitness 계획 및 canonical 사실은 유지한다.

## 7. V2 mode validation behavior

MERCHANT는 non-null candidate, non-food-service receipt, 빈 product_candidates/
nutrition/consumption/links라는 기존 계약을 그대로 유지한다. 잘못된 mode의 상품
artifact는 decode 초기에 검사해 text-backed 상품도 mode 충돌을 직접 설명한다.

PACKAGED_PRODUCT는 top-level candidate=null, null 또는 non-food-service receipt,
candidate 또는 ProductLabel nutrition 하나 이상, ProductLabel만 있는 nutrition,
빈 links라는 계약을 유지한다. `receipt.merchant`는 삭제하지 않는다. restaurant와
legacy fixture의 기존 의미도 유지한다.

invalid producer JSON/bundle은 거부한다. mode 변경, candidate 삭제, manifest hash
자동 보정은 하지 않는다. producer가 canonical bytes를 수정하면 최종 bytes에 대한
`manifest.canonical_sha256`을 다시 계산해야 한다. Bundle Spec은 변경하지 않았다.

## 8. Exact diagnostics / UI propagation

```text
V2 merchant mode requires merchant_candidate.
V2 merchant mode requires product_candidates=[]; packaged product artifacts require mode=packaged_product.
V2 merchant mode requires nutrition=[]; product_label nutrition requires mode=packaged_product.
V2 merchant mode requires consumption=[].
V2 merchant mode requires links=[].
V2 merchant mode does not allow a food_service receipt; use restaurant mode.
V2 packaged_product requires merchant_candidate=null; receipt.merchant may remain.
V2 packaged_product does not allow a food_service receipt.
V2 packaged_product requires a product candidate or product_label nutrition.
V2 packaged_product nutrition must contain only product_label artifacts.
V2 packaged_product requires links=[].
```

Desktop의 JSON/bundle 사용자 error text와 Android의 JSON/bundle error/detail이 core
diagnostic을 포함한다. 기존 detail 전달 경로를 유지하고 regression test를 추가했다.
mode mismatch가 generic `Failed requirement.`로 대체되지 않으며 invalid import에서
수리된 envelope나 ingestion session을 만들지 않는다.

## 9. Revision/fingerprint

기존 orchestrator의 purchase dependency는 `YeonsikOcrV4Json.encodePurchaseRecord(record)`
전체 JSON이다. 새 배열과 배열 순서가 artifact/canonical/projection payload fingerprint에
포함된다. add/edit/delete/reorder 모두 canonical revision을 만들며 이전 확인을 무효화한다.

PT와 CashOS의 영향을 받는 projection revision이 증가하고 idempotency key, remote ID,
metadata/checkpoint를 초기화한다. 재확인 후에는 새 idempotency key와 비어 있는 이전
checkpoint로 제출한다. 기존 fingerprint/reset 코드를 그대로 사용하며, 네 가지 작업을
각각 제출 완료 상태에서 실행한 회귀 테스트로 확인한다.

## 10. Tests and commands/results

최종 변경 상태에서 아래 명령이 모두 성공했다.

```powershell
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-17'
$env:ANDROID_HOME = 'C:\Users\nwhck\AppData\Local\Android\Sdk'
.\gradlew.bat :core:test :app:test :desktop-app:test :core:check :desktop-app:check :app:lintDebug :app:compileDebugAndroidTestKotlin --console=plain --continue
& 'C:\Users\nwhck\.cache\codex-runtimes\codex-primary-runtime\dependencies\node\bin\node.exe' .github/project-docs/validate-project-docs.mjs --config project-docs.config.json --require-tracked
git diff --check
```

| 검사 | 결과 |
| --- | --- |
| Core test XML | 288 tests, 288 통과, failures/errors/skipped 모두 0 |
| Android app test XML | 133 tests, 133 통과, failures/errors/skipped 모두 0 |
| Desktop test XML | 47 tests, 45 통과, failures/errors 0, 기존 원격 테스트 2 skipped |
| 합계 | 466 통과, 실패/오류 0, skipped 2 |
| core:check / desktop-app:check | 성공 |
| app:lintDebug | 성공, 오류 0, 경고 26 |
| app:compileDebugAndroidTestKotlin | 성공 |
| Project docs validation | 오류 0, 경고 0 |
| git diff --check | 성공 (ReceiptOcrApp.kt의 LF→CRLF 설정 안내만 있음) |

새 회귀 테스트는 Core 31개(옵션 19, mode 12), Android 3개, Desktop 3개다.
Desktop에는 실제 Compose UI의 추가·편집·이동·삭제 버튼 조작 검증이 포함된다.
기존 bundle SHA/type/binding, 동일 bytes의 여러 logical binding와 seller branch 테스트도
전체 suite에서 통과했다. 재검증은 마지막 보완과 컴파일 수정 이후 수행했으며 성공한
동일 소스의 Core/App 결과는 Gradle의 up-to-date 검사로 재사용했다.

요청한 34개 검증 항목의 대응은 아래와 같다. 한 테스트가 여러 invariant를 함께 검증한다.

| 요구 번호 | 검증 위치/내용 |
| --- | --- |
| 1–5 | StructuredPurchaseOptionsTest: three options, ordered codec, missing field, legacy text, null legacy text |
| 6–9 | 동일 suite: duplicate/blank key/description, nullable/positive finite quantity, nullable/zero/integer/nonnegative amount |
| 10–11 | 동일 suite: add/edit/null/delete/reorder, stable keys, undo/redo/recreation, line alias; DesktopWorkspaceUiTest 버튼 조작 |
| 12 | Core invalid edits unchanged canonical/audit/fingerprint; Android confirm/submit, Desktop verify 차단; 다른 정상/no-op 편집 뒤에도 오류 유지 |
| 13 | Android bundle: exact attachment bytes/raw JSON/manifest/hash/source/evidence 보존; Core source/evidence 동등성 |
| 14–16 | V5 parent 3-key links/영양 provenance 유지, nutrition/consumption 동등성과 consumption=[] |
| 17–18 | PT payload deterministic flatten, source order/null suffix/observed zero, legacy pass-through |
| 19 | 500/501/emoji/NUL/단독 surrogate 경계, 원문 무손실, PT-only 차단과 CashOS/Fitness eligibility |
| 20 | 네 mutation 각각 canonical/projection revision/fingerprint, idempotency/remote/checkpoint 초기화와 재제출 |
| 21–23 | YeonsikOcrV2ModeDiagnosticTest: merchant product/label/consumption exact diagnostics |
| 24–25 | 동일 suite: non-food-service receipt + candidate (+label), decode/import 성공, receipt.merchant 보존 |
| 26–28 | 동일 suite: top-level candidate, food_service receipt, missing product/label exact diagnostics |
| 29–30 | merchant-only/receipt-only와 restaurant/food-photo 기존 fixture 통과 |
| 31 | DesktopIngestionRegressionTest JSON/bundle core diagnostic 전달 |
| 32 | AndroidCanonicalJsonValidatorTest JSON/bundle core diagnostic 전달 |
| 33 | 전체 core/app/desktop의 기존 bundle SHA/type/binding와 중복 bytes logical binding 회귀 |
| 34 | 전체 core/app/desktop의 기존 seller_branch_name 회귀 |

## 11. PT/Fitness/CashOS migrations

이번 작업에서는 세 downstream의 DB/schema/RPC migration을 추가하거나 적용하지 않았다.
PT의 기존 single-text 계약은 adapter flatten과 호환성 gate로 처리했다. 옵션은 Fitness
영양/섭취 payload의 입력이 아니며 CashOS의 기존 typed transaction wire contract도
변경하지 않는다. 원격 배포 상태를 로컬 receiver 검사로 추정하지 않았다.

## 12. Remaining limitations

Android 실제 기기/emulator에서 UI/E2E는 실행하지 않았으며 instrumentation 코드 컴파일과
unit test로 검증한다. 실 Supabase RPC/RLS/Storage와 downstream 원격 제출은 실행하지
않았다. Desktop의 기존 원격 checkpoint integration 테스트 두 개는 환경/명시적 owner
approval 조건이 없어 skipped다.

- `DesktopCheckpointRemoteIntegrationTest.savedLegacyCheckpointRecoversServerResolutionWithoutReingestion`: `YEONSIK_CHECKPOINT_INTEGRATION_RECORD` 필요.
- `DesktopApprovedCheckpointRecoveryTest.approvedExistingReviewResumesPublicationThroughOcrController`: explicit owner approval과 `YEONSIK_APPROVED_CHECKPOINT_ID` 필요.

PT 500자를 넘거나 안전하게 표현할 수 없는 원문은 보존되지만 해당 PT submission은
계속 차단된다. legacy 옵션을 구조화 배열로 자동 변환하지 않는다. 수동 검수는 source
evidence를 새로 만들지 않으므로 옵션 변경만으로 증거/영양/섭취 사실이 추가되지 않는다.
