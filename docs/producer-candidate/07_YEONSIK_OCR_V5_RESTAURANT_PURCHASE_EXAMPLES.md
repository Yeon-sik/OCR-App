# V5 complete examples

Status: INACTIVE_RUNTIME_CANDIDATE. 실제 Project 원본 대조/배포 gate 전 활성화하지 않는다. 아래는 합성 예제이며 source 선언만으로 실제 evidence를 대신하지 않는다. 모든 JSON block은 core strict parser로 decode/validate한다.

## A. Legacy 3-key main

기본 main/purchase_line. optional key를 넣지 않는다.

```json
{
  "schema_version": "yeonsik-ocr.v5",
  "mode": "restaurant_purchase",
  "source": {
    "producer": "chatgpt",
    "source_files": [
      {
        "id": "order-history-1",
        "type": "order_history",
        "label": "주문내역 캡처"
      },
      {
        "id": "payment-history-1",
        "type": "payment_history",
        "label": "결제내역 캡처"
      },
      {
        "id": "food-photo-1",
        "type": "food_photo",
        "label": "육회비빔밥 음식 사진"
      }
    ],
    "user_text": null
  },
  "merchant_candidate": null,
  "receipt": null,
  "product_candidates": [],
  "price_observations": [],
  "purchase_records": [
    {
      "client_key": "purchase-1",
      "platform": "예시 주문 플랫폼",
      "platform_code": null,
      "seller": "맛단-맛있는 다이어트식단",
      "seller_branch_name": "영등포점",
      "seller_source_namespace": null,
      "seller_source_code": null,
      "seller_business_kind": "food_service",
      "source_version": "chatgpt-restaurant-purchase-v5-example",
      "order_reference": "order-example-1",
      "purchase_kind": "restaurant",
      "ordered_on": "2026-09-11",
      "ordered_at": null,
      "paid_on": "2026-09-11",
      "paid_at": null,
      "status": "paid",
      "currency": "KRW",
      "totals": {
        "subtotal_amount_krw": 1100,
        "discount_amount_krw": 0,
        "shipping_amount_krw": 0,
        "tax_amount_krw": 0,
        "grand_total_amount_krw": 1100,
        "paid_amount_krw": 1100
      },
      "payment": {
        "method": "card",
        "provider": null,
        "status": "paid"
      },
      "line_items": [
        {
          "line_key": "line-1",
          "product_client_key": null,
          "description": "육회비빔밥",
          "seller_override": null,
          "option_text": null,
          "option_items": [],
          "price_status": "itemized",
          "merchant_sku": null,
          "quantity": 2,
          "unit_price_amount_krw": 550,
          "gross_amount_krw": 1100,
          "discount_amount_krw": 0,
          "net_amount_krw": 1100
        }
      ],
      "evidence": [
        {
          "source_type": "order_history",
          "source_attachment_ids": [
            "order-history-1"
          ],
          "source_ref": "order-seller",
          "field": "seller",
          "observed_value": "맛단-맛있는 다이어트식단"
        },
        {
          "source_type": "order_history",
          "source_attachment_ids": [
            "order-history-1"
          ],
          "source_ref": "order-branch",
          "field": "seller_branch_name",
          "observed_value": "영등포점"
        },
        {
          "source_type": "order_history",
          "source_attachment_ids": [
            "order-history-1"
          ],
          "source_ref": "order-header",
          "field": "platform",
          "observed_value": "예시 주문 플랫폼"
        },
        {
          "source_type": "order_history",
          "source_attachment_ids": [
            "order-history-1"
          ],
          "source_ref": "order-total",
          "field": "grand_total_amount_krw",
          "observed_value": "1100"
        },
        {
          "source_type": "payment_history",
          "source_attachment_ids": [
            "payment-history-1"
          ],
          "source_ref": "payment-status",
          "field": "payment.status",
          "observed_value": "paid"
        }
      ],
      "confidence": 0.98
    }
  ],
  "nutrition": [
    {
      "client_key": "food-1",
      "kind": "restaurant_estimate",
      "line_id": null,
      "menu_name": "육회비빔밥",
      "payload": null,
      "estimate": {
        "estimated": true,
        "confidence": 0.82,
        "nutrients": {
          "calories_kcal": 620,
          "protein_grams": 28,
          "carbs_grams": 82,
          "fat_grams": 18,
          "sodium_mg": 980,
          "saturated_fat_grams": 4,
          "sugars_grams": 9,
          "fiber_grams": 6,
          "added_sugars_grams": null,
          "trans_fat_grams": null,
          "cholesterol_mg": 110
        },
        "ranges": {},
        "provenance": {
          "calories_kcal": {
            "value_status": "estimated",
            "source_type": "food_image_estimate",
            "evidence_refs": [
              "food-photo-1"
            ]
          },
          "protein_grams": {
            "value_status": "estimated",
            "source_type": "food_image_estimate",
            "evidence_refs": [
              "food-photo-1"
            ]
          },
          "carbs_grams": {
            "value_status": "estimated",
            "source_type": "food_image_estimate",
            "evidence_refs": [
              "food-photo-1"
            ]
          },
          "fat_grams": {
            "value_status": "estimated",
            "source_type": "food_image_estimate",
            "evidence_refs": [
              "food-photo-1"
            ]
          },
          "sodium_mg": {
            "value_status": "estimated",
            "source_type": "food_image_estimate",
            "evidence_refs": [
              "food-photo-1"
            ]
          },
          "saturated_fat_grams": {
            "value_status": "estimated",
            "source_type": "food_image_estimate",
            "evidence_refs": [
              "food-photo-1"
            ]
          },
          "sugars_grams": {
            "value_status": "estimated",
            "source_type": "food_image_estimate",
            "evidence_refs": [
              "food-photo-1"
            ]
          }
        }
      }
    }
  ],
  "consumption": [],
  "classification_hints": {
    "cashos": {
      "category_hint": "food",
      "institution_hint": null,
      "payment_method_hint": "card"
    }
  },
  "links": [
    {
      "nutrition_client_key": "food-1",
      "purchase_line_key": "line-1",
      "purchase_record_client_key": "purchase-1"
    }
  ],
  "projection_targets": [
    "pricetrace_price_observation",
    "cashos_transaction",
    "fitness_nutrition"
  ],
  "review": {
    "status": "needs_review",
    "blocking_issues": [],
    "warnings": []
  }
}
```

