# PriceTrace Product Candidate Brand resolution V2 consumer

This integration document keeps its original filename. Private resolution calls now use V2; deployed PT V1 remains unchanged.

PriceTrace owns the canonical Brand graph. OCR stores observed `brand`, `sub_brand` and manufacturer facts, plus an explicit user-selection sidecar. A selection does not verify a public product or change a Brand master.

## Source identity and wire translation

`ProductCandidate.clientKey` remains the logical OCR relation key. Canonical `yeonsik-ocr.v1` through `v5`, purchase/nutrition links, CashOS and Fitness keep their existing serialization and meaning. No PT Brand IDs are added to these envelopes.

`PriceTraceCandidatePayload.source(candidate)` is the single builder for the sanitized PT candidate source. It contains exactly the existing transmitted fields except outgoing `client_key`, including normalized evidence and provenance. The revision material is UTF-8 JSON with recursively sorted object keys:

```json
{"logical_client_key":"<OCR logical key>","source":{"<exact PT source fields>":"..."}}
```

`sourceRevisionFingerprint = SHA256(material)` and `authorityClientKey = "ocrpc-r1-" + sourceRevisionFingerprint`. Arrays retain their source order. The hash never contains the derived authority key. Brand selection sidecars do not participate.

Only the PT gateway emits this authority key as `p_candidate.client_key`. Candidate idempotency is SHA-256 of `projectionIdempotencyKey + "|candidate=" + authorityClientKey`. PT ingest v1 still rejects changing a payload under an existing key. The local hash and PT's server `request_fingerprint` are different hashes with different ownership.

Successful, current legacy projection metadata retains its already accepted authority key. Changing source facts resets aggregate workflow status, but retains accepted evidence independently for every logical candidate whose exact source revision is unchanged. Only the changed candidate loses its previous authority. New metadata also records the source hash and rejects stale mappings. Echoed keys must be strings equal to the transmitted authority key before metadata is translated back to logical keys.

When restoring an unchanged canonical source, Core adds a missing exact-source hash to successful legacy metadata without changing its accepted key. This supplements the older artifact fingerprints, which can sort evidence arrays. Source-hash changes invalidate the affected approvals and projections even when those older fingerprints compare equal; already stale uploaded metadata is blocked during restore.

The same effective mapping is used only in PT standalone `product.product_client_key` and purchase-v4 `items[].product.client_key`. The canonical source objects are unchanged. Metadata preserves `priceTraceAuthorityClientKey` separately from logical `clientKey` / `productClientKey`.

## Selection and persistence

Shared Core models distinguish `UNSET`, `SELECTED` and `CLEAR`. The durable draft identifies logical candidate key, source field and trimmed observed value, and optionally stores canonical ID/name/parent display snapshots. These snapshots are not a local Brand master.

Android's Room database v14 adds `brand_resolution_drafts_json TEXT NOT NULL DEFAULT '[]'` to ingestion sessions. Migration 13→14 preserves existing rows. Desktop stores the same sidecar under session `brand_resolution_drafts`; older files load an empty list. Canonical JSON remains separate.

Android and Desktop use the same Core client and handoff. Each nonblank raw Brand/sub-brand has explicit search/change/clear controls. Search starts from the raw text, debounces, cancels obsolete queries, shows canonical and immediate-parent context and alias matches, and never auto-selects. Missing connection, loading, empty, error and retry states preserve ordinary fact review. Manufacturer is independent.

Editing a raw Brand/sub-brand drops its stale draft in both canonical revision and invalidation paths. Returning the raw text to its old value does not restore the discarded selection. Other still-matching fields remain intact.

## Submission and recovery

1. Submit the unchanged fact-only ingest-v1 shape using the effective revision authority key.
2. Apply explicit clear commands first, then Brand and sub-brand selections.
3. Read `get_product_candidate_brand_resolutions_v2` and validate the exact authority key, observed fields, one current server fingerprint and requested selections.
4. Persist the combined product projection metadata. Brand rows cannot become CatalogProduct identity.

