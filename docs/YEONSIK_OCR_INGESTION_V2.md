# yeonsik-ocr.v2

`yeonsik-ocr.v2`는 `yeonsik-ocr.v1`을 대체하지 않는다. `YeonsikOcrEnvelopeCodec`가
`schema_version`으로 dispatch하며, v1은 기존 `YeonsikOcrEnvelopeJson`으로 계속 읽고 쓴다.
v2에서만 상품 후보와 item 단위 식사 섭취를 사용한다.

## 경계

OCR-App은 외부 JSON을 검증하고, 원본 증거를 로컬에 연결하고, 각 canonical projection을
독립적으로 라우팅하는 계층이다. PriceTrace, Fitness, CashOS의 Master 데이터나 UUID를
소유하지 않으며, GPT가 서버 identity를 생성·확정하지 않는다.

| 영역 | Master | OCR-App 동작 |
| --- | --- | --- |
| 상품/판매처/영수증 identity | PriceTrace | 관측 후보만 제출하고 응답 identity만 저장 |
| NutritionFood/Meal | Fitness | canonical RPC에 검증된 payload 전송 |
| account/category/ledger identity | CashOS | 기존 CashOS projection만 라우팅 |

각 projection은 별도 상태·idempotency key·retry 경로를 갖는다. 서비스 간 분산 트랜잭션은
만들지 않는다.

## Product

source attachment type에 `product_photo` (`PRODUCT_PHOTO`)를 추가했다. `product_candidates`
항목은 다음 관측 사실과 evidence만 가질 수 있다.

- `product_name`
- `brand` 또는 `manufacturer`
- `specification`, `content_amount`, `content_unit`, `package_count`
- `variant`
- `barcode`, `ean`, `upc`
- source attachment를 가리키는 `evidence`

v2 입력에는 `catalog_product_id`, `standard_product_id`, `restaurant_menu_id`,
`nutrition_food_id` 같은 서버 identity를 넣을 수 없다. OCR-App은
`PRICETRACE_PRODUCT_CANDIDATE` projection에서 PriceTrace의
`submit_product_candidate_v1`에 fact-only payload를 보낸다. PriceTrace가 반환한
`catalogProductId`와 candidate response만 이후 projection identity로 보존한다.

## Meal

`consumption`은 `consumed_at`과 item 목록을 필수로 한다. 각 item은
`nutrition_client_key`, `amount`, `unit`, `confidence`를 가진다. `consumed_at`은 ISO-8601
offset을 포함한 실제 식사시각이며 receipt 결제시각이나 사진시각으로 자동 대체하지 않는다.

`FITNESS_MEAL`은 사용자 검증 이후 Fitness의 `import_verified_meal_v1`로 전송한다. 각 item의
양·단위·confidence와 실제 식사시각을 그대로 보존하며, Fitness가 반환한
`meal_record_id`만 projection 결과로 저장한다.

## Side dish / meal component

Nutrition kind `meal_component_estimate`는 음식 사진으로 추정한 무료 반찬처럼 receipt에
없는 항목을 표현한다. `line_id`와 `restaurant_menu_id`는 null일 수 있고 receipt line 연결을
요구하지 않는다. 식당/지점은 `restaurant_name`, `branch_name` provenance/reference로만
보존할 수 있다.

이 항목은 Fitness의 별도 `import_meal_component_estimate_v1` 경계와 Meal projection으로만
전송한다. PriceTrace RestaurantMenu projection에는 보내지 않으며, `links`에도 포함하지
않는다. `restaurant_menu_id`는 GPT/OCR 입력에서 확정하지 않는다.

## Product ↔ Nutrition dependency

상품 후보가 PriceTrace에서 해결되고 같은 v2 envelope의 ProductLabel이 Fitness에서
NutritionFood로 생성된 뒤에만 `FITNESS_PRODUCT_NUTRITION_LINK`를 실행한다.

1. PriceTrace candidate response에서 받은 `catalogProductId`를 사용한다.
2. response에 product revision이 없으면 PriceTrace `get_product_read_v1`를 해당
   `catalogProductId`로 exact read한다.
3. Fitness `propose_product_nutrition_link_v1`에 catalog ID, NutritionFood ID, exact
   `sha256:` revision, source provenance를 보낸다.

의존성이 아직 업로드되지 않았거나 exact revision read가 일시적으로 실패하면 link는
`BLOCKED`/retryable 상태로 남고, 이미 성공한 candidate·nutrition projection은 되돌리지
않는다.

## 검증 및 예시

v2 codec은 root/nested key를 strict하게 검사하고, candidate evidence가 실제
`PRODUCT_PHOTO` attachment를 참조하는지, component가 PriceTrace menu identity를 넣지
않는지, consumption에 실제 offset 시각과 item 값을 갖는지 검증한다. 외부 `review.status`나
`consumption.status`는 서버 권한으로 취급하지 않고 로컬 사용자 검증으로 다시 결정한다.

- `examples/yeonsik-ocr.v2.packaged-product.example.json`
- `examples/yeonsik-ocr.v2.restaurant.example.json`

로컬 검증:

```text
gradlew.bat :receipt-scanner:testDebugUnitTest :app:testDebugUnitTest --no-daemon
gradlew.bat :app:assembleDebug --no-daemon
```

이 검증은 codec, evidence gate, projection payload/재시도와 컴파일을 확인한다. 실제
Supabase RPC/RLS, 계정 세션, Fitness/PriceTrace remote 결과와 물리기기 E2E의 증명은 별도다.