## B. Main 6,000원 + 무료 국물

구매 행 1개, nutrition 2개, consumption 없음. 국물은 같은 부모의 menu_component다.

```json
{
  "schema_version": "yeonsik-ocr.v5",
  "mode": "restaurant_purchase",
  "source": {
    "producer": "chatgpt",
    "source_files": [
      {
        "id": "order-history-1",
        "type": "order_history",
        "label": "주문내역 캡처"
      },
      {
        "id": "payment-history-1",
        "type": "payment_history",
        "label": "결제내역 캡처"
      },
      {
        "id": "food-photo-1",
        "type": "food_photo",
        "label": "오므라이스와 함께 제공된 국물 사진"
      }
    ],
    "user_text": null
  },
  "merchant_candidate": null,
  "receipt": null,
  "product_candidates": [],
  "price_observations": [],
  "purchase_records": [
    {
      "client_key": "purchase-1",
      "platform": "웨이티",
      "platform_code": null,
      "seller": "한성대 학식당",
      "seller_branch_name": null,
      "seller_source_namespace": null,
      "seller_source_code": null,
      "seller_business_kind": "food_service",
      "source_version": "chatgpt-restaurant-purchase-v5-example",
      "order_reference": "component-example-1",
      "purchase_kind": "restaurant",
      "ordered_on": "2026-10-14",
      "ordered_at": null,
      "paid_on": "2026-10-14",
      "paid_at": null,
      "status": "paid",
      "currency": "KRW",
      "totals": {
        "subtotal_amount_krw": 6000,
        "discount_amount_krw": 0,
        "shipping_amount_krw": 0,
        "tax_amount_krw": 0,
        "grand_total_amount_krw": 6000,
        "paid_amount_krw": 6000
      },
      "payment": {
        "method": "card",
        "provider": null,
        "status": "paid"
      },
      "line_items": [
        {
          "line_key": "line-1",
          "product_client_key": null,
          "description": "캐네디소시지오므라이스",
          "seller_override": null,
          "option_text": null,
          "option_items": [],
          "price_status": "itemized",
          "merchant_sku": null,
          "quantity": 1,
          "unit_price_amount_krw": 6000,
          "gross_amount_krw": 6000,
          "discount_amount_krw": 0,
          "net_amount_krw": 6000
        }
      ],
      "evidence": [
        {
          "source_type": "order_history",
          "source_attachment_ids": [
            "order-history-1"
          ],
          "source_ref": "order-seller",
          "field": "seller",
          "observed_value": "한성대 학식당"
        },
        {
          "source_type": "order_history",
          "source_attachment_ids": [
            "order-history-1"
          ],
          "source_ref": "order-header",
          "field": "platform",
          "observed_value": "웨이티"
        },
        {
          "source_type": "order_history",
          "source_attachment_ids": [
            "order-history-1"
          ],
          "source_ref": "order-total",
          "field": "grand_total_amount_krw",
          "observed_value": "6000"
        },
        {
          "source_type": "payment_history",
          "source_attachment_ids": [
            "payment-history-1"
          ],
          "source_ref": "payment-status",
          "field": "payment.status",
          "observed_value": "paid"
        }
      ],
      "confidence": 0.98
    }
  ],
  "nutrition": [
    {
      "client_key": "food-1",
      "kind": "restaurant_estimate",
      "line_id": null,
      "menu_name": "캐네디소시지오므라이스",
      "payload": null,
      "estimate": {
        "estimated": true,
        "confidence": 0.82,
        "nutrients": {
          "calories_kcal": 620,
          "protein_grams": 28,
          "carbs_grams": 82,
          "fat_grams": 18,
          "sodium_mg": 980,
          "saturated_fat_grams": 4,
          "sugars_grams": 9,
          "fiber_grams": 6,
          "added_sugars_grams": null,
          "trans_fat_grams": null,
          "cholesterol_mg": 110
        },
        "ranges": {},
        "provenance": {
          "calories_kcal": {
            "value_status": "estimated",
            "source_type": "food_image_estimate",
            "evidence_refs": [
              "food-photo-1"
            ]
          },
          "protein_grams": {
            "value_status": "estimated",
            "source_type": "food_image_estimate",
            "evidence_refs": [
              "food-photo-1"
            ]
          },
          "carbs_grams": {
            "value_status": "estimated",
            "source_type": "food_image_estimate",
            "evidence_refs": [
              "food-photo-1"
            ]
          },
          "fat_grams": {
            "value_status": "estimated",
            "source_type": "food_image_estimate",
            "evidence_refs": [
              "food-photo-1"
            ]
          },
          "sodium_mg": {
            "value_status": "estimated",
            "source_type": "food_image_estimate",
            "evidence_refs": [
              "food-photo-1"
            ]
          },
          "saturated_fat_grams": {
            "value_status": "estimated",
            "source_type": "food_image_estimate",
            "evidence_refs": [
              "food-photo-1"
            ]
          },
          "sugars_grams": {
            "value_status": "estimated",
            "source_type": "food_image_estimate",
            "evidence_refs": [
              "food-photo-1"
            ]
          }
        }
      }
    },
    {
      "client_key": "food-soup",
      "kind": "meal_component_estimate",
      "line_id": null,
      "menu_name": "국물",
      "payload": null,
      "estimate": {
        "estimated": true,
        "confidence": 0.82,
        "nutrients": {
          "calories_kcal": 30,
          "protein_grams": 2,
          "carbs_grams": 4,
          "fat_grams": 1,
          "sodium_mg": 400,
          "saturated_fat_grams": 0.2,
          "sugars_grams": 1,
          "fiber_grams": 0.5,
          "added_sugars_grams": null,
          "trans_fat_grams": null,
          "cholesterol_mg": 0
        },
        "ranges": {},
        "provenance": {
          "calories_kcal": {
            "value_status": "estimated",
            "source_type": "food_image_estimate",
            "evidence_refs": [
              "food-photo-1"
            ]
          },
          "protein_grams": {
            "value_status": "estimated",
            "source_type": "food_image_estimate",
            "evidence_refs": [
              "food-photo-1"
            ]
          },
          "carbs_grams": {
            "value_status": "estimated",
            "source_type": "food_image_estimate",
            "evidence_refs": [
              "food-photo-1"
            ]
          },
          "fat_grams": {
            "value_status": "estimated",
            "source_type": "food_image_estimate",
            "evidence_refs": [
              "food-photo-1"
            ]
          },
          "sodium_mg": {
            "value_status": "estimated",
            "source_type": "food_image_estimate",
            "evidence_refs": [
              "food-photo-1"
            ]
          },
          "saturated_fat_grams": {
            "value_status": "estimated",
            "source_type": "food_image_estimate",
            "evidence_refs": [
              "food-photo-1"
            ]
          },
          "sugars_grams": {
            "value_status": "estimated",
            "source_type": "food_image_estimate",
            "evidence_refs": [
              "food-photo-1"
            ]
          }
        }
      },
      "component_role": "complimentary_side"
    }
  ],
  "consumption": [],
  "classification_hints": {
    "cashos": {
      "category_hint": "food",
      "institution_hint": null,
      "payment_method_hint": "card"
    }
  },
  "links": [
    {
      "nutrition_client_key": "food-1",
      "purchase_line_key": "line-1",
      "purchase_record_client_key": "purchase-1"
    },
    {
      "purchase_record_client_key": "purchase-1",
      "purchase_line_key": "line-1",
      "nutrition_client_key": "food-soup",
      "component_role": "complimentary_side",
      "relation_type": "menu_component"
    }
  ],
  "projection_targets": [
    "pricetrace_price_observation",
    "cashos_transaction",
    "fitness_nutrition"
  ],
  "review": {
    "status": "needs_review",
    "blocking_issues": [],
    "warnings": []
  }
}
```

