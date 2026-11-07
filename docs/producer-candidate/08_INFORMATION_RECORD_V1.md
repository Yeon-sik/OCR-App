# 범용 정보 구조화 작성 지침

출력 계약은 `contracts/information-record.v1.md`다. 기존 영수증/구매/영양 v1~v5 경로로 정확히 표현할 수 있는 정보는 기존 계약을 계속 사용한다. 새 자료 종류와 아직 전용 기능이 없는 정보는 이 검토함 계약으로 보존한다.

## 작성 순서

1. 원문 파일 또는 텍스트를 OCR 정보 보관함에 먼저 보관한다. 파일의 종류가 자동 인식 대상이 아니어도 원본 바이트와 SHA-256을 유지한다.
2. 원문에 실제로 있는 사실, 사용자의 진술, 추정/계산을 구별한다. 값이나 단위를 모르면 만들지 않는다. 미상을 0으로 쓰지 않는다.
3. 자료 전체에 앱 하나를 강제로 배정하지 말고, 사실의 소유자별로 기록을 나눈다.
4. 동일 원문을 사용한 기록은 같은 source_id/hash를 참조한다. 다른 앱이 소유한 사실은 `references`에 정확한 owner/record/project를 연결한다.
5. 필요한 발췌만 source.text에 넣는다. 전체 원본은 OCR에 남긴다. 다른 분야의 개인정보를 관련 없는 앱의 추출 필드에 복사하지 않는다.
6. 해당 앱 정보라는 확인을 받은 뒤 저장/내보내기한다. 수신 앱에서 다시 검토할 수 있으며 원장/식단/진단/공개 카탈로그에 자동 반영되지 않는다.

## 소유자 판단 예

| 원문에서 확인한 사실 | 소유 앱 | 잘못된 추론 방지 |
|---|---|---|
| 상품 이름·모델·제공자·제시 가격 | PriceTrace | 가격표만으로 개인 결제를 만들지 않음 |
| 실제 결제·환급·잔액·채무 조건 | CashOS | 상품 identity는 PriceTrace 참조 |
| 실제 먹은 양·운동·활동·체중 | Fitness | 구매했다는 이유만으로 섭취를 만들지 않음 |
| 영양제/약 복용·수면·증상·검사 관측 | HealthTrace | 검사 수치만으로 질환을 확정하지 않음 |
| 영양 조성 카탈로그 | 현재 Fitness Nutrition | PriceTrace는 제품 identity를 연결하여 조회 |
| 네 앱에 속하지 않는 지식/작업/관계 자료 | OCR 미분류 원문 | 맞지 않는 앱 이름을 억지로 선택하지 않음 |

예를 들어 영양제 구매 영수증은 PriceTrace 상품과 CashOS 결제의 근거가 될 수 있다. 실제 복용 정보가 없으면 HealthTrace 복용 기록은 만들 수 없다. 가격표와 결제 금액이 다르면 서로 다른 사실로 보존하고 일치하도록 수정하지 않는다.

## 값 표현

- decimal은 `"12345678901234567890.0010"`처럼 문자열로 쓴다. 숫자의 크기와 끝자리 0을 유지한다.
- 단위는 decimal의 unit으로 쓴다. 통화·기준량·횟수·기간처럼 의미를 구분하는 보조 사실은 별도 필드로 둔다.
- 시간대가 확인된 시각만 datetime으로 쓴다. 날짜만 있으면 date 필드를 쓰고 임의 시각이나 시간대를 추가하지 않는다.
- 서술·분류는 text, 참/거짓은 boolean, 구조가 필요한 목록/범위/복합 관계는 json을 사용한다.
- confidence는 추출 확신도일 뿐 사실의 진실성이나 의학적 확실성을 뜻하지 않는다. 추정·계산·자기 보고의 구분은 출처 설명과 별도 근거 필드에 명시한다.
- 파일 경로와 인증정보를 URI로 쓰지 않는다. 파일은 source_id/hash/name으로 참조한다.
- 파서가 지원하지 않는 파일을 실행하거나 임의 URL을 자동 조회하지 않는다.

## 예시: 서비스 관측

아래는 형식을 설명하는 가상 예시다. 실제 사용자 자료가 아니다.

```json
{
  "schema_version": "information-record.v1",
  "record_id": "bf665028-9ef5-44a5-99ab-f68703b9ff23",
  "owner_app": "pricetrace",
  "record_type": "pricetrace.service_observation",
  "title": "수선 서비스 안내문",
  "captured_at": "2026-11-06T12:00:00+09:00",
  "sources": [
    {"source_id": "notice-1", "kind": "text", "text": "기본 수선 12,000원부터, 소재에 따라 추가 비용"}
  ],
  "fields": [
    {"key": "service_name", "value_type": "text", "value": "기본 수선", "source_ids": ["notice-1"]},
    {"key": "starting_price", "value_type": "decimal", "value": "12000", "unit": "KRW", "source_ids": ["notice-1"]},
    {"key": "price_basis", "value_type": "text", "value": "시작 가격이며 소재에 따라 추가 비용", "source_ids": ["notice-1"]},
    {"key": "conditions", "value_type": "json", "value": {"material_surcharge": "unspecified", "payment_observed": false}}
  ],
  "references": []
}
```

이 기록은 관측 자료를 즉시 보관할 수 있지만, 표준 서비스 identity 확정이나 시장 가격 통계에 자동 게시하지 않는다. 사용자가 결제했다는 근거도 없다.

## 수정과 재전송

같은 ID와 동일 내용은 중복으로 처리한다. 같은 ID의 내용을 바꾸면 충돌로 거절된다. 수정할 때는 새 record_id를 발급하고 supersedes_record_id로 이전 기록을 연결한다. 원문을 수정해 과거 관측을 지우지 않는다. 각 앱의 검토 상태는 기록 본문과 별개이며 생산자가 검토 완료를 주장하는 필드를 넣지 않는다.

현재 제한: 파일당 512 MiB, 구조화 JSON 1 MiB, 중첩 object/array 64개. 더 큰 자료는 원본 파일을 나누거나 사실 단위 기록으로 나누되, 같은 source 식별자를 연결한다. 자동 추출이 지원되지 않는 미디어는 원문 보관과 수동 구조화를 사용한다.
