# Yeonsik Bundle

Status: INACTIVE_RUNTIME_CANDIDATE. 기존 Project 원본은 없다. V5 지원 문구를 반영한 transport 후보이며 구조/version 변경은 없다.

`.yeonsik`은 `canonical.json`, `manifest.json`, `evidence/<files>`를 담는 ZIP이다. canonical.json은 기존 yeonsik-ocr.v1/v2/v3/v4와 yeonsik-ocr.v5를 지원한다. V5 attachment-backed evidence type은 order_history, payment_history, food_photo다.

manifest exact shape는 그대로다. key를 추가하거나 bundle_version을 변경하지 않는다.

```json
{
  "bundle_version": "yeonsik-bundle.v1",
  "canonical_path": "canonical.json",
  "canonical_sha256": "lowercase 64 hex",
  "evidence": [
    {
      "source_file_id": "source id from canonical.json",
      "type": "food_photo",
      "path": "evidence/original.jpg",
      "sha256": "lowercase 64 hex",
      "mime_type": "image/jpeg",
      "byte_size": 12345,
      "original_filename": "original.jpg"
    }
  ]
}
```

Hash는 원본 byte의 SHA-256이며 sha256: prefix 없이 소문자 64 hex다. byte_size는 실제 byte 수다. source.source_files의 id/type과 manifest source_file_id/type은 정확히 1:1로 일치해야 한다. user_statement는 파일 바인딩을 만들지 않는다. 여러 source ID가 같은 path를 참조하면 hash/byte_size/MIME가 모두 같아야 한다. evidence byte를 변형하지 않는다.

manifest_sha256은 manifest.json의 정확한 UTF-8 byte hash다. bundle_fingerprint는 기존 canonical_sha256+manifest_sha256 규칙을 유지한다. 같은 canonical이라도 evidence/manifest가 다르면 별도 bundle이다. 원본 canonical/evidence/binding은 immutable insert-or-reuse이며 기존 파일을 덮어쓰거나 재바인딩하지 않는다.

최대 128 ZIP entries, evidence 1개 50 MiB, 총 uncompressed evidence 250 MiB 제한을 유지한다. traversal/absolute path, duplicate/undeclared entries, symbolic link, encrypted entries, ZIP64를 거부한다. canonical/manifest는 strict root entry다.

ZIP/manifest exact shape·hash·size·source binding → 로컬 evidence materialization → strict canonical parser/evidence gate → 기존 evidence archive/verification checkpoint 순서를 유지한다. archive 실패는 downstream 전송을 허용하지 않는다.

Evidence UUID, DB UUID, Storage URL은 canonical에 넣지 않는다. local source ID와 manifest binding만 사용한다. 이 문서의 변경 목적은 현재 V5 지원 사실을 명시하는 것이다.
