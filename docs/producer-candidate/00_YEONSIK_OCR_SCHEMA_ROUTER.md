# Yeonsik OCR schema router

Status: INACTIVE_RUNTIME_CANDIDATE. 기존 Project 원본은 제공되지 않았다. 이 파일은 실제 Source of Truth 대조와 배포 gate를 마치기 전 활성화하지 않는다. 기존 V1–V4 계약은 각 runtime codec을 유지한다.

| 근거/작업 | schema |
| --- | --- |
| 비영수증 식당 주문·결제 근거와 실제 음식 사진의 영양이 함께 있음 | yeonsik-ocr.v5 / restaurant_purchase |
| 주문·결제/구매 사실만 있음, food-photo nutrition 없음 | 기존 yeonsik-ocr.v4 |
| 식당 영수증 및 음식 사진 | 기존 yeonsik-ocr.v2 |
| V5 범위 밖 mixed evidence | 기존 needs_split 규칙 유지; V5로 강제 변환하지 않음 |

V5는 실제 구매 main과 함께 제공된 국물·반찬·소스·음료를 각각 독립 nutrition으로 보존한다. 실제 별도 유료 항목은 자신의 실제 purchase line에 연결한다. 무료/포함 항목은 실제 부모 행의 menu_component다. 가짜 purchase line, 0원 가격, price observation을 만들지 않는다.

order_history/payment_history는 구매 근거이며 nutrition의 food_photo provenance를 대신하지 않는다. 사진만으로 결제 사실이나 섭취를 만들지 않는다. consumption은 명시적인 사용자 진술만 사용한다.

상세 계약: [06 V5 spec](06_YEONSIK_OCR_V5_RESTAURANT_PURCHASE_SPEC.md), [07 complete examples](07_YEONSIK_OCR_V5_RESTAURANT_PURCHASE_EXAMPLES.md), [05 bundle](05_YEONSIK_BUNDLE_SPEC.md).
