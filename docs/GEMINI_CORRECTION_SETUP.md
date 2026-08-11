# Gemini AI 교정 제안 설정

## 현재 경계

`0.1.16`은 Firebase를 사용하지 않고 Gemini Developer API의 Interactions API를 직접 호출합니다. API 키는 앱의 교정 화면에서 사용자가 입력하며, 소스·Gradle·APK에 빌드 시점 비밀로 넣지 않습니다. 저장소 테스트와 APK 컴파일은 실제 키, 네트워크, 할당량 또는 OCR 정확도 향상을 증명하지 않습니다.

Gemini는 OCR 엔진의 대체물이 아닙니다.

```text
로컬 ML Kit OCR
  -> 규칙 파서 초안
  -> 사용자가 Gemini 요청
  -> 기존 상품 행의 교정 후보
  -> 앱 정책 재검증
  -> 사용자가 후보별 적용 또는 무시
  -> 기존 최종 검수
```

## API 키 연결

1. [Google AI Studio API 키 안내](https://ai.google.dev/gemini-api/docs/api-key)에서 본인 키를 준비합니다.
2. 앱에서 영수증의 `항목 검수` → `Gemini 교정 제안`으로 이동합니다.
3. `새 API 키`에 키를 입력하고 `API 키 저장`을 누릅니다.
4. 키가 저장된 뒤 `전송 범위를 확인하고 제안 요청`을 누릅니다.
5. 키를 회전하거나 사용을 중단할 때는 같은 화면의 `저장된 키 삭제`를 누릅니다.

키를 저장소, `local.properties`, Gradle, 문서, 스크린샷 또는 채팅에 붙여 넣지 않습니다. 앱은 키를 Android Keystore의 AES-GCM 키로 암호화해 private SharedPreferences에 저장하고 현재 값을 다시 표시하지 않습니다. 요청 시에만 복호화해 `x-goog-api-key` 헤더로 보냅니다.

이 저장은 평문 파일보다 안전하지만 공개 배포용 비밀 보호를 보장하지 않습니다. 직접 API를 호출하는 모바일 앱의 키는 루팅·디버깅·런타임 계측 환경에서 추출될 수 있습니다. 현재 방식은 소유자가 직접 쓰는 개인 앱 경계입니다. 다른 사용자에게 배포하려면 키를 앱에서 제거하고 인증·사용자별 제한·비용 한도가 있는 백엔드 프록시로 이동해야 합니다. Google도 client-side production 배포에서 키 노출을 금지하고 서버 측 호출을 권장합니다.

## 호출 계약

- endpoint: `POST https://generativelanguage.googleapis.com/v1beta/interactions`
- model: `gemini-3.5-flash-lite`
- authentication: `x-goog-api-key` HTTP header
- response format: JSON Schema가 지정된 `application/json`
- interaction storage: `store=false`
- mode: 동기식, non-streaming
- timeout: 앱 전체 45초, 연결 15초, read 45초
- 최대 응답 body: 1 MiB

Interactions 응답의 `completed` 상태와 `model_output` text만 읽습니다. HTTP 401/403, 429, 네트워크 오류, provider 오류, 완료되지 않은 상태와 형식 불일치는 서로 다른 안전한 사용자 메시지로 처리하며 provider 오류 body는 화면이나 로그에 노출하지 않습니다.

`store=false`는 Interactions 리소스 저장을 끄는 설정입니다. 그것만으로 서비스 tier별 데이터 사용 조건이 같아지는 것은 아니므로 실제 구매 정보를 보내기 전에 [현재 Gemini 가격과 데이터 사용 조건](https://ai.google.dev/gemini-api/docs/pricing)을 확인합니다.

## 전송되는 데이터

사용자가 요청 버튼을 누를 때만 다음을 전송합니다.

- OCR 근거가 있고 아직 사용자가 확인하지 않은 기존 상품 행 최대 12개
- 각 행의 상품명, 수량, 단가, 행 금액과 source line ID
- 사업자번호·전화·카드·거래번호·주소 형태 필터를 통과한 source line 문자열
- 각 source line bounding box 주변 JPEG crop 최대 8개, 최대 폭 1600px

전송 계약에서 제외되는 항목:

- 전체 영수증 이미지
- 판매처명, 지점, 주소, 전화번호, 사업자번호
- 구매시각, 결제수단, 카드·승인·거래 식별 정보
- 합계 필드와 사용자가 직접 추가한 행

이미지 inline payload는 원본 byte 기준 10 MiB에서 추가를 중단해 API의 전체 요청 한도 아래에 여유를 둡니다. 상품명·가격과 좁은 crop도 구매 정보입니다. 민감정보 필터는 휴리스틱이며 완전한 개인정보 탐지기가 아니므로 실제 기기에서 crop 범위를 육안 확인해야 합니다.

## 앱의 검증 규칙

Gemini는 다음 field path만 제안할 수 있습니다.

```text
line_items[<existing-line-id>].description
line_items[<existing-line-id>].quantity
line_items[<existing-line-id>].unit_price_amount_minor
line_items[<existing-line-id>].net_amount_minor
```

앱은 응답을 받은 뒤에도 다음을 다시 검사합니다.

- line ID와 field path가 현재 초안에 존재하는가
- `oldValue`가 현재 값과 같은가
- 모든 source line이 로컬 OCR에 존재하고 해당 상품 행에 연결되는가
- 같은 필드의 중복 제안이 없는가
- 수량과 금액 형식·범위가 유효한가
- 수량·단가·행 금액이 모두 있으면 곱셈 보존식이 정확히 맞는가

정책을 통과하지 못한 후보는 표시하지 않습니다. 통과 후보도 자동 적용하지 않으며 사용자가 원본과 근거를 보고 하나씩 적용합니다. 적용된 필드는 낮은 신뢰도로 남고 최종 `user_verified` 검수 조건을 우회하지 않습니다.

## 검증

저장소 검증:

```powershell
.\gradlew.bat test lint assembleDebug assembleDebugAndroidTest :app:compileReleaseKotlin --no-daemon
```

실환경에서는 별도로 다음을 확인해야 합니다.

- 유효한 개인 API 키 저장 → 앱 재시작 → 키 구성 상태 복원
- 실제 Gemini 제안 성공과 후보별 적용·무시
- 잘못된 키에서 인증 오류, quota 초과에서 한도 오류 표시
- 키 교체와 삭제 후 이전 키를 사용하지 않음
- 네트워크 없음·timeout·provider 오류에서 기존 초안이 변하지 않음
- 요청 중 다른 세션으로 이동했을 때 이전 응답이 현재 초안에 나타나지 않음
- 실제 영수증에서 crop이 상품 행 주변만 포함하는지 육안 확인
- 개인정보 제거 표본에서 규칙 파서 baseline 대비 필드 오류율과 검수 시간 비교

이 체크리스트 전에는 “Gemini 실연결 완료”, “무료 운영 보장” 또는 “OCR 정확도 향상”을 주장하지 않습니다.