## C. 실제 별도 유료 side

실제 구매 행 2개와 총 7,500원. paid-side는 독립 purchase_line/side이며 0원 음식 행이 없다.

```json
{
  "schema_version": "yeonsik-ocr.v5",
  "mode": "restaurant_purchase",
  "source": {
    "producer": "chatgpt",
    "source_files": [
      {
        "id": "order-history-1",
        "type": "order_history",
        "label": "주문내역 캡처"
      },
      {
        "id": "payment-history-1",
        "type": "payment_history",
        "label": "결제내역 캡처"
      },
      {
        "id": "food-photo-1",
        "type": "food_photo",
        "label": "오므라이스와 함께 제공된 국물 사진"
      }
    ],
    "user_text": null
  },
  "merchant_candidate": null,
  "receipt": null,
  "product_candidates": [],
  "price_observations": [],
  "purchase_records": [
    {
      "client_key": "purchase-1",
      "platform": "웨이티",
      "platform_code": null,
      "seller": "한성대 학식당",
      "seller_branch_name": null,
      "seller_source_namespace": null,
      "seller_source_code": null,
      "seller_business_kind": "food_service",
      "source_version": "chatgpt-restaurant-purchase-v5-example",
      "order_reference": "component-example-1",
      "purchase_kind": "restaurant",
      "ordered_on": "2026-10-14",
      "ordered_at": null,
      "paid_on": "2026-10-14",
      "paid_at": null,
      "status": "paid",
      "currency": "KRW",
      "totals": {
        "subtotal_amount_krw": 7500,
        "discount_amount_krw": 0,
        "shipping_amount_krw": 0,
        "tax_amount_krw": 0,
        "grand_total_amount_krw": 7500,
        "paid_amount_krw": 7500
      },
      "payment": {
        "method": "card",
        "provider": null,
        "status": "paid"
      },
      "line_items": [
        {
          "line_key": "line-1",
          "product_client_key": null,
          "description": "캐네디소시지오므라이스",
          "seller_override": null,
          "option_text": null,
          "option_items": [],
          "price_status": "itemized",
          "merchant_sku": null,
          "quantity": 1,
          "unit_price_amount_krw": 6000,
          "gross_amount_krw": 6000,
          "discount_amount_krw": 0,
          "net_amount_krw": 6000
        },
        {
          "line_key": "paid-side",
          "product_client_key": null,
          "description": "추가 돈까스",
          "seller_override": null,
          "option_text": null,
          "option_items": [],
          "price_status": "itemized",
          "merchant_sku": null,
          "quantity": 1,
          "unit_price_amount_krw": 1500,
          "gross_amount_krw": 1500,
          "discount_amount_krw": 0,
          "net_amount_krw": 1500
        }
      ],
      "evidence": [
        {
          "source_type": "order_history",
          "source_attachment_ids": [
            "order-history-1"
          ],
          "source_ref": "order-seller",
          "field": "seller",
          "observed_value": "한성대 학식당"
        },
        {
          "source_type": "order_history",
          "source_attachment_ids": [
            "order-history-1"
          ],
          "source_ref": "order-header",
          "field": "platform",
          "observed_value": "웨이티"
        },
        {
          "source_type": "order_history",
          "source_attachment_ids": [
            "order-history-1"
          ],
          "source_ref": "order-total",
          "field": "grand_total_amount_krw",
          "observed_value": "6000"
        },
        {
          "source_type": "payment_history",
          "source_attachment_ids": [
            "payment-history-1"
          ],
          "source_ref": "payment-status",
          "field": "payment.status",
          "observed_value": "paid"
        }
      ],
      "confidence": 0.98
    }
  ],
  "nutrition": [
    {
      "client_key": "food-1",
      "kind": "restaurant_estimate",
      "line_id": null,
      "menu_name": "캐네디소시지오므라이스",
      "payload": null,
      "estimate": {
        "estimated": true,
        "confidence": 0.82,
        "nutrients": {
          "calories_kcal": 620,
          "protein_grams": 28,
          "carbs_grams": 82,
          "fat_grams": 18,
          "sodium_mg": 980,
          "saturated_fat_grams": 4,
          "sugars_grams": 9,
          "fiber_grams": 6,
          "added_sugars_grams": null,
          "trans_fat_grams": null,
          "cholesterol_mg": 110
        },
        "ranges": {},
        "provenance": {
          "calories_kcal": {
            "value_status": "estimated",
            "source_type": "food_image_estimate",
            "evidence_refs": [
              "food-photo-1"
            ]
          },
          "protein_grams": {
            "value_status": "estimated",
            "source_type": "food_image_estimate",
            "evidence_refs": [
              "food-photo-1"
            ]
          },
          "carbs_grams": {
            "value_status": "estimated",
            "source_type": "food_image_estimate",
            "evidence_refs": [
              "food-photo-1"
            ]
          },
          "fat_grams": {
            "value_status": "estimated",
            "source_type": "food_image_estimate",
            "evidence_refs": [
              "food-photo-1"
            ]
          },
          "sodium_mg": {
            "value_status": "estimated",
            "source_type": "food_image_estimate",
            "evidence_refs": [
              "food-photo-1"
            ]
          },
          "saturated_fat_grams": {
            "value_status": "estimated",
            "source_type": "food_image_estimate",
            "evidence_refs": [
              "food-photo-1"
            ]
          },
          "sugars_grams": {
            "value_status": "estimated",
            "source_type": "food_image_estimate",
            "evidence_refs": [
              "food-photo-1"
            ]
          }
        }
      }
    },
    {
      "client_key": "food-paid-side",
      "kind": "restaurant_estimate",
      "line_id": null,
      "menu_name": "추가 돈까스",
      "payload": null,
      "estimate": {
        "estimated": true,
        "confidence": 0.82,
        "nutrients": {
          "calories_kcal": 620,
          "protein_grams": 28,
          "carbs_grams": 82,
          "fat_grams": 18,
          "sodium_mg": 980,
          "saturated_fat_grams": 4,
          "sugars_grams": 9,
          "fiber_grams": 6,
          "added_sugars_grams": null,
          "trans_fat_grams": null,
          "cholesterol_mg": 110
        },
        "ranges": {},
        "provenance": {
          "calories_kcal": {
            "value_status": "estimated",
            "source_type": "food_image_estimate",
            "evidence_refs": [
              "food-photo-1"
            ]
          },
          "protein_grams": {
            "value_status": "estimated",
            "source_type": "food_image_estimate",
            "evidence_refs": [
              "food-photo-1"
            ]
          },
          "carbs_grams": {
            "value_status": "estimated",
            "source_type": "food_image_estimate",
            "evidence_refs": [
              "food-photo-1"
            ]
          },
          "fat_grams": {
            "value_status": "estimated",
            "source_type": "food_image_estimate",
            "evidence_refs": [
              "food-photo-1"
            ]
          },
          "sodium_mg": {
            "value_status": "estimated",
            "source_type": "food_image_estimate",
            "evidence_refs": [
              "food-photo-1"
            ]
          },
          "saturated_fat_grams": {
            "value_status": "estimated",
            "source_type": "food_image_estimate",
            "evidence_refs": [
              "food-photo-1"
            ]
          },
          "sugars_grams": {
            "value_status": "estimated",
            "source_type": "food_image_estimate",
            "evidence_refs": [
              "food-photo-1"
            ]
          }
        }
      }
    }
  ],
  "consumption": [],
  "classification_hints": {
    "cashos": {
      "category_hint": "food",
      "institution_hint": null,
      "payment_method_hint": "card"
    }
  },
  "links": [
    {
      "nutrition_client_key": "food-1",
      "purchase_line_key": "line-1",
      "purchase_record_client_key": "purchase-1"
    },
    {
      "purchase_record_client_key": "purchase-1",
      "purchase_line_key": "paid-side",
      "nutrition_client_key": "food-paid-side",
      "component_role": "side",
      "relation_type": "purchase_line"
    }
  ],
  "projection_targets": [
    "pricetrace_price_observation",
    "cashos_transaction",
    "fitness_nutrition"
  ],
  "review": {
    "status": "needs_review",
    "blocking_issues": [],
    "warnings": []
  }
}
```

