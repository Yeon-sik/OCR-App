# 식당 구매 구성요소 · identity 검수 구현 보고서

2026-10-15 추가 최종화 및 현재 activation gate는 [후속 검증 보고서](validation/2026-10-15-restaurant-component-finalization.md)를 따른다.
아래 테스트 수치는 최초 feature 구현 시점의 기록이다. 실제 Project Source of Truth는 확인되지 않았고 Producer 후보는 비활성이다.

## 기준과 보존

- OCR-App 기준 main: `887948203a13917e3513269a9ed5fb6348fcc728`.
- PriceTrace 최종 기준 main: `d04cb5cc249b9211a0b72ec88a2821ee20ed8148`.
  최초 기준 `d85a72f44072fec3e517a2c717489b94c3a24b1e`의 #62를 조사했고,
  최종 fetch에서 #63 통합 main을 발견해 구조를 다시 조사하고 feature를 rebase했다.
  관측 UI, 판매처 후보 화면, 공개 홈/owner 구매 분리 및 새 SQL fixture를 보존했다.
- 두 저장소 branch: `feat/restaurant-component-identity-resolution`.
- 원래 checkout의 미커밋 아이콘·생성 자료는 유지했다. 모든 구현은 별도 작업 트리에서 했다.
- 원격 migration apply, production write, main push/merge는 수행하지 않았다.

## 1. 변경 파일과 재사용 구조

기존 `PurchaseNutritionIdentity`, owner restaurant choices, shared use case/orchestrator,
projection metadata/checkpoint, canonical revision/audit/undo를 확장했다.
식당/메뉴는 #62의 merchant candidate와 immutable source binding/fingerprint를 재사용한다.
Android/Desktop은 같은 core command/state/validator를 사용한다.

OCR-App 변경 목록:

```text
app/src/main/java/com/pricetrace/receiptocr/AndroidCanonicalJsonValidator.kt
app/src/main/java/com/pricetrace/receiptocr/ReceiptAppViewModel.kt
app/src/main/java/com/pricetrace/receiptocr/ReceiptOcrApp.kt
app/src/main/java/com/pricetrace/receiptocr/ReceiptScreens.kt
app/src/main/java/com/pricetrace/receiptocr/PurchaseIdentityCard.kt
app/src/test/java/com/pricetrace/receiptocr/pricetrace/PriceTraceCanonicalGatewayTest.kt
core/src/main/java/com/pricetrace/receiptocr/fitness/FitnessCanonicalProjectionSubmitter.kt
core/src/main/java/com/pricetrace/receiptocr/pricetrace/PriceTraceCanonicalGateway.kt
core/src/main/java/com/pricetrace/receiptscanner/ingestion/CanonicalIngestionUseCase.kt
core/src/main/java/com/pricetrace/receiptscanner/ingestion/IngestionEvidenceGate.kt
core/src/main/java/com/pricetrace/receiptscanner/ingestion/IngestionModels.kt
core/src/main/java/com/pricetrace/receiptscanner/ingestion/IngestionOrchestrator.kt
core/src/main/java/com/pricetrace/receiptscanner/ingestion/PurchaseNutritionIdentity.kt
core/src/main/java/com/pricetrace/receiptscanner/ingestion/PurchaseIdentityReview.kt
core/src/main/java/com/pricetrace/receiptscanner/ingestion/YeonsikOcrV5Json.kt
core/src/main/java/com/pricetrace/receiptscanner/review/CanonicalFieldRegistry.kt
core/src/main/java/com/pricetrace/receiptscanner/review/CanonicalReviewController.kt
core/src/main/java/com/pricetrace/receiptscanner/review/ReviewViewModel.kt
core/src/test/java/com/pricetrace/receiptscanner/ingestion/OwnerConfirmedPurchaseRecoveryTest.kt
core/src/test/java/com/pricetrace/receiptscanner/ingestion/RestaurantPurchaseComponentTest.kt
desktop-app/src/main/kotlin/com/yeonsik/ingestion/desktop/CollectorApp.kt
desktop-app/src/main/kotlin/com/yeonsik/ingestion/desktop/CollectorReview.kt
desktop-app/src/main/kotlin/com/yeonsik/ingestion/desktop/DesktopIngestionController.kt
desktop-app/src/main/kotlin/com/yeonsik/ingestion/desktop/PurchaseIdentityCard.kt
desktop-app/src/test/kotlin/com/yeonsik/ingestion/desktop/DesktopSessionStoreTest.kt
desktop-app/src/test/kotlin/com/yeonsik/ingestion/desktop/PurchaseIdentityReviewUiTest.kt
docs/YEONSIK_OCR_V5_RESTAURANT_PURCHASE.md
docs/PROJECT_V5_CONTRACT_PATCH.md
docs/RESTAURANT_COMPONENT_IDENTITY_IMPLEMENTATION.md
examples/yeonsik-ocr.v5.restaurant-purchase-components.example.json
```

