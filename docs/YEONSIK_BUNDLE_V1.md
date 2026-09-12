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

## Safety limits

- At most 128 ZIP entries.
- At most 50 MiB per evidence file.
- At most 250 MiB total uncompressed evidence.
- Duplicate entries, traversal or absolute paths, symbolic links, encrypted entries, ZIP64, and undeclared evidence are rejected.
- `canonical.json` and `manifest.json` are strict root entries. Other root entries are rejected.

## Processing order

1. Validate ZIP structure, exact manifest shape, canonical/evidence hashes, sizes, and source id/type bindings.
2. Materialize original evidence into app-private local storage and create readable local evidence using `source_file_id` as `attachmentId`.
3. Run the existing canonical importer.
4. Authenticate to the separate Evidence Supabase, upsert the canonical snapshot, archive blobs idempotently by owner/hash, and upsert evidence objects and bindings.
5. Only after every binding is archived may `SOURCE_EVIDENCE` verification succeed. Record the verification event, then use the existing projection planner and submitters.

Archive failure never triggers a downstream projection. The local bundle/session remains retryable; successful object uploads are reused by hash. Evidence archive retry and projection retry are separate operations.