## D. Structured option_items + parent nutrition

옵션은 실제 부모 내부에 남는다. nutrition은 부모 line을 참조하며 옵션 문구는 음식 영양 provenance가 아니다.

```json
{
  "schema_version": "yeonsik-ocr.v5",
  "mode": "restaurant_purchase",
  "source": {
    "producer": "chatgpt",
    "source_files": [
      {
        "id": "order-history-1",
        "type": "order_history",
        "label": "주문내역 캡처"
      },
      {
        "id": "payment-history-1",
        "type": "payment_history",
        "label": "결제내역 캡처"
      },
      {
        "id": "food-photo-1",
        "type": "food_photo",
        "label": "육회비빔밥 음식 사진"
      }
    ],
    "user_text": null
  },
  "merchant_candidate": null,
  "receipt": null,
  "product_candidates": [],
  "price_observations": [],
  "purchase_records": [
    {
      "client_key": "purchase-1",
      "platform": "예시 주문 플랫폼",
      "platform_code": null,
      "seller": "맛단-맛있는 다이어트식단",
      "seller_branch_name": "영등포점",
      "seller_source_namespace": null,
      "seller_source_code": null,
      "seller_business_kind": "food_service",
      "source_version": "chatgpt-restaurant-purchase-v5-example",
      "order_reference": "order-example-1",
      "purchase_kind": "restaurant",
      "ordered_on": "2026-09-11",
      "ordered_at": null,
      "paid_on": "2026-09-11",
      "paid_at": null,
      "status": "paid",
      "currency": "KRW",
      "totals": {
        "subtotal_amount_krw": 1100,
        "discount_amount_krw": 0,
        "shipping_amount_krw": 0,
        "tax_amount_krw": 0,
        "grand_total_amount_krw": 1100,
        "paid_amount_krw": 1100
      },
      "payment": {
        "method": "card",
        "provider": null,
        "status": "paid"
      },
      "line_items": [
        {
          "line_key": "line-1",
          "product_client_key": null,
          "description": "육회비빔밥",
          "seller_override": null,
          "option_text": null,
          "option_items": [
            {
              "option_key": "option-1",
              "description": "보통",
              "quantity": 1,
              "amount_krw": 0
            },
            {
              "option_key": "option-2",
              "description": "현미잡곡밥 180g (약275kcal → 순탄수 61g)",
              "quantity": 1,
              "amount_krw": 0
            },
            {
              "option_key": "option-3",
              "description": "리뷰이벤트 계란후라이 1개 추가 (순단백질7g)",
              "quantity": 1,
              "amount_krw": 0
            }
          ],
          "price_status": "itemized",
          "merchant_sku": null,
          "quantity": 2,
          "unit_price_amount_krw": 550,
          "gross_amount_krw": 1100,
          "discount_amount_krw": 0,
          "net_amount_krw": 1100
        }
      ],
      "evidence": [
        {
          "source_type": "order_history",
          "source_attachment_ids": [
            "order-history-1"
          ],
          "source_ref": "order-seller",
          "field": "seller",
          "observed_value": "맛단-맛있는 다이어트식단"
        },
        {
          "source_type": "order_history",
          "source_attachment_ids": [
            "order-history-1"
          ],
          "source_ref": "order-branch",
          "field": "seller_branch_name",
          "observed_value": "영등포점"
        },
        {
          "source_type": "order_history",
          "source_attachment_ids": [
            "order-history-1"
          ],
          "source_ref": "order-header",
          "field": "platform",
          "observed_value": "예시 주문 플랫폼"
        },
        {
          "source_type": "order_history",
          "source_attachment_ids": [
            "order-history-1"
          ],
          "source_ref": "order-total",
          "field": "grand_total_amount_krw",
          "observed_value": "1100"
        },
        {
          "source_type": "payment_history",
          "source_attachment_ids": [
            "payment-history-1"
          ],
          "source_ref": "payment-status",
          "field": "payment.status",
          "observed_value": "paid"
        }
      ],
      "confidence": 0.98
    }
  ],
  "nutrition": [
    {
      "client_key": "food-1",
      "kind": "restaurant_estimate",
      "line_id": null,
      "menu_name": "육회비빔밥",
      "payload": null,
      "estimate": {
        "estimated": true,
        "confidence": 0.82,
        "nutrients": {
          "calories_kcal": 620,
          "protein_grams": 28,
          "carbs_grams": 82,
          "fat_grams": 18,
          "sodium_mg": 980,
          "saturated_fat_grams": 4,
          "sugars_grams": 9,
          "fiber_grams": 6,
          "added_sugars_grams": null,
          "trans_fat_grams": null,
          "cholesterol_mg": 110
        },
        "ranges": {},
        "provenance": {
          "calories_kcal": {
            "value_status": "estimated",
            "source_type": "food_image_estimate",
            "evidence_refs": [
              "food-photo-1"
            ]
          },
          "protein_grams": {
            "value_status": "estimated",
            "source_type": "food_image_estimate",
            "evidence_refs": [
              "food-photo-1"
            ]
          },
          "carbs_grams": {
            "value_status": "estimated",
            "source_type": "food_image_estimate",
            "evidence_refs": [
              "food-photo-1"
            ]
          },
          "fat_grams": {
            "value_status": "estimated",
            "source_type": "food_image_estimate",
            "evidence_refs": [
              "food-photo-1"
            ]
          },
          "sodium_mg": {
            "value_status": "estimated",
            "source_type": "food_image_estimate",
            "evidence_refs": [
              "food-photo-1"
            ]
          },
          "saturated_fat_grams": {
            "value_status": "estimated",
            "source_type": "food_image_estimate",
            "evidence_refs": [
              "food-photo-1"
            ]
          },
          "sugars_grams": {
            "value_status": "estimated",
            "source_type": "food_image_estimate",
            "evidence_refs": [
              "food-photo-1"
            ]
          }
        }
      }
    }
  ],
  "consumption": [],
  "classification_hints": {
    "cashos": {
      "category_hint": "food",
      "institution_hint": null,
      "payment_method_hint": "card"
    }
  },
  "links": [
    {
      "nutrition_client_key": "food-1",
      "purchase_line_key": "line-1",
      "purchase_record_client_key": "purchase-1"
    }
  ],
  "projection_targets": [
    "pricetrace_price_observation",
    "cashos_transaction",
    "fitness_nutrition"
  ],
  "review": {
    "status": "needs_review",
    "blocking_issues": [],
    "warnings": []
  }
}
```

