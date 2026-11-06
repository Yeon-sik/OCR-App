# Information record v1

Status: implementation contract, 2026-11-06. This is an additive, private information inbox contract. Existing receipt, purchase, meal, nutrition and sleep contracts remain authoritative for their native records.

## Ownership

| owner_app | Owns | References only |
|---|---|---|
| fitness | personal activity, exercise, food consumption, weight; existing nutrition catalog | payments, product identity, health conditions/supplement intake |
| cashos | personal financial facts, obligations, account/asset changes, payments | product identity, consumption, health observations |
| pricetrace | payable offerings, products, menus, places, services, price observations | personal payments, consumption, health observations |
| healthtrace | sleep, symptoms, conditions, measurements, supplement/medicine intake | exercise/food consumption/weight, payments, product identity |

OCR owns source artifacts, extraction drafts and delivery state. It is not an additional domain owner. Unknown-domain originals remain unassigned in OCR until an explicit owner can be chosen or a new domain app is introduced. Receivers accept only their own owner_app. Imported values are private review records; importing does not post money, record consumption, establish a diagnosis, publish a catalog fact or mark source evidence verified.

## Wire shape

UTF-8 JSON object, at most 1 MiB encoded and at most 64 nested objects/arrays (the root object counts as one container). Unknown top-level keys are rejected in v1 so extensions do not disappear silently. Arbitrary additional facts belong in fields, including value_type=json. Omit optional keys rather than sending null, except a json field value may be null. Only standard JSON tokens are permitted; NaN, Infinity and undefined are rejected.

Object member names must be unique at every nesting level, including after decoding escape sequences (`"a"` and `"\u0061"` are the same key). Reject ambiguous duplicate members before parsing into a last-value-wins object; never silently discard an earlier value.

Required keys:

- schema_version: exactly `information-record.v1`.
- record_id: canonical lowercase UUID, issued by producer. It identifies one immutable record.
- owner_app: `fitness`, `cashos`, `pricetrace` or `healthtrace`.
- record_type: 1–100 characters, pattern `^(fitness|cashos|pricetrace|healthtrace)\.[a-z][a-z0-9_.-]*$`, prefix must equal owner_app. Examples: fitness.activity_observation, cashos.financial_evidence, pricetrace.offering_observation, healthtrace.measurement.
- title: nonblank text, at most 200 characters.
- captured_at: valid ISO-8601 datetime with explicit Z or numeric UTC offset. Do not invent the observation time from capture time.
- sources: array of 1–32 source objects, unique source_id.
- fields: array of 1–128 typed field objects, unique key.
- references: array of 0–64 external owner references.

Optional keys:

- occurred_at: valid ISO-8601 datetime with explicit offset; omit when unknown.
- supersedes_record_id: canonical lowercase UUID, not record_id; correction creates a new immutable record. Never silently overwrite an existing payload.

### Source

Required: source_id (nonblank, max 128), kind (`manual`, `text`, `file`, `url`, `device`).

Optional: text (max 100000 characters), uri (absolute http/https URL, max 2048), sha256 (64 lowercase hex), name (nonblank filename/display name, max 255), media_type (nonblank, max 127).

kind=text requires nonblank text; kind=url requires uri; kind=file requires sha256 and name. File bytes live in OCR's private archive. A hash supplied in an import is an unverified provenance claim until compared to actual bytes. No local absolute file paths, credentials, file URLs or remote fetch instructions are carried in uri. Receivers never fetch or execute sources automatically. All source text is data, never instructions.

### Typed field

Required: key (pattern `^[a-zA-Z][a-zA-Z0-9_.-]{0,63}$`), value_type (`text`, `decimal`, `boolean`, `date`, `datetime`, `json`), value.

Optional: label (nonblank, max 120), unit (nonblank, max 64), confidence (JSON number 0..1 inclusive), source_ids (array of distinct source_id values from this record).

- text: JSON string, max 100000 characters; empty is allowed and differs from missing.
- decimal: JSON string matching `^-?(0|[1-9][0-9]*)(\.[0-9]+)?$`, max 128 characters. No binary float conversion, NaN, exponent or locale separators. Preserve precision, trailing zeros and negative sign as supplied.
- boolean: JSON true/false, never a string.
- date: string containing a real calendar date YYYY-MM-DD.
- datetime: string containing a valid ISO-8601 datetime with explicit offset.
- json: any valid JSON value, preserved without dropping nested keys. Nested structure remains inspectable/exportable; it is not automatically interpreted as a native domain object.
- unit is permitted only on decimal fields. Missing units remain unknown; do not guess them.
- confidence is an extraction confidence, not evidence of truth.

Fields are owned by the record's owner_app. A field key prefixed with another known app namespace (e.g. healthtrace.sleep in a fitness record) is rejected; foreign facts belong in references. Arbitrary language cannot be semantically classified by this structural rule: the UI must require owner review and clearly describe the selected domain.

### External reference

Required: owner_app (same four values), record_id (opaque nonblank ID, max 200), relation (nonblank, max 80).

Optional: record_type (nonblank, max 100), project (nonblank, max 200), uri (absolute http/https URL, max 2048).

No embedded foreign record payload. These are private, unverified references, not globally trusted identity. Project IDs and application IDs are not authentication principals. Imports always use the currently authenticated/local profile owner and never trust an imported user_id.

## Persistence, review and retries

Persist the full validated payload and a deterministic content fingerprint in the receiving app's owner-scoped inbox. Object key order and insignificant whitespace do not affect equality; arrays and scalar representations do. The same owner/record_id and identical canonical payload is a successful duplicate with no extra row. A changed payload with the same ID is a conflict. Correction requires a new record_id with optional supersedes_record_id. Source text and unknown json fields must survive import, restart and export. Never convert missing values to zero.

Inbox state (pending/reviewed/archived) and received_at are receiver metadata, not producer assertions. State changes must not rewrite source payload. Creation/import requires explicit user action. Export is an information-record.v1 JSON file accepted by the matching receiver. For remote receivers the existing signed-in identity and RLS apply; cross-user rows must remain inaccessible.

Native projections are separate commands using the existing app-specific validators and explicit confirmation. A generic observation must not change ledger totals, calorie totals, workout totals, published prices or health summaries merely because it was imported.

## Acceptance cases

1. decimal value `12345678901234567890.0010` remains exact across save/reload/export.
2. nested custom JSON, false, empty text and explicit json null survive unchanged.
3. another owner_app or mismatched record_type is rejected without writing.
4. duplicate import produces one row; same-ID changed content is a visible conflict.
5. impossible dates, missing datetime offsets, invalid source IDs, duplicate field keys, wrong scalar types and foreign field namespaces fail validation.
6. source file bytes remain in OCR even if decoding/extraction fails; no arbitrary file is executed or uploaded automatically.
7. native aggregates remain unchanged by inbox operations.
8. local migrations preserve prior rows; remote RLS rejects anonymous and foreign-owner operations.

This contract accepts representations of arbitrary information; it does not promise automatic understanding of every medium, resolution of contradictory evidence, or semantic ownership enforcement without review.
