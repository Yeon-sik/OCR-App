# yeonsik-ocr.v5 restaurant_purchase

Status: INACTIVE_RUNTIME_CANDIDATE. 실제 Project 원본 대조, PT 배포·HTTP smoke 및 OCR merge 전 활성화 금지. 문서 예제는 core parser 테스트 대상이다.

top-level key는 정확히 `schema_version`, `mode`, `source`, `merchant_candidate`, `receipt`, `product_candidates`, `price_observations`, `purchase_records`, `nutrition`, `consumption`, `classification_hints`, `links`, `projection_targets`, `review`다. schema_version은 yeonsik-ocr.v5, mode는 restaurant_purchase다. merchant_candidate/receipt는 null, product_candidates/price_observations는 빈 배열이다. 누락/추가 key는 거부한다.

source는 `{producer,source_files,user_text}`, source_files 원소는 `{id,type,label}`다. producer는 chatgpt 또는 ocr_app. source file type은 order_history/payment_history/food_photo만 허용하며 nonblank local ID는 unique다. user_text는 nullable이며 source fact를 identity 선택으로 바꾸지 않는다.

purchase_records는 1개 이상이고 모두 purchase_kind=restaurant이다. 필드·금액·날짜·구매 evidence는 기존 V4 계약을 재사용한다. 실제 구매 행은 line_key로 식별한다. structured option_items는 실제 부모 행 내부에 남고 nutrition link에 option_key를 추가하지 않는다. 옵션에 관측된 0원은 그대로 보존할 수 있지만 무료 음식의 purchase line이나 0원 가격을 새로 만들 수 없다. 옵션의 kcal/단백질 문구는 음식 사진 provenance로 승격하지 않는다.

nutrition은 1개 이상이며 local client_key가 unique/nonblank다. 기본 kind는 restaurant_estimate이고 line_id=null, menu_name은 nonblank다. 기존 V2 meal_component_estimate는 complimentary_side + menu_component에 한해 재사용한다. 이 경우에도 line_id=null이고 legacy identity reference는 금지한다. 다른 역할에는 restaurant_estimate와 link role을 사용한다. estimate의 기존 V2 필드·단위·range/confidence 검증을 그대로 적용한다. 필수 영양소 provenance는 food_image_estimate이며 evidence_refs는 선언된 실제 food_photo를 참조해야 한다. order/payment screenshot은 이 근거를 대신하지 않는다. 선언만 있고 읽을 수 있는 로컬 바인딩이 없으면 runtime evidence gate가 차단한다.

| links key | 요구 |
| --- | --- |
| purchase_record_client_key | 필수; 실제 record local key |
| purchase_line_key | 필수; 해당 record의 실제 line key |
| nutrition_client_key | 필수; 실제 nutrition local key |
| component_role | 선택: main / side / complimentary_side / sauce / beverage / other. 기본 main |
| relation_type | 선택: purchase_line / menu_component. 기본 purchase_line |

기본값은 encoder에서도 생략하여 legacy 3-key canonical을 보존한다. 다른 key와 DB UUID는 허용하지 않는다. 모든 nutrition은 정확히 하나의 relation을 갖는다. 같은 부모 record+line의 main은 최대 하나이며 purchase_line relation도 최대 하나다. 그 행에 menu_component는 여러 개 가능하다. main은 purchase_line, complimentary_side는 menu_component다. 별도 유료 side/beverage는 자신의 실제 행을 사용한다. dangling/conflicting/duplicate relation은 거부한다.

consumption은 기존 V2 item-level 계약이다. 구매만으로 생성하지 않는다. source.user_text의 명시적인 사용자 진술이 필요하며 consumed_at을 넣으면 해당 timestamp가 user_text에 명시되어야 한다. order/payment/photo time을 consumed_at으로 복사하지 않는다. 명시적 consumption만 Fitness Meal 적격성을 가질 수 있고 local verification 및 Nutrition dependency가 충족되어야 전송된다.

projection_targets는 PriceTrace 가격, CashOS transaction, Fitness Nutrition/Meal의 기존 명칭만 허용한다. 최종 적격성은 runtime이 계산한다. 외부 review/verification은 신뢰하지 않고 local review gate를 거친다.

server Restaurant/Location/Menu/Platform UUID, evidence UUID, Storage URL, search query/selection은 canonical에 넣지 않는다. OCR runtime metadata/checkpoint에서만 보존한다. source seller/menu/platform 문자열을 resolution 결과로 덮어쓰지 않는다. public candidate 선택은 owner-confirmed mapping이며 exact/global publication authority가 아니다.

각 component Nutrition은 Fitness private import로 남으며 부모 유료 메뉴의 private link/public publication identity를 상속하지 않는다. PriceTrace의 구성 관계는 owner-private이고 가격 column/observation이 없다. CashOS transaction은 실제 구매 record/line만 사용한다.

PT component evidence identity는 `(user_id,purchase_source_id,canonical_fingerprint,nutrition_client_key)`다. 독립 purchase의 동일 local key/fingerprint는 허용한다. 동일 source/revision/key 재전송은 reuse, 부모/facts 변경은 conflict, 새 revision은 evidence history append다.
