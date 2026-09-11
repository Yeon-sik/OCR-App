# yeonsik-ocr.v4 구매 evidence

이 문서는 `yeonsik-ocr.v4`의 `purchase` mode와 PriceTrace/CashOS V4 연결 경계를
기록한다. V4는 기존 `yeonsik-ocr.v1`~`v3` codec을 대체하지 않으며, 별도 strict
codec으로만 읽고 쓴다.

## 확인한 외부 contract

2026-09-11 현재 이 OCR 저장소의 `feat/yeonsik-ocr-v4-purchase-evidence`는
`main` `348f478`에서 시작했다. 다음 두 로컬 작업 트리의 실제 source/migration을
대조해 adapter를 작성했다.

| 대상 | 확인한 contract | OCR App 호출 경로 |
| --- | --- | --- |
| PriceTrace Product Candidate | `submit_product_candidate_v1(p_idempotency_key text, p_candidate jsonb)`; `PRICETRACE_PRODUCT_CANDIDATE` / `product-candidate.v1` | `/rest/v1/rpc/submit_product_candidate_v1` |
| PriceTrace purchase | `ingest_verified_purchase_price_observation_v1(p_idempotency_key text, p_purchase jsonb)`; `purchase-price-observation.v4` / `purchase-price.v4` | `/rest/v1/rpc/ingest_verified_purchase_price_observation_v1` |
| CashOS | `finance_ingest_transaction_v4`의 최신 flat `p_*` 인자; `cashos.transaction-ingest.v4`; nullable `ledger_entry_id`, `posting_state`, `posting_date_source` 응답 | `/rest/v1/rpc/finance_ingest_transaction_v4` |

대조 원본은 PriceTrace `C:\Github\창팡맨\docs\contracts\PURCHASE_PRICE_OBSERVATION_V4.md`
및 `supabase/migrations/20260911140000_purchase_price_observation_v4.sql`, CashOS
`C:\Github\personal-os\CashOS\src\shared\contracts\transaction-ingest-v4.ts`
및 `src/client/lib/api/transactions.ts`다. 이 파일들의 local 존재는 원격 migration
적용이나 live RPC/RLS 성공을 증명하지 않는다.

PriceTrace purchase payload는 중첩된 `platform`, nullable `seller`, `order`,
`payment`, `items` shape을 그대로 사용한다. CashOS payload는 다음 flat 인자를
그대로 사용한다.

`p_contract_version`, `p_idempotency_key`, `p_document_id`, `p_transaction_revision`,
`p_revision_seq`, `p_transaction_fingerprint`, `p_platform`, `p_seller`,
`p_source_type`, `p_transaction_status`, `p_payment_status`, `p_order_reference`,
`p_payment_reference`, `p_ordered_at`, `p_paid_at`, `p_timestamp_provenance`,
`p_ordered_local_date`, `p_paid_local_date`, `p_grand_total_amount_krw`,
`p_gross_amount_krw`, `p_discount_amount_krw`, `p_fee_amount_krw`,
`p_payment_method_hint`, `p_account_hint`, `p_institution_hint`, `p_category_hint`,
`p_category_id`, `p_account_id`, `p_price_trace_store_id`, `p_items`.

CashOS `p_items`에는 line-level `seller`, `seller_reference`,
`source_item_reference`를 보존한다. `posting_state`가 `CONFIRMED_EXPENSE`일 때만
일반 지출 원장이 만들어지고, 취소·환불·대기·주문만 있는 상태는 원장 지출로
승격되지 않는다. `gross`/`discount`가 null이어도 관측된 shipping/fee는 산식으로
검증 가능한 경우 보존한다.

OCR App은 두 authority의 UUID·server identity를 V4 입력에서 만들거나 복사하지
않는다. PriceTrace의 retail line에는 opaque `product_client_key`를 wire의
`product.client_key`로 보낸다. `merchant_sku`는 별도 관측 사실이며 client key로
채우지 않는다. CashOS에는 아직 PriceTrace 응답 identity를 임의로 넣지 않는다.

