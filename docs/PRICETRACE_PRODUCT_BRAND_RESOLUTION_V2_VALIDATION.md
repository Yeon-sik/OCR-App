# Brand Resolution V2 and candidate authority repair validation

Executed 2026-10-24 KST on `feat/pricetrace-brand-resolution`, starting at `3b5d58b6850f0338ba2a65e78c7a55373679320b`. Remote main remained `dc66647459740ec10ef7d8785e4fd2cd98df068b` at delivery preparation. PriceTrace V2 was independently read back before the live OCR smoke; its repair commit is `3dbdddc8dd024660be416973a26c1cb59d029e38`. Neither branch was merged.

## Repair behavior

The former aggregate reset erased accepted metadata for every Product Candidate. The reset now reconstructs only independently accepted rows matching logical key and exact sourceRevisionFingerprint, with a valid opaque non-UUID authority key. Prior successful legacy metadata must first have been annotated while the saved canonical source was still current. Unknown keys, changed/missing fingerprints, missing accepted identity and malformed authorities are dropped; conflicting duplicate representations are rejected. Remote Brand caches are excluded from authority preservation.

Unchanged A keeps its accepted legacy/current authority; edited B derives the existing deterministic ocrpc-r1 revision. Aggregate workflow status becomes pending. Brand/sub-brand intent remains candidate/field scoped and source edits invalidate only mismatching observed facts.

Private RPCs consume V2 exclusively. The strict read validates all fourteen fields, allowed relationship/status combinations, matching UUID equality, cardinality, source fields and fingerprint. Same UUID selections are explicit independent actions. Drift/inactive reads retain snapshots and emit semantic review required, non-retryable without correction; network failures remain retryable. A review-required read cannot complete the projection.

## Locally executed

Windows validation uses the existing ASCII temporary build copy because the Android build has path limitations with the Korean parent path. SHA-256 equality was checked for all 22 changed/new Kotlin sources, including the final instrumentation test. Build configuration and dependency files were unchanged. No local credentials or environment overrides were added to Git.

```text
:core:test
:app:testDebugUnitTest
:desktop-app:test
:receipt-scanner:testDebugUnitTest
:app:assembleDebug
:app:assembleDebugAndroidTest
:receipt-scanner:assembleDebugAndroidTest
:app:lintDebug
:receipt-scanner:lintDebug
```

All commands completed successfully using `gradlew.bat --no-daemon --no-parallel --console=plain`.

| Suite | Executed result |
| --- | --- |
| Core | 377 PASS, including explicitly enabled HTTP public-read/denial smoke |
| Android app JVM | 145 PASS |
| Receipt scanner JVM | 176 PASS |
| Desktop | 97 PASS, 3 existing remote integration tests skipped |
| Debug APK / both test APKs | PASS |
| App/scanner lint | PASS |
| Android Compose on emulator | 14 PASS (5 Brand-resolution, 9 linked-product) |
| Android Room on emulator | 3 PASS |

Skipped Desktop tests require separate remote fixture setup: `DesktopApprovedCheckpointRecoveryTest`, `DesktopApprovedReceiptNutritionLinkTest`, `DesktopCheckpointRemoteIntegrationTest`. They are not counted as passed.

The new Core multi-candidate tests first reproduced failure before the reset fix. They now prove legacy and ocrpc authority preservation, deterministic edited-B keys, transport failure/retry, and actual outgoing standalone, purchase-v4, Brand V2 mutation/read keys. Canonical serialization remains byte-equivalent before/after PT-only translation.

`MultiCandidateAuthorityPersistenceTest` constructs a new DesktopSessionStore; `MultiCandidateAuthorityRoomTest` closes and reopens a real file-backed Room DB. Both resume pending A/B metadata with a new gateway, submit using the preserved/new mixed keys, checkpoint accepted metadata, and issue downstream standalone requests. Existing Room migration and Brand sidecar tests also pass.

Android tests ran on `emulator-5556` with `adb install -r`; no application data clear/uninstall or physical device change occurred. A final whole-suite run exposed an existing linked-product manual-edit test interaction with soft-keyboard movement: the expected source update had not occurred after the tap, while the isolated test passed. The test now verifies typed text, closes the IME, and checks the visible/enabled confirmation button before tapping. Existing result/audit assertions remain unchanged. The entire 14-test suite then passed, followed by all 3 Room tests. Product edit behavior was not altered for this test repair.

## Compatibility and remote evidence

Seven canonical v1-v5 codec/model source files were compared to the starting feature commit and are unchanged. `PriceTraceCandidatePayload.revision`, sorting and source-builder bytes are unchanged. Logical ProductCandidate keys, Catalog/Standard Product identity and CashOS/Fitness references retain their meaning; all new evidence is integration/session metadata.

Real HTTP smoke: strict public Brand V1 search returned 41 rows; anonymous V2 mutation was denied with HTTP 401. No private V1 fallback exists. Authenticated production HTTP mutations were not attempted without a designated safe user-owned fixture. PriceTrace separately passed remote rolled-back role/claim set/read/same-brand/clear and owner/anon denial, and read back V1/ingest definition hashes, V2 signatures, RLS and grants.

PriceTrace's feature-commit CI passed: https://github.com/Yeon-sik/PriceTrace/actions/runs/39996319788 . OCR CI is separately observed after pushing this commit; these local results do not claim CI success in advance. Both branches remain unmerged for independent SOL post-implementation review.
