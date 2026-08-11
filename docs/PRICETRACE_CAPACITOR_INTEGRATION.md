# PriceTrace Capacitor Android 연결 설계

이 문서는 향후 연결 경계만 정의합니다. 현재 PriceTrace 저장소, Supabase, 원격 테이블, RPC를 수정하거나 호출하지 않습니다.

## 재사용 단위

`:receipt-scanner`를 AAR로 포함하고 Capacitor plugin은 다음 Android adapter 역할만 담당합니다.

1. Activity Result 수명주기와 `MlKitDocumentCaptureProvider` launch/result 연결
2. 문서 ID와 검수 화면 진입/복귀
3. 앱 전용 저장소 접근 권한과 사용자 로그인 context 전달
4. 검증된 `ReceiptV2`와 idempotency key를 서버 adapter에 전달

도메인 경계에는 ML Kit 결과 타입, Room entity, Android URI, Supabase SDK row 타입을 넣지 않습니다.

## 교체 가능한 계약

- `DocumentCaptureProvider`: byte page import의 순수 결과와 안정적 실패 상태
- `ReceiptOcrEngine`: 순수 `OcrDocument` 반환
- `ReceiptParser`: `ParserProfile` 교체 가능
- `ReceiptSessionRepository`: 세션/페이지/검수 이력 보존
- `ReceiptPublisher`: 서버 연결의 유일한 추상 경계

현재 `LocalOnlyReceiptPublisher`는 모든 호출에서 `local_only`만 반환하며 원격 상태를 주장하지 않습니다.

## ReceiptPublisher 연결 순서

```text
stageDocument(manifest, pages)
  -> private image upload 및 receipt_documents metadata
stageOcr(documentId, ocrLines)
  -> private OCR 초안, review_required 유지
사용자 검수 및 user_verified
finalizeVerifiedReceipt(documentId, receiptV2, idempotencyKey)
  -> 신뢰 가능한 단일 서버 RPC/endpoint
getPublicationStatus(documentId)
  -> retry 가능한 명시적 상태
```

클라이언트가 `receipts`, `receipt_items`, `price_observations`에 순차 insert하지 않습니다. 최종 확정은 하나의 서버 transaction에서 원자적으로 처리해야 합니다.

## 서버 측 필수 검증

- 인증 사용자와 private document 소유자 일치
- manifest page SHA-256, MIME, 크기, page ID와 업로드 객체 일치
- `receipt.v2` strict schema와 canonical revision 재계산
- idempotency key 중복 호출의 동일 결과
- `document.source.transcription_status == user_verified`
- 판매처/날짜/KRW/최종 합계/상품 필수값/보존식/source reference/reconciliation 재검증
- 클라이언트가 보낸 `catalog_namespace`, `merchant_id` 또는 표준 상품 링크를 신뢰하지 않음
- PriceTrace의 공개 projection에는 허용된 필드만 노출

## 표준 상품과 가격 관측 경계

- 이 앱은 상품명만으로 표준 상품을 연결하지 않습니다.
- 영수증에 인쇄된 코드만 `identifiers: [{"scheme":"merchant_sku", ...}]`로 보존합니다.
- OCR 초안은 `verified` 가격 관측을 만들 수 없습니다.
- 표준 상품 연결은 별도 서버/사용자 승인 절차가 책임집니다.

## 단계별 도입안

1. AAR public API와 Capacitor Activity Result bridge의 characterization test
2. 개발 환경의 사용자 로그인과 private signed upload
3. `stageDocument`/`stageOcr`를 staging project에서 검증
4. 검수 완료 단일 RPC와 중복 호출/rollback 테스트
5. owner isolation, RLS, 삭제, retry, 오프라인 큐 실제 검증
6. 공개 projection 검토 후 제한된 배포

각 단계는 이전 단계의 실패/부분 저장 복구가 검증된 뒤 진행합니다.
