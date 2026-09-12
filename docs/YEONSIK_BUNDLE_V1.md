# Yeonsik Bundle v1

`.yeonsik` is a ZIP archive that preserves a canonical OCR artifact and its original source evidence without adding evidence identities to the canonical schema.

## Layout

```text
canonical.json
manifest.json
evidence/<files>
```

`manifest.json` has exactly these keys:

```json
{
  "bundle_version": "yeonsik-bundle.v1",
  "canonical_path": "canonical.json",
  "canonical_sha256": "lowercase 64 hex",
  "evidence": [
    {
      "source_file_id": "source id from canonical.json",
      "type": "receipt",
      "path": "evidence/original.jpg",
      "sha256": "lowercase 64 hex",
      "mime_type": "image/jpeg",
      "byte_size": 12345,
      "original_filename": "original.jpg"
    }
  ]
}
```

Hashes cover exact bytes and never use a `sha256:` prefix. Every `source.source_files[]` item in `canonical.json` must match one manifest evidence item by both `id` and `type`. `user_statement` facts do not bind a file. Multiple source IDs may reuse one path only when its content hash, byte size, and MIME type agree. Evidence bytes must not be transformed.

## Import identity and immutability

`manifest_sha256` is the SHA-256 of the exact UTF-8 bytes of `manifest.json`. The bundle identity is the deterministic `bundle_fingerprint` derived from `canonical_sha256` and `manifest_sha256`; it is not the canonical hash alone. Therefore identical canonical JSON with different evidence, paths, or manifest metadata remains a separate bundle/import.

Canonical artifacts, evidence objects, and evidence bindings use immutable insert-or-reuse semantics. A conflict is read back and validated; existing canonical content, evidence content, or a binding is never updated or rebound. `original_filename` remains in the bundle manifest but is stored remotely as binding metadata, not as evidence-object identity. Storage paths are deterministic: `<owner-id>/<evidence-sha256>.<mime-extension>`.

## Safety limits

- At most 128 ZIP entries.
- At most 50 MiB per evidence file.
- At most 250 MiB total uncompressed evidence.
- Duplicate entries, traversal or absolute paths, symbolic links, encrypted entries, ZIP64, and undeclared evidence are rejected.
- `canonical.json` and `manifest.json` are strict root entries. Other root entries are rejected.

## Processing order

1. Validate ZIP structure, exact manifest shape, canonical/evidence hashes, sizes, and source id/type bindings.
2. Materialize original evidence into app-private local storage and create readable local evidence using `source_file_id` as `attachmentId`.
3. Run the existing canonical importer. During bundle import the canonical JSON editor and Parse action are read-only/disabled; opening a standalone JSON explicitly starts a new ingestion and never mutates the bundle session.
4. Authenticate to the separate Evidence Supabase, insert-or-reuse the canonical snapshot by bundle fingerprint, stream evidence blobs, and insert-or-reuse evidence objects and bindings. A single expired-session response may refresh the stored refresh token once and retry.
5. Only after every binding is archived may `SOURCE_EVIDENCE` verification succeed. Record the verification event, then use the existing projection planner and submitters.

Archive failure never triggers a downstream projection. The local bundle/session remains retryable; successful object uploads are reused by hash. Evidence archive retry and projection retry are separate operations.

Android persists the imported manifest, materialized evidence paths, archive checkpoint/status, and verification-event status with synchronous commits. On process recovery, an interrupted archive is surfaced as retryable after paths and hashes are revalidated.
