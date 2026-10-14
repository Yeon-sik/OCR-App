# Project producer 계약 패치 — 최종 구현 기준

Project의 원본 00/06/07 파일은 이 작업 폴더에 없으므로 원본을 임의로 교체하지 않았다.
아래는 구현과 함께 검증하는 교체 문구다. 완전한 JSON 예제는
`examples/yeonsik-ocr.v5.restaurant-purchase-components.example.json`이다.

## 00_YEONSIK_OCR_SCHEMA_ROUTER.md

수정 필요. V5 선택 조건을 다음으로 확장한다.

> 비영수증 식당 주문/결제 source와 음식 사진 기반 영양이 함께 있으면 V5를 사용한다.
> 사진의 메인·사이드·반찬·국물·소스·음료는 각각 독립 nutrition으로 보존한다.
> 실제로 별도 구매된 항목은 해당 구매 행에 연결한다. 함께 제공된 구성요소는
> 부모 구매 행에 menu_component로 연결하며 구매 행이나 가격을 발명하지 않는다.
> 구매만 있으면 V4, 영수증과 음식 사진이면 기존 V2를 유지한다.

## 06_YEONSIK_OCR_V5_RESTAURANT_PURCHASE_SPEC.md

기존 nutrition/links 제한 부분을 다음으로 교체한다. top-level 14개 key와 source,
purchase_records, option_items, consumption, projection_targets의 기존 계약은 유지한다.

> nutrition은 restaurant_estimate를 사용한다. V2의 meal_component_estimate도
> complimentary_side 구성요소에 재사용할 수 있다. 둘 모두 line_id는 null이고,
> 필수 영양소의 provenance는 food_image_estimate이며 실제 선언된 food_photo를
> 참조해야 한다. V5 meal_component_estimate에는 legacy 식당/메뉴 reference를 넣지 않는다.
> 다른 구성 역할은 restaurant_estimate와 아래 link의 역할로 표현한다.
>
> link의 필수 key는 purchase_record_client_key, purchase_line_key, nutrition_client_key다.
> 선택 key는 component_role, relation_type이다. 추가 identity key는 허용하지 않는다.
> component_role: main, side, complimentary_side, sauce, beverage, other.
> relation_type: purchase_line, menu_component.
> 생략하면 기존 V5와 동일한 main + purchase_line이다. 기본값은 encoder에서도 생략한다.
>
> 각 nutrition_client_key는 정확히 하나의 relation에 있어야 한다.
> parent record/line은 실제 존재해야 한다. 한 record+line의 main은 최대 하나이며,
> 해당 행에 main과 여러 menu_component가 함께 연결될 수 있다.
> 실제 유료 행의 purchase_line relation은 최대 하나다. 추가 음식은 menu_component로
> 표현한다. 별도 유료 side는 자신의 실제 구매 행을 사용한다.
> main은 purchase_line이고, complimentary_side는 menu_component다.
> 별도로 구매한 side/beverage는 실제 별도 purchase_line과 기존 가격 경로를 사용한다.
> 사진으로 발견한 무료 구성요소에는 가짜 행, 0원 구매 또는 가격 observation을 만들지 않는다.
>
> seller/platform/menu 원문은 resolver 선택으로 교체하지 않는다.
> Restaurant/Location/Menu/Platform UUID, DB UUID, Storage URL 및 runtime 선택은
> producer canonical에 넣지 않는다. 선택·alias·server identity는 OCR runtime
> metadata/checkpoint에서만 다룬다. 공개 검증 후보를 사용자가 선택하더라도 원래
> source pair의 exact authority를 발명하지 않고 owner-confirmed 연결로 보존한다.
>
> 음식 사진은 영양의 근거이며 구매/결제 사실의 근거가 아니다. 주문/결제 screenshot의
> kcal·단백질 문구는 food_image_estimate가 아니다. 구매만으로 consumption/Meal을
> 생성하지 않으며 기존 명시적 사용자 섭취 진술과 provenance 경계를 유지한다.

메인 6,000원과 함께 제공된 국물의 links:

```json
[
  {
    "purchase_record_client_key": "purchase-1",
    "purchase_line_key": "line-1",
    "nutrition_client_key": "food-1"
  },
  {
    "purchase_record_client_key": "purchase-1",
    "purchase_line_key": "line-1",
    "nutrition_client_key": "food-soup",
    "component_role": "complimentary_side",
    "relation_type": "menu_component"
  }
]
```

## 07_YEONSIK_OCR_V5_RESTAURANT_PURCHASE_EXAMPLES.md

수정 필요. 기존 3-key 예제는 유지하고 아래 예제를 추가한다.

- 메인 한 행 6,000원 + 국물 nutrition 두 개를 같은 부모 행에 연결한 완전한 예제:
  `examples/yeonsik-ocr.v5.restaurant-purchase-components.example.json`.
- 독립 유료 사이드는 실제 별도 행에 `component_role=side`,
  `relation_type=purchase_line`으로 연결한다.
- 메뉴 연결 재검수는 각 nutrition의 실제 purchase record/line을 사용자가 선택한다.
  이름 유사성으로 authority를 확정하지 않는다.
- "한성대 학식당" / "캐네디 소시지 오므라이스" / "웨이티"를 기존 identity에
  연결하는 사례는 원문을 유지한다. 선택 UUID/alias는 예제 canonical에 넣지 않고
  OCR runtime의 검수 결과로 별도 설명한다.

## 05_YEONSIK_BUNDLE_SPEC.md

수정 불필요. ZIP/manifest, attachment ID/type, byte_size/SHA-256, evidence archive 및
bundle fingerprint의 transport 계약은 변경하지 않는다. canonical 내용의 새 revision은
기존 수정본 보관·verification event 경로를 사용한다.

## 적용 순서

PriceTrace 코드와 forward migration을 먼저 병합한다. 별도 승인된 배포 과정에서 migration을
검증·배포한 뒤 OCR resolver를 사용한다. OCR importer/UI 코드 병합과 위 producer 문구의
적용을 맞춘다. 이번 작업은 실제 remote migration apply/production write를 수행하지 않는다.