PriceTrace 변경 목록:

```text
src/domain/owner-confirmed-restaurant.ts
src/repositories/owner-confirmed-restaurant.repository.ts
src/repositories/owner-confirmed-restaurant.repository.test.ts
src/app/OwnerConfirmedRestaurants.tsx
src/app/OwnerConfirmedRestaurants.module.css
e2e/owner-confirmed-restaurants.spec.ts
scripts/test-purchase-price-sql.mjs
supabase/migrations/20261014091055_restaurant_component_identity_resolution.sql
supabase/tests/restaurant_component_identity_resolution.sql
supabase/tests/purchase_public_identity_search.sql
docs/contracts/RESTAURANT_COMPONENT_IDENTITY_RESOLUTION.md
```

## 2. DB migration

추가한 forward migration은 `20261014091055_restaurant_component_identity_resolution.sql`
한 개다. 기존 migration은 수정하지 않았다.

| 추가 모델/API | 의미 |
| --- | --- |
| `purchase_owner_menu_components` | 기존 owner menu 또는 exact global parent 아래의 이름/역할. 가격 필드 없음 |
| `purchase_owner_menu_component_sources` | 실제 부모 구매 행, local nutrition key, canonical fingerprint, food-photo refs의 immutable binding |
| `purchase_owner_platforms` | owner private 플랫폼 이름/code |
| `purchase_owner_platform_sources` | 관측 platform 원문/code와 owner platform의 immutable source binding |
| `search_purchase_owner_identity_v1` | 저장된 본인 구매 행에서 식당/메뉴/플랫폼 검색. owner와 공개 verified 후보를 구별 |
| 기존 restaurant resolver의 새 overload | 기존 source lock/owner lock/candidate/reuse 경로에 menu 선택 및 review/create action 추가 |
| `resolve_purchase_global_identity_v1` | 사용자가 고른 공개 후보를 private owner mapping으로 연결. source의 exact authority를 발명하지 않음 |
| `resolve_purchase_owner_platform_v1` | 명시적 플랫폼 선택/생성, 기존 unambiguous owner alias 재사용 |
| `register_purchase_menu_components_v1` | 서버가 저장된 부모 menu를 확인하여 구성 관계만 등록. 한 호출 최대 100개 |
| `get_my_owner_confirmed_restaurants_v2` | 기존 private 식당/가격 이력에 구성요소 목록 추가 |

네 신규 테이블은 owner SELECT RLS와 immutable trigger를 적용하고 anon 조회/직접 쓰기를
허용하지 않는다. 외부 RPC는 `auth.uid()`와 source/identity owner를 확인하고 빈 search_path를
사용한다. internal helper의 PUBLIC/anon/authenticated 실행 권한은 회수했다.

2026-10-15 최종화: component evidence identity는 owner + purchase source + canonical fingerprint + nutrition key다.
별도 실제 purchase는 같은 fingerprint/local key를 사용해도 독립 evidence로 등록된다.
동일 source/revision/key에서 다른 사실/부모로 재전송하면 거부하며 새 local revision은 새 evidence history로 남긴다.
OCR은 저장된 구성요소를 읽고 누락된 항목만 100개씩 보낸다. 저장 후 응답을 잃은 batch도 재개한다.

