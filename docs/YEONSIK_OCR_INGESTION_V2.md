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

source attachment type에 `product_photo` (`PRODUCT_PHOTO`)를 추가했다. `product_candidates` 항목은 최신 Project wire field와 source fact만 가진다.

- `product_name`
- `brand_name`, `manufacturer_name`, `variant_name`, `specification_text`
- `content_amount`, `content_unit`, `package_count`
- `barcodes[]` (`scheme`, `value`)
- `source_attachment_ids`, `confidence`

v2 입력에는 `catalog_product_id`, `standard_product_id`, `restaurant_menu_id`,
`nutrition_food_id` 같은 서버 identity를 넣을 수 없다. OCR-App은
`PRICETRACE_PRODUCT_CANDIDATE` projection에서 PriceTrace의
`submit_product_candidate_v1`에 fact-only payload를 보낸다. PriceTrace가 반환한
`catalogProductId`와 candidate response만 이후 projection identity로 보존한다.

### Text-backed packaged product

첨부 없이 공식 영양성분 공개 페이지를 조회한 packaged product도 정상적인 v2 경로다.
이 경우 `source.source_files`와 `product_candidates[].source_attachment_ids`는 빈 배열이고,
`source.user_text`는 비어 있지 않아야 한다. 후보 fact는 입력에 이미 있는 값만 보존하며,
각 evidence는 `source_type=user_statement`로 생성한다. user text에서 상품 필드를 새로
추론하거나 `consumption`을 만들지 않는다.

ProductLabel payload는 기존 `fitness-nutrition-draft.v1` schema를 유지하면서 다음 provenance를
사용한다.

| field | text lookup contract |
| --- | --- |
| `source_type` | `external_reference` |
| `source_reference` | 공개 `http`/`https` 영양성분 URL |
| `parser_version` / `source_version` | `external-nutrition-lookup.v1` |
| `estimate` | `null` |

Fitness projection은 hierarchy-aware v3 RPC의 `external-reference.v1` input contract와
실제 공개 URL을 `evidence_refs`에 사용한다. `product_label_ocr`와 attachment-backed
`PRODUCT_PHOTO` 경로는 기존 `nutrition-label.v1` 계약과 V2 RPC를 그대로 유지한다.

## Meal

`consumption`은 사용자가 실제 섭취를 명시한 경우에만 producer가 넣을 수 있다. 상품 정보,
음식 사진, 구매 사실만으로 OCR/App이 자동 생성하지 않는다. 각 item은
`nutrition_client_key`, `amount`, `unit`, `confidence`, `amount_status`를 가진다. `amount_status=user_provided`는
비어 있지 않은 `source.user_text`의 사용자 섭취 진술에만 쓴다. 이 경우 FOOD_PHOTO는 evidence gate의
요건이 아니다. 사진 기반 추정량(`amount_status=estimated`) 등 다른 amount status는 기존처럼
`FOOD_PHOTO` evidence가 필요하다. `consumed_at`은 사용자가 명시한 경우에만 넣으며, receipt 결제시각이나
사진시각으로 자동 대체하지 않는다.

`FITNESS_MEAL`은 사용자 검증 이후 Fitness의 `import_verified_meal_v1`로 전송한다. 각 item의
양·단위·confidence와 실제 식사시각을 그대로 보존하며, Fitness가 반환한
`meal_record_id`만 projection 결과로 저장한다.

## Restaurant nutrition without a receipt

V2의 `restaurant_estimate`는 영수증이 있는 식당 흐름과 영수증이 없는 음식사진 흐름을
같은 kind로 표현한다. 영수증이 있으면 `line_id`가 실제 `receipt.line_items[].id`여야 하고,
기존 `links`에도 같은 연결이 있어야 한다. 영수증이 없으면 `mode`는 `restaurant`,
`merchant_candidate`는 필수이며, `line_id`는 반드시 `null`이다. 이 경우 가짜 receipt나
receipt line을 만들지 않는다.

영수증 없는 식당 estimate의 source evidence는 `food_photo`다. `menu_photo`나 V3의
`restaurant_menu_estimate`를 V2에 섞지 않는다. `PRICETRACE_MERCHANT_CANDIDATE`와
`FITNESS_NUTRITION`은 독립적으로 동시에 계획할 수 있지만, 가격·날짜 근거가 없으므로
`PRICETRACE_PRICE_OBSERVATION`은 만들지 않는다. 음식사진과 가게/메뉴 텍스트만으로
`consumption` 또는 `FITNESS_MEAL`을 생성하지 않으며, 사용자가 실제 섭취를 명시한 경우에만
기존 consumption 검증 규칙을 적용한다.

현재 receipt-free restaurant merchant fact 경로는 `source.user_text`가 비어 있지 않고
`merchant_candidate.source_attachment_ids`가 빈 배열인 text-backed evidence다. 이때
`food_photo`는 영양 추정 evidence로만 사용하며 merchant name evidence로 승격하지 않는다.
별도의 merchant attachment를 실제로 참조하는 향후 경로는 해당 attachment가 로컬에서
읽을 수 있을 때만 허용하고, `food_photo`를 merchant source로 취급하지 않는다. Merchant
artifact를 text로 통과시켜도 full-envelope 검증에서는 `restaurant_estimate`의
`FOOD_PHOTO` 검사를 별도로 수행한다.

Receipt line의 `food_service.benefit_kind`는 `included`, `complimentary`, `review_event`,
`promotion`, `other`를 지원한다. 값이 null이 아닌 line은 일반 PriceTrace price observation으로
승격하지 않는다. 0원 또는 소액이라는 값만으로 benefit kind를 OCR/App이 자동 추론하지 않는다.
## Side dish / meal component

Nutrition kind `meal_component_estimate`는 음식 사진으로 추정한 무료 반찬처럼 receipt에
없는 항목을 표현한다. `component_role`은 `complimentary_side` 같은 원본 역할을 보존한다.
`line_id`와 `restaurant_menu_id`는 null일 수 있고 receipt line 연결을
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
않는지, consumption item이 기존 nutrition을 참조하는지 검증한다. Project의 `projection_targets`는
hint일 뿐 routing authority가 아니며, Product Candidate와 NutritionFood가 모두 downstream에서
해결된 뒤에만 `pricetrace_product_nutrition_link`가 실행된다. 외부 `review.status`나
`consumption.status`는 서버 권한으로 취급하지 않고 항상 `UNVERIFIED`로 시작하며, production
UI에서 명시적으로 확정한 뒤에만 `USER_VERIFIED`가 된다. 이 문서와 examples는 OCR Project의
authoritative SPEC을 대체하는 schema authority가 아니다.

- `examples/yeonsik-ocr.v2.packaged-product.example.json`
- `examples/yeonsik-ocr.v2.packaged-product.text-lookup.example.json`
- `examples/yeonsik-ocr.v2.restaurant.example.json`
- `examples/yeonsik-ocr.v2.restaurant-food-photo.example.json`

로컬 검증:

```text
gradlew.bat :receipt-scanner:testDebugUnitTest :app:testDebugUnitTest --no-daemon
gradlew.bat :app:assembleDebug --no-daemon
```

이 검증은 codec, evidence gate, projection payload/재시도와 컴파일을 확인한다. 실제
Supabase RPC/RLS, 계정 세션, Fitness/PriceTrace remote 결과와 물리기기 E2E의 증명은 별도다.
