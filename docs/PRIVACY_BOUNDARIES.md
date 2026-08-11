# 개인정보 및 보안 경계

## 저장 위치

- 원본/보정 이미지: 앱 전용 `filesDir/receipt-scanner/<document-id>/pages/`
- 검수 중 JSON: 앱 전용 `<document-id>/draft/receipt.json`
- 검증본과 manifest: 앱 전용 `<document-id>/exports/<revision>/`
- OCR 위치 디버그: 앱 전용 `<document-id>/ocr/` 및 export 내부 private artifact
- 세션, 페이지 metadata, 수정 이력: Room

`receipt.v2`에는 절대 파일 경로를 기록하지 않고 `source_images`에는 논리적 page ID만 저장합니다.

## 외부 공유

- 기본 공유 대상은 검증된 `receipt.json` 하나입니다.
- `document.source.raw_text`는 기본 `null`이며 사용자가 스위치를 켠 경우에만 포함합니다.
- 이미지, Room DB, manifest, `ocr-debug.json`은 Android 공유 Intent에 넣지 않습니다.
- `FileProvider`의 제한된 `files-path`와 일시적 read grant를 사용합니다.
- draft 또는 OCR-only 결과는 외부 공유할 수 없습니다.

## 선택적 Gemini 교정 요청

- 사용자가 `Gemini 교정 제안`을 명시적으로 누른 경우에만 Google Gemini Developer API로 직접 데이터가 나갑니다. 자동·백그라운드 요청은 없습니다.
- 요청에는 사용자 확인 전의 기존 OCR 상품 행 최대 12개, 연결된 비민감 OCR line 문자열과 line 주변 JPEG crop 최대 8개만 포함합니다.
- 전체 영수증 이미지, 판매처명·지점·주소·전화번호·사업자번호·구매시각·결제수단·카드·거래 식별 필드는 요청 계약에서 제외합니다. 다만 상품명·가격과 crop도 구매 정보입니다.
- 사업자번호·전화·카드·거래번호·주소 형태 line은 전송 전에 제외하지만 휴리스틱이므로 완전한 개인정보 탐지를 보장하지 않습니다.
- Interactions 요청은 `store=false`로 보냅니다. 이 설정은 Interactions 리소스 저장을 끄지만 서비스 tier별 데이터 사용 조건 전체를 대체하지 않습니다.
- AI는 새 행·삭제·합계·판매처를 바꿀 수 없습니다. 현재 값과 OCR source line에 연결된 허용 후보만 앱 검증을 통과하며 사용자가 하나씩 적용해야 합니다.
- 실제 구매 정보를 보내기 전에 현재 [Gemini 가격 및 데이터 사용 조건](https://ai.google.dev/gemini-api/docs/pricing)을 확인합니다.

## 로그와 키

- OCR 전체 텍스트, 이미지 경로, API key와 provider error body를 로그에 출력하지 않습니다.
- debug/release 모두 `OCR_DEBUG_LOGGING=false`입니다.
- Gemini API key는 앱 화면에서만 입력받고 빌드 파일·소스·문서·Git에 넣지 않습니다.
- 키는 Android Keystore의 AES-GCM key로 암호화한 ciphertext와 IV만 private SharedPreferences에 저장하며, 화면에서 현재 값을 다시 표시하지 않습니다.
- 요청 시 키를 복호화해 `x-goog-api-key` header에만 둡니다. request JSON과 correction provenance에는 포함하지 않습니다.
- 키 삭제는 ciphertext/IV와 Keystore alias를 함께 제거합니다.
- Android Keystore는 루팅·디버깅·런타임 계측 환경의 추출을 막는 production server boundary가 아닙니다. 직접 API 호출은 개인 사용으로 제한하며 공개 배포 시 backend proxy로 이동해야 합니다. [Gemini API key security](https://ai.google.dev/gemini-api/docs/api-key)
- PriceTrace/Supabase 업로드 구현이 없으므로 사용자 로그인 token은 저장하지 않습니다. 선택적 Gemini 요청 외의 영수증 원격 전송 경로는 없습니다.

## 삭제 일관성

세션 삭제는 먼저 상태를 `deleting`으로 표시하고 앱 전용 문서 디렉터리를 삭제한 뒤 Room metadata를 삭제합니다. 파일 삭제가 실패하면 metadata를 보존하고 `delete_files_failed`, metadata 삭제가 실패하면 `delete_metadata_failed`를 기록합니다. metadata가 이미 없더라도 남은 문서 디렉터리를 다시 정리합니다. 호출자는 `DeletionResult.isComplete`를 확인해야 합니다.

## 남은 보안 작업

- 화면 캡처 제한은 설정 옵션으로 계획되어 있으나 구현하지 않았습니다.
- Android Auto Backup은 application 수준에서 비활성화했지만 OEM/루팅 환경의 추출 방지는 주장하지 않습니다.
- 기기 분실 대응을 위한 biometric gate는 구현하지 않았습니다.
- 공개 배포 전 backend proxy, 사용자 인증, 사용자별 quota, API 제한, 비용 경보와 키 회전을 설계·검증해야 합니다.
- 실제 기기에서 네트워크 실패/취소, 키 저장·교체·삭제와 전송 crop 범위를 검증해야 합니다.