식당/메뉴 alias용 중복 테이블은 추가하지 않았다. #62의 source bindings를 사용하며
explicit source pair/branch 충돌, serving/option 차이, rejected candidate는 합치지 않는다.
공백/단어 검색은 후보 제안이며 이름만으로 선택/공개 승격하지 않는다.

## 3. Canonical contract와 downstream

기존 V5 top-level 14개 key와 link의 필수 3개 key는 유지한다. 선택 key를 추가했다.

| Key | 값/기본값 |
| --- | --- |
| `component_role` | main, side, complimentary_side, sauce, beverage, other. 생략 시 main |
| `relation_type` | purchase_line, menu_component. 생략 시 purchase_line |

각 nutrition은 실제 존재하는 부모 record/line에 단일 relation을 가진다. 중복 nutrition,
dangling parent, 동일 행의 중복 main을 거부한다. 한 행에 main 하나와 menu_component 여러
개를 허용한다. 실제 유료 행의 직접 purchase_line relation은 최대 하나이며 다른 실제
유료 side는 자신의 행을 사용한다.

V2 `MealComponentEstimate`를 V5 complimentary_side에 재사용할 수 있다. 다른 역할은
RestaurantEstimate와 link role로 표현하며 검수에서 역할을 바꾸면 같은 영양/provenance를
보존한다. V5에는 legacy component identity reference를 허용하지 않는다.
필수 영양소는 food_image_estimate이고 모든 food evidence ref는 선언된 food_photo여야 한다.

Fitness는 각 음식/국물을 독립 private Nutrition으로 기존 food-estimate 경로에 전달한다.
component에는 부모 paid menu의 private link나 public publication identity를 붙이지 않는다.
명시적인 consumption user statement가 없으면 Meal을 생성하지 않는다.
PriceTrace는 실제 유료 행만 가격 observation으로 보존하고 무료 국물은 구성 관계로 남긴다.
CashOS는 원래 구매 transaction 경로를 계속 사용한다.

seller/menu/platform 원문, producer local keys, 사진 근거는 resolver 선택으로 바뀌지 않는다.
Restaurant/Location/Menu/Platform DB UUID와 선택/query는 기존 runtime metadata/checkpoint에만
저장한다. 공개 verified 후보를 수동 선택해도 owner_confirmed mapping으로 남기며 기존
source-pair exact/global authority와 public Nutrition publication은 별도로 유지한다.

Project 원본은 workspace에 없으므로 구현과 일치하는 [문서 패치](PROJECT_V5_CONTRACT_PATCH.md)를 준비했다.

- `00_YEONSIK_OCR_SCHEMA_ROUTER.md`: V5 main/구성요소 선택 조건 확장 필요.
- `06_YEONSIK_OCR_V5_RESTAURANT_PURCHASE_SPEC.md`: optional role/relation, cardinality,
  V2 component 재사용, provenance/runtime boundary 제한 변경 필요.
- `07_YEONSIK_OCR_V5_RESTAURANT_PURCHASE_EXAMPLES.md`: 메인 6,000원 + 국물의 완전한 JSON,
  실제 유료 side 및 식당/메뉴/플랫폼 검색 사례 추가 필요.
- `05_YEONSIK_BUNDLE_SPEC.md`: V5 지원 schema 및 order_history/payment_history/food_photo를 문구로 명시. ZIP/manifest/evidence archive/fingerprint transport 유지.

## 4. Android/Desktop UX

두 화면 모두 공통 core 모델에서 식당/메뉴/플랫폼의 관측값, 현재 연결, 검색/다시 검색,
후보 선택, 신규 등록, 보류를 제공한다. 같은 이름은 지점·source pair·serving 및 authority
표시로 구별한다. 여러 후보는 사용자가 고르며 새 등록은 변경되지 않은 검색의 빈 결과에서만
활성화한다. 저장되지 않은 source/검수되지 않은 revision에서는 mutation을 허용하지 않는다.