Product Candidate를 order history에서 식별할 수 있도록 OCR App의 V4 evidence
allowlist에는 `order_history`를 포함한다. 다만 현재 확인한 PriceTrace의 기존
`submit_product_candidate_v1` migration allowlist에는 이 source type이 아직
배포되어 있지 않으므로, live 연동 전 PriceTrace authority migration/RLS를
동일 contract로 적용해야 한다.

## canonical model

`purchase_records`의 각 record는 다음을 보존한다.

- `platform`과 nullable `seller` (둘은 대소문자 무시 비교로 동일할 수 없다)
- 명시적 source fact인 `purchase_kind` (`retail`, `restaurant`, `other`, `unknown`)
- `ordered_on`/`ordered_at`, `paid_on`/`paid_at` (서로 독립적인 nullable 사실)
- `status`, KRW `totals` (`subtotal`, `discount`, `shipping`, `tax`, `grand`, `paid`)
- `payment` (`method`, `provider`, `status`)
- line item의 `product_client_key`, seller override, option, quantity, nullable price facts
- field-level `evidence`와 `confidence`

`purchase_kind`는 상품 key나 line 형태로 추론하지 않는다. line이 없는 record는
호환성상 payment-only로 취급하지만, retail/restaurant/other/unknown의 canonical
분류는 명시된 source fact를 사용한다. seller가 없더라도 payment-only CashOS
transaction은 가능하다. 날짜나 시각을 사진 촬영·주문 화면 생성 시각·결제 시각에서
자동 대입하지 않는다. `ordered_at`/`paid_at`만 있으면 해당 timestamp의 local date를
routing용으로 파생하되 원본 timestamp는 보존한다. 모르는 금액·할인·단가는 `null`로
남긴다.

사진 evidence는 `order_history` 또는 `payment_history` source attachment를 참조하고,
자유 텍스트는 `user_statement` evidence와 `source.user_text`로 참조한다. 두 출처가
동일 field에서 다른 값을 말하면 우선순위를 정하거나 자동 선택하지 않고
`review.status=conflict`와 `purchase_evidence_conflict:<client_key>:<field>`로
검수를 차단한다.

## routing

| 조건 | projection | 동작 |
| --- | --- | --- |
| line-item purchase + ordered/paid date | `pricetrace_price_observation` | 먼저 필요하면 Product Candidate를 저장한 뒤 PriceTrace purchase V4 source를 저장한다. 실제 observation은 명시적 retail/restaurant, 결제/상태, seller, product, date, price 조건이 모두 충족될 때만 생성된다 |
| 결제 확정 + grand/paid total + ordered 또는 paid date | `cashos_transaction` | CashOS transaction ingest V4의 일반 `EXPENSE`는 실제 `paid` 확인이 있을 때만 보낸다. payment-only도 허용하지만 ordered/pending/cancelled/refunded/unknown은 보내지 않는다 |
| purchase record | Fitness nutrition/meal | 항상 disabled; purchase만으로 consumption/Meal을 만들지 않는다 |
| 같은 receipt 거래 | receipt / legacy `price_observations` | V4 purchase envelope에서 함께 표현하지 않으며 중복 생성하지 않는다 |

retail line의 `product_client_key`가 같은 V4 `product_candidates`를 참조하면
`PRICETRACE_PRODUCT_CANDIDATE`가 `PRICETRACE_PRICE_OBSERVATION`의 dependency가
되어 순서대로 제출된다. 여러 seller override는 PriceTrace line-level seller로
표현하고, CashOS는 하나의 transaction으로 유지할 수 있다. 각 record는 PriceTrace와
CashOS에 독립된 idempotency/fingerprint를 사용한다. 서버가 반환한 id만 projection
metadata로 보존한다. PriceTrace metadata/UI는 source 저장 성공과 실제 observation
생성을 각각 표시한다.

## codec와 validator

- V1~V3 JSON은 기존 codec으로 계속 읽고 쓴다. V1~V3 decoder는 purchase field를 읽지
  않는다.
