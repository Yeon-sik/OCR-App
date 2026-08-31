# yeonsik-ocr.v1

`ExternalJsonImporter` now accepts `receipt.v2`, `fitness-nutrition-draft.v1`, and this envelope. The two legacy contracts remain unchanged. The envelope is a local draft: `review.status` and every server/trust field from the producer are not proof of verification.

## Canonical schema

```json
{
  "$schema": "https://json-schema.org/draft/2020-12/schema",
  "type": "object",
  "required": ["schema_version", "mode", "source", "merchant_candidate", "receipt", "nutrition", "classification_hints", "links", "review"],
  "additionalProperties": false,
  "properties": {
    "schema_version": {"const": "yeonsik-ocr.v1"},
    "mode": {"enum": ["merchant", "restaurant", "packaged_product"]},
    "source": {"$ref": "#/$defs/source"},
    "merchant_candidate": {"anyOf": [{"$ref": "#/$defs/merchant"}, {"type": "null"}]},
    "receipt": {"description": "Exact nested receipt.v2 object", "anyOf": [{"$ref": "receipt.v2"}, {"type": "null"}]},
    "nutrition": {"type": "array", "items": {"$ref": "#/$defs/nutrition"}},
    "classification_hints": {"type": "object", "required": ["cashos"], "additionalProperties": false},
    "links": {"type": "array", "items": {"$ref": "#/$defs/link"}},
    "review": {"$ref": "#/$defs/review"}
  }
}
```

`source_files` contains logical producer references only; local file paths are never imported. A `product_label` nutrition item contains the exact `fitness-nutrition-draft.v1` object in `payload`. A `restaurant_estimate` contains `line_id`, `menu_name`, `estimated: true`, confidence, nutrient values, and optional min/point/max ranges. Restaurant links join `receipt_line_id` to `nutrition_client_key`.

The importer sanitizes receipt IDs/status/source images and nutrition IDs/status/confirmation. The app creates its own fingerprint and local ID. Server IDs, owner IDs, publisher status, revisions, remote receipt IDs, and `user_verified` are never accepted as trust.

## Projection state machine

```text
disabled                         (mode does not need this authority)
pending -> ready -> user_verified -> submitted
                             \-> failed -> submitted  (retry only this projection)
```

Each projection has its own remote ID, attempt count, error, and deterministic idempotency key. There is no cross-database transaction. A successful projection remains `submitted` when another projection fails.

## Call order

1. Import and strict-decode the envelope; persist the sanitized envelope locally.
2. Attach readable local pages and classify them as receipt, nutrition label, food photo, or menu photo.
3. Run the existing `VerifiedDraftGate`; restaurant requires a readable receipt and food photo, packaged product requires a readable nutrition label.
4. User reviews and verifies the relevant section.
5. For each enabled projection, perform live authority lookup and show ambiguous/not-found candidates.
6. Build the minimum server payload from resolved IDs, calculate the local idempotency key, and submit that projection.

PriceTrace owns store/restaurant/location/menu/catalog identity, Fitness owns food/product identity, and CashOS owns category/account identity. The envelope never supplies an authoritative server UUID.

## Failure and retry

Identity ambiguity is a user-resolution failure and stays failed until the user selects a candidate. Network/server failures retain the local session and increment only that projection's attempt count. Retry recomputes the same canonical key and does not resubmit already-submitted projections.

## Verification boundary

Unit coverage includes legacy import, all three envelope modes, trust downgrading, evidence blocking, isolated projection failure/retry, and deterministic fingerprints. Room instrumentation covers projection/evidence restoration after repository recreation. Manual work still required: real ChatGPT Project file download, camera/gallery attachment to every evidence type, live PriceTrace/Fitness/CashOS identity candidate selection, authenticated submissions, and physical-device UI/E2E.