## E. Identity search/select outside canonical

이 canonical을 검수한 뒤 OCR runtime에서 식당·메뉴·플랫폼을 검색/선택한다. 서버가 반환한 UUID/query/choice는 metadata/checkpoint에만 저장한다. source 문자열과 canonical은 B와 동일하며 identity를 삽입하지 않는다.

```json
{
  "schema_version": "yeonsik-ocr.v5",
  "mode": "restaurant_purchase",
  "source": {
    "producer": "chatgpt",
    "source_files": [
      {
        "id": "order-history-1",
        "type": "order_history",
        "label": "주문내역 캡처"
      },
      {
        "id": "payment-history-1",
        "type": "payment_history",
        "label": "결제내역 캡처"
      },
      {
        "id": "food-photo-1",
        "type": "food_photo",
        "label": "오므라이스와 함께 제공된 국물 사진"
      }
    ],
    "user_text": null
  },
  "merchant_candidate": null,
  "receipt": null,
  "product_candidates": [],
  "price_observations": [],
  "purchase_records": [
    {
      "client_key": "purchase-1",
      "platform": "웨이티",
      "platform_code": null,
      "seller": "한성대 학식당",
      "seller_branch_name": null,
      "seller_source_namespace": null,
      "seller_source_code": null,
      "seller_business_kind": "food_service",
      "source_version": "chatgpt-restaurant-purchase-v5-example",
      "order_reference": "component-example-1",
      "purchase_kind": "restaurant",
      "ordered_on": "2026-10-14",
      "ordered_at": null,
      "paid_on": "2026-10-14",
      "paid_at": null,
      "status": "paid",
      "currency": "KRW",
      "totals": {
        "subtotal_amount_krw": 6000,
        "discount_amount_krw": 0,
        "shipping_amount_krw": 0,
        "tax_amount_krw": 0,
        "grand_total_amount_krw": 6000,
        "paid_amount_krw": 6000
      },
      "payment": {
        "method": "card",
        "provider": null,
        "status": "paid"
      },
      "line_items": [
        {
          "line_key": "line-1",
          "product_client_key": null,
          "description": "캐네디소시지오므라이스",
          "seller_override": null,
          "option_text": null,
          "option_items": [],
          "price_status": "itemized",
          "merchant_sku": null,
          "quantity": 1,
          "unit_price_amount_krw": 6000,
          "gross_amount_krw": 6000,
          "discount_amount_krw": 0,
          "net_amount_krw": 6000
        }
      ],
      "evidence": [
        {
          "source_type": "order_history",
          "source_attachment_ids": [
            "order-history-1"
          ],
          "source_ref": "order-seller",
          "field": "seller",
          "observed_value": "한성대 학식당"
        },
        {
          "source_type": "order_history",
          "source_attachment_ids": [
            "order-history-1"
          ],
          "source_ref": "order-header",
          "field": "platform",
          "observed_value": "웨이티"
        },
        {
          "source_type": "order_history",
          "source_attachment_ids": [
            "order-history-1"
          ],
          "source_ref": "order-total",
          "field": "grand_total_amount_krw",
          "observed_value": "6000"
        },
        {
          "source_type": "payment_history",
          "source_attachment_ids": [
            "payment-history-1"
          ],
          "source_ref": "payment-status",
          "field": "payment.status",
          "observed_value": "paid"
        }
      ],
      "confidence": 0.98
    }
  ],
  "nutrition": [
    {
      "client_key": "food-1",
      "kind": "restaurant_estimate",
      "line_id": null,
      "menu_name": "캐네디소시지오므라이스",
      "payload": null,
      "estimate": {
        "estimated": true,
        "confidence": 0.82,
        "nutrients": {
          "calories_kcal": 620,
          "protein_grams": 28,
          "carbs_grams": 82,
          "fat_grams": 18,
          "sodium_mg": 980,
          "saturated_fat_grams": 4,
          "sugars_grams": 9,
          "fiber_grams": 6,
          "added_sugars_grams": null,
          "trans_fat_grams": null,
          "cholesterol_mg": 110
        },
        "ranges": {},
        "provenance": {
          "calories_kcal": {
            "value_status": "estimated",
            "source_type": "food_image_estimate",
            "evidence_refs": [
              "food-photo-1"
            ]
          },
          "protein_grams": {
            "value_status": "estimated",
            "source_type": "food_image_estimate",
            "evidence_refs": [
              "food-photo-1"
            ]
          },
          "carbs_grams": {
            "value_status": "estimated",
            "source_type": "food_image_estimate",
            "evidence_refs": [
              "food-photo-1"
            ]
          },
          "fat_grams": {
            "value_status": "estimated",
            "source_type": "food_image_estimate",
            "evidence_refs": [
              "food-photo-1"
            ]
          },
          "sodium_mg": {
            "value_status": "estimated",
            "source_type": "food_image_estimate",
            "evidence_refs": [
              "food-photo-1"
            ]
          },
          "saturated_fat_grams": {
            "value_status": "estimated",
            "source_type": "food_image_estimate",
            "evidence_refs": [
              "food-photo-1"
            ]
          },
          "sugars_grams": {
            "value_status": "estimated",
            "source_type": "food_image_estimate",
            "evidence_refs": [
              "food-photo-1"
            ]
          }
        }
      }
    },
    {
      "client_key": "food-soup",
      "kind": "meal_component_estimate",
      "line_id": null,
      "menu_name": "국물",
      "payload": null,
      "estimate": {
        "estimated": true,
        "confidence": 0.82,
        "nutrients": {
          "calories_kcal": 30,
          "protein_grams": 2,
          "carbs_grams": 4,
          "fat_grams": 1,
          "sodium_mg": 400,
          "saturated_fat_grams": 0.2,
          "sugars_grams": 1,
          "fiber_grams": 0.5,
          "added_sugars_grams": null,
          "trans_fat_grams": null,
          "cholesterol_mg": 0
        },
        "ranges": {},
        "provenance": {
          "calories_kcal": {
            "value_status": "estimated",
            "source_type": "food_image_estimate",
            "evidence_refs": [
              "food-photo-1"
            ]
          },
          "protein_grams": {
            "value_status": "estimated",
            "source_type": "food_image_estimate",
            "evidence_refs": [
              "food-photo-1"
            ]
          },
          "carbs_grams": {
            "value_status": "estimated",
            "source_type": "food_image_estimate",
            "evidence_refs": [
              "food-photo-1"
            ]
          },
          "fat_grams": {
            "value_status": "estimated",
            "source_type": "food_image_estimate",
            "evidence_refs": [
              "food-photo-1"
            ]
          },
          "sodium_mg": {
            "value_status": "estimated",
            "source_type": "food_image_estimate",
            "evidence_refs": [
              "food-photo-1"
            ]
          },
          "saturated_fat_grams": {
            "value_status": "estimated",
            "source_type": "food_image_estimate",
            "evidence_refs": [
              "food-photo-1"
            ]
          },
          "sugars_grams": {
            "value_status": "estimated",
            "source_type": "food_image_estimate",
            "evidence_refs": [
              "food-photo-1"
            ]
          }
        }
      },
      "component_role": "complimentary_side"
    }
  ],
  "consumption": [],
  "classification_hints": {
    "cashos": {
      "category_hint": "food",
      "institution_hint": null,
      "payment_method_hint": "card"
    }
  },
  "links": [
    {
      "nutrition_client_key": "food-1",
      "purchase_line_key": "line-1",
      "purchase_record_client_key": "purchase-1"
    },
    {
      "purchase_record_client_key": "purchase-1",
      "purchase_line_key": "line-1",
      "nutrition_client_key": "food-soup",
      "component_role": "complimentary_side",
      "relation_type": "menu_component"
    }
  ],
  "projection_targets": [
    "pricetrace_price_observation",
    "cashos_transaction",
    "fitness_nutrition"
  ],
  "review": {
    "status": "needs_review",
    "blocking_issues": [],
    "warnings": []
  }
}
```