각 음식은 nutrition name, 실제 부모 행, 역할/관계, 연결 menu 상태, 영양소와 food-photo 근거를
표시하고 수정한다. 다른 음식이 점유한 직접 유료 행은 맞교환을 표시하고 두 연결을 함께
변경한다. validation, fingerprint, verification, projection plan/payload, audit와 undo를
같은 revision 경로로 재계산한다. resolver 검색/선택은 canonical revision을 변경하지 않는다.

PriceTrace private 식당 화면도 함께 제공된 국물을 `가격 미관측`으로 표시한다.
새 main의 어두운 테마에서 가격 이력의 고정 색상이 읽히지 않는 문제를 캡처에서 확인하여
private 화면의 글자/표/버튼 색상을 기존 테마 변수에 맞췄다.
Desktop Compose 화면과 PriceTrace desktop/mobile 화면을 캡처하여 확인했다.
Android는 빌드와 unit/lint로 검증했으며 실기기 화면은 검증하지 않았다.

## 5. 테스트 결과

| 검증 | 결과 |
| --- | --- |
| OCR core | 337 tests 통과 |
| OCR Android app | 142 tests 통과 |
| OCR Desktop | 56 tests 중 53 통과, 기존 remote smoke 3 skip |
| receipt-scanner | 176 tests 통과 |
| OCR 전체 | 711 tests 중 708 통과, 3 skip, 실패 없음 |
| OCR Android lint/assembleDebug 및 Desktop jar | 통과 |
| 최신 main 위 PriceTrace Vitest | 62 files / 573 tests 통과 |
| PriceTrace ESLint/typecheck | 통과 |
| PriceTrace production static build | 통과 (First Load JS 503 kB) |
| PriceTrace production export Playwright 전체 | 최초 36개 중 30 통과, 6 timeout. 9개 재실행은 5 통과, 4 timeout |
| PriceTrace 구성요소/owner/public/logout Playwright | 로딩 완료 대기 후 5개 모두 통과 (5.3분) |
| private 테마 대비 수정 후 desktop/mobile 가격·국물 표시 재검증 | 1개 통과 (41.7초), 새 캡처 확인 |
| 로컬 SQL | migration 102개, 기존 6개 + 신규 2개 fixture 통과 |

OCR 명령:

```text
gradlew.bat :core:test :app:testDebugUnitTest :desktop-app:test :receipt-scanner:testDebugUnitTest lint assembleDebug :desktop-app:jar --no-daemon
```

PriceTrace 명령:

```text
npm.cmd test -- --maxWorkers=2 --minWorkers=1
npm.cmd run lint
npx.cmd tsc --noEmit --incremental false
npm.cmd run build
node scripts/test-purchase-price-sql.mjs node_modules/@electric-sql/pglite
npx.cmd playwright test --config=test-results/identity.playwright.config.cjs --workers=1
npx.cmd playwright test e2e/owner-confirmed-restaurants.spec.ts --config=test-results/identity.playwright.config.cjs --workers=1 --trace on
```

브라우저는 synthetic API fixture로 빌드한 production export와 임시 loopback 정적 서버/config를
사용했다. 전체 test 예산은 90초이며 기존 5초 assertion과 데이터/권한 검증은 유지했다.
식당 테스트는 RPC 완료와 현재 로딩 표시가 사라지는 시점을 기다린 후 내용을 검사한다.
최초 실패 로그와 재실행 로그는 로컬 `test-results/identity-ui-initial`, `identity-ui-retry`에 보존했다.
모든 브라우저 테스트가 한 번에 통과했다는 의미는 아니다.

SQL fixtures는 요청 CASE A–H를 포함한다. 한성대/한성대학교 연결과 원문 유지, 다음 구매
alias 재사용, 공백이 다른 메뉴의 수동 선택, 6,000원 main+무료 국물, 실제 유료 side,
모호한 후보, 웨이티 platform alias, replay를 검증했다. 다른 owner source 조회 및 본인
source에서 foreign identity 선택, anonymous 권한, branch/source/serving 충돌, immutable
binding, 공개/private authority 분리도 검사했다.