- V4 strict root는 `schema_version=yeonsik-ocr.v4`, `mode=purchase`,
  `purchase_records`를 요구하고 `product_candidates`는 빈 배열 또는 order-history
  evidence를 가진 후보를 허용한다. receipt, legacy `price_observations`, nutrition,
  consumption, links와 혼합하지 않는다.
- V4 history attachment source type은 `order_history`, `payment_history`다. 자유
  텍스트 source fact는 `user_statement` evidence와 `source.user_text`로 보존한다.
  history evidence의 attachment ID는 선언된 동일 type source file을 가리켜야 한다.
- Product Candidate에 `product_photo`를 기계적으로 요구하지 않는다. candidate가
  참조한 source evidence가 readable인지 검사하며, 사진과 자유 텍스트의 동일 field
  충돌은 자동 선택하지 않고 review conflict로 남긴다.
- V4 canonical purchase line 상한은 PriceTrace source-line 상한과 맞춘 최대 100개다.
  CashOS 최신 request schema의 200개 cap보다 더 보수적인 canonical cap을 사용해
  양 downstream에 같은 line 집합을 보낸다.
- 외부 producer의 `review.status`/`user_verified`는 authority가 아니다. Android와
  Desktop에서 explicit confirm 후 Core가 `MANUAL_CANONICAL_REVIEW` 또는
  `SOURCE_EVIDENCE`로 확정한다.
- Desktop은 source file logical ID와 저장된 파일 ID를 분리해 보존하며, V4 attach 시
  요청된 history type에 맞는 source file만 연결한다. Android/Core는 자유 텍스트의
  text-only purchase를 별도 예외 없이 gate에서 허용한다.

## 검증 경계

2026-09-11 이 branch에서 실행한 local 검증:

```powershell
.\gradlew.bat :core:test :app:testDebugUnitTest :desktop-app:test :app:compileDebugAndroidTestKotlin
.\gradlew.bat :app:connectedDebugAndroidTest
```

첫 command의 Core 전체 test, App 전체 unit test, Desktop 전체 test와 Android
instrumentation compile은 통과했다. 마지막 Android command는 APK와 instrumented
test compile까지 수행했지만 현재 `adb devices`에 연결된 device가 없어
`No connected devices!`로 실행되지 않았다. 따라서 Android physical/emulator E2E는
미검증으로 남긴다. Desktop V4 photo/text flow와 Core/App synthetic flow는 테스트에
포함되어 있다.

실제 Supabase RPC, Auth session, RLS, 원격 migration 적용, PriceTrace authority
resolution, CashOS category/account resolution은 이 branch에서 실행하지 않았다.
특히 PriceTrace Product Candidate authority가 `order_history`를 허용하도록 migration을
적용한 뒤, authenticated RPC/RLS와 실제 candidate→observation→CashOS sequence를
live에서 확인해야 한다. 그 결과를 성공했다고 표시하지 않는다.

## 관련 코드와 합성 예제

- `core/src/main/kotlin`가 아니라 `core/src/main/java` 아래의 `ingestion` model/codec/
  planner/orchestrator와 `publisher/PurchaseEvidenceV4Models.kt`
- `core/src/main/java/com/pricetrace/receiptocr/pricetrace/PriceTraceCanonicalGateway.kt`
  및 `CashOsReceiptGateway.kt`
- `core/src/main/java/com/pricetrace/receiptocr/pricetrace/*CanonicalProjectionSubmitter.kt`
- `app/src/main/java/com/pricetrace/receiptocr/AndroidCanonicalJsonValidator.kt`
- `desktop-app/src/main/kotlin/com/yeonsik/ingestion/desktop/DesktopIngestionController.kt`
- `examples/yeonsik-ocr.v4.purchase.example.json`
- `examples/yeonsik-ocr.v4.purchase.text-only.example.json`

예제는 실제 주문번호·계정·token·원본 이미지를 포함하지 않는 synthetic fixture다.
