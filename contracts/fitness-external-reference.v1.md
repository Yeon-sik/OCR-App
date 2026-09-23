# `external-reference.v1` cross-repo contract

This is the normative contract note shared by OCR-App and FitnessApp. The
request fixture in `fitness-external-reference.v1.json` is the exact RPC body
used by both repositories' tests.

| Layer | Fixed schema/version |
| --- | --- |
| OCR nutrition draft | `fitness-nutrition-draft.v1` |
| OCR parser/source | `external-nutrition-lookup.v1` |
| Fitness RPC input | `external-reference.v1` via `import_canonical_nutrition_v3` |
| Fitness read | `nutrition-read.v3` |

The RPC request keeps the existing nineteen named arguments. The external
branch requires:

- `p_input_contract=external-reference.v1`.
- `p_source_document_ref` and `p_provenance.source_reference` set to the same
  exact public `http`/`https` nutrition URL.
- `external_reference` as the top-level and every nutrient provenance source
  type.
- `external-nutrition-lookup.v1` as parser/source version.
- Observed values for exactly seven required nutrients: `calories_kcal`,
  `protein_grams`, `carbs_grams`, `fat_grams`, `sodium_mg`,
  `saturated_fat_grams`, and `sugars_grams`.
- `estimated=false`, with null `p_estimation_evidence` and
  `p_pricetrace_identity`. Server-generated UUIDs are output identity only;
  client input never becomes PriceTrace authority.

Fitness stores and exposes `source_type=external_reference`, the exact source
URL, and `external-nutrition-lookup.v1` through the private Nutrition read.
This is published/reference nutrition, not `product_label_ocr` and not a
restaurant or food-photo estimate. Existing `nutrition-label.v1` and
`food-estimate.v1` contracts remain separate.