OCR 회귀는 잘못된 evidence/dangling/중복 link, 부모 행 변경/맞교환/undo, 모든 component
role 편집, forged UUID/choice 거부, 보류, 파일 저장 후 재시작 metadata 복원, Fitness 독립
국물 import, component 응답 손실 및 205개 구성요소의 부분 batch 복구를 검사했다.
재개 시 구매 PT/Fitness/CashOS 중복 제출이 생기지 않는지 함께 확인했다.

## 6. Backward compatibility

- V1/V2/V3/V4 importer와 V2 receipt-context component 경로는 기존 회귀를 통과했다.
- 기존 V5 3-key link는 같은 JSON과 같은 dependency 문자열을 유지한다.
- 기존 bundle reader/manifest/attachment hash/verification 경계는 변경하지 않았다.
- 기존 restaurant resolver 4/5 인자 API 및 owner read V1은 유지한다.
  새 read V2가 없는 서버는 `PGRST202`일 때만 V1으로 fallback한다.
- #62 식당/메뉴/가격 이력과 source binding, private Fitness 구매 link, CashOS
  transaction replay/idempotency 및 structured option_items 회귀를 유지했다.
- 새 main의 공개 홈과 owner 구매 분리, 판매처 후보 및 카탈로그 구조를 보존했다.

## 7. 남은 위험과 검증 범위

SQL 검증은 로컬 PGlite의 역할/claims fixture다. 실제 서명된 HTTP JWT, 운영 데이터 규모,
원격 Supabase migration 적용, 실기기 설치 또는 실제 downstream 쓰기는 검증하지 않았다.
기존 SQL harness의 역사적 POSITION/CASE bootstrap 보정은 그대로 사용했으므로 수정 없는
빈 PostgreSQL 설치를 통과했다는 의미가 아니다. 새 migration 자체는 로컬 chain과 fixture를
통과했다. local/remote migration 이력 차이는 별도 배포 검토 대상이다.

Desktop remote smoke 3개는 기존 환경 의존 skip이다. 신규 resolver는 새 RPC가 필요하므로
migration 배포 전에 활성화하면 실패 응답을 받는다. 기존 private read는 V1 fallback을 유지한다.
alias는 owner 범위이며 지점/source/serving의 모호함을 해소한 명시적 사용자 선택이 필요하다.
구성요소 등록은 이미 서버에 저장되고 해소된 실제 부모 구매 행/menu를 요구한다.

확장한 전체 브라우저 회귀에서 기존 공개 식당 상세 URL 재진입과 표준 상품 영양 화면의
대기 시간 초과가 재실행에서도 남았다. 카탈로그 실패 두 건은 재실행에서 통과했다.
남은 항목은 `restaurant-menu.spec.ts`의 한결식당 heading 대기(15초)와
`shopping.spec.ts`의 하겐다즈 정보 보기 대기(5초)다. 해당 화면/테스트 source는 이번 feature에서
수정하지 않았다. 순수 main과 별도 실행 환경을 대조하지 않았으므로 원인을 확정하지 않았다.
이 결과를 전체 E2E 통과로 표기하지 않는다.

## 8. 권장 merge order

1. 최신 PriceTrace main 위 feature 코드·migration·SQL fixtures를 검토/병합한다.
2. 별도로 승인된 배포 작업에서 local/remote migration 이력과 기존 데이터 제약을 확인하고
   forward migration을 검증·적용한다. 이번 작업에서는 적용하지 않았다.
3. OCR feature를 병합하여 Android/Desktop 공통 검수와 새 resolver를 활성화한다.
4. OCR importer 배포와 실제 Project 원본 대조를 마친 후 00/05/06/07을 활성화한다. 05는 V5 지원 문구만 보완하고 transport 계약은 유지한다.

실제 commit/push는 feature branch까지만 수행한다. main은 직접 push/merge하지 않는다.