No-selection submissions issue only ingest calls. A partial mutation/read failure does not report projection success; accepted candidate metadata is preserved for retry, including when ingest itself fails or recovery detects a conflicting selection. Retry uses the same authority and candidate idempotency keys, replays idempotent selections and reads the server-owned current state. Network/auth failures retain existing private gateway refresh/retry behavior; hierarchy/contract failures require review. Public Brand discovery always uses the anonymous public connection, so an expired signed-in session cannot block it. A distinct-UUID hierarchy conflict is corrected by selecting another Brand, explicitly clearing a selection or editing the raw fact. OCR never reparents Brands.

The private mutation is `set_product_candidate_brand_resolution_v2(p_client_key text, p_source_field text, p_observed_value text, p_brand_id uuid, p_user_verified boolean) RETURNS jsonb`. Null ID explicitly clears. The read RPC accepts `p_client_key text` and returns current owner-only resolutions, including the server fingerprint and archived-ID state. Public discovery uses the existing strict `get_public_brand_catalog_v1` contract; anonymous discovery sends the publishable `apikey` without a blank Bearer header.

## Validation and limits

The tests cover deterministic revision identity, legacy reuse and invalidation, strict search/response parsing, PT-only purchase/standalone translation, selection persistence, partial retry, clear/conflict behavior, query cancellation, raw edit invalidation and unchanged canonical JSON. Android Compose and Room tests run on an emulator; Desktop Compose tests run on the JVM.

Optional live read/denial smoke:

```powershell
# Supply existing public connection values as process environment variables; never commit keys.
$env:PT_BRAND_SMOKE_URL = '<existing PriceTrace URL>'
$env:PT_BRAND_SMOKE_KEY = '<existing publishable key>'
.\gradlew.bat :core:test --tests '*PriceTraceBrandLiveSmokeTest'
```

This test only searches public Brands and verifies anonymous mutation denial. Without these variables it is skipped. It never submits a real candidate. Authenticated production mutation requires an existing safe user-owned test candidate; role/claim rollback probes are distinct from real user JWT evidence.

On Windows, the current Android plugin/JVM tests have path limitations with this Korean-named parent checkout. Validation used an ASCII temporary build copy with SHA-256 comparison of every changed source file, the exact tracked build files, and the existing ignored local environment. No path override or credentials are part of the delivered change.

## Candidate-scoped recovery and V2 semantics

A projection reset keeps only rows with an explicit current logical key, identical `sourceRevisionFingerprint`, structurally valid authority key and accepted candidate/catalog identity. It rebuilds `responses`/`products` without stale remote Brand rows. Duplicate authority representations must agree. Missing fingerprints are not invented during reset: only restoration of an unchanged, previously uploaded legacy projection may annotate them first.

Editing B sets the aggregate projection to PENDING while retaining unchanged A's accepted authority. This applies to legacy logical authorities and `ocrpc-r1-*` authorities. B derives the new revision deterministically; retries and durable session/Room reloads use the same mixed map. Existing source hashing, immutable PT keys and canonical v1-v5 are unchanged.

V2 read retains the eleven V1 fields and appends `pair_relationship`, `pair_relationship_valid`, `requires_review`. The strict parser validates types, field cardinality, UUID equality, revision consistency and relation/review combinations. `single`, `same_brand`, `descendant` are valid; `hierarchy_conflict` is invalid. Review is required exactly when the row's Brand is inactive or the pair is invalid. OCR trusts the server's arbitrary-depth graph evaluation; a direct parent is display context only.

Both fields may explicitly select the same UUID. Android/Desktop display that fact while keeping both raw observations. Later graph drift and archival preserve local historical snapshots with change/clear controls. A review-required read produces a semantic, non-retryable review state and cannot mark the projection fully complete. A later explicit retry may succeed after user action or a corrected server graph. Network failures remain retryable. There is no private V1 fallback when V2 is unavailable; public Brand search remains V1.

Regression coverage includes mixed legacy/current authority keys in actual standalone, purchase-v4 and Brand V2 request bodies, partial failure/retry, Desktop store reconstruction, Room close/reopen, explicit same-Brand UI selections and non-destructive drift/archival warnings. SQL and remote deployment evidence is maintained in PriceTrace's V2 contract validation report.
