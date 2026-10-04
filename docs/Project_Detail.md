# OCR-App | Project Detail

2026-10-05 (Asia/Seoul) 갱신. Primary source boundary는 [main@60b0fba](https://github.com/Yeon-sik/OCR-App/tree/60b0fba1d4c8566ad7509294cf8bb276f9a2e010)이다. source 설명과 실제 검증 결과를 구분한다. [빠른 소개](./Project_Intro.md)를 참고한다.

## 1. 문서 목적과 범위

Android OCR 앱과 Compose Desktop 콘솔이 공통 Kotlin core로 영수증·영양·가격 evidence를 검증하고 서비스별로 전달한다. OCR은 검수 surface이며 identity·Nutrition·거래 authority는 각 도메인 서비스에 둔다.

원본·추출 사실·사용자 수정·canonical revision을 분리한다. core가 version별 validate/plan하고 target별 checkpoint·exact identity·idempotency를 보존하며 독립 commit을 재시도한다.

- 최근 main에는 V5 외식 Nutrition publication, unresolved identity 차단, Evidence owner/RLS repair, legacy publication checkpoint 복구와 exact identity 이후 review 상태 보존이 포함된다.

미병합 branch, 사용자 미커밋 작업과 명시하지 않은 운영 검증은 기능 완료 근거에 포함하지 않는다.

## 2. 시스템 아키텍처

```text
app -> core + receipt-scanner
receipt-scanner -> core (capture / OCR / parser / Room / review)
desktop-app -> core
core -> codec / evidence gate / validator / projection planner
  -> PriceTrace canonical gateway -> exact Product/Restaurant/Menu IDs
  -> Fitness import -> atomic publication / eligible meal
  -> CashOS eligible receipt/transaction
각 target의 checkpoint·retry·accepted facts를 독립 보존
```

## 3. 데이터 모델과 불변식

- OCR은 사람이 검수한 source evidence를 소유한다. AI에게 server UUID·identity authority·user_verified 권한을 부여하지 않는다.
- Android/Desktop는 core validator·planner를 공유한다. legacy codec은 version별 dispatch로 보존한다.
- revision archive는 JWT/local/remote owner와 artifact/parent를 확인하고 composite FK·RLS를 유지한다.
- line_id/sourceLineId 또는 opaque price client key로 Nutrition 행을 선택한다. 이름으로 UUID를 추정하지 않는다.
- private import만 성공한 상태를 publication 완료로 표시하지 않는다. pending·ambiguous·unverified identity는 fail closed 한다.
- accepted 금액·날짜·수량·evidence를 변경하면 같은 import를 replay하지 않고 revision conflict로 막는다.
- 서비스별 독립 commit과 동일 key·원본 selector를 보존한다. 섭취 근거가 없는 구매를 Meal로 만들지 않는다.
- 개인용 APK의 .env credential 주입을 공개 배포용 보안 설계로 주장하지 않는다. token·이미지·OCR 본문을 문서에 넣지 않는다.

## 4. 핵심 기술 의사결정

### 결정 1. 공통 core와 adapter

ML Kit/Android URI/Room과 Desktop UI를 core 계약에 넣지 않아 동일 검증을 유지한다.

### 결정 2. 검수와 authority 분리

정확한 서비스 response만 사용하고 unresolved 상태를 자동 승인하지 않는다.

### 결정 3. target별 durable checkpoint

응답 유실·legacy uploaded·publication 오류에서도 accepted import와 idempotency identity를 잃지 않는다.


## 5. 테스트와 검증 전략

| 검사 | 결과 | 근거·환경과 한계 |
| --- | --- | --- |
| 현재 main JVM CI | 통과 | [JVM CI](https://github.com/Yeon-sik/OCR-App/actions/runs/37196081812): core:test, app:testDebugUnitTest, desktop-app:test. |
| V5 audit 기록 | 저장소 문서 근거 | OCR_V5_PIPELINE_COMPLETION.md의 재현·수리·배포 기록은 해당 문서 증거 범위를 따른다. 원격 검증을 이번 갱신에서 재실행하지 않았다. |
| 실환경·정확도 | 이번 갱신에서 미실행 | 실문서 정확도·ML Kit 기기·Gemini·현재 RLS/publication·교차 앱·기기 UI는 unit CI로 보장하지 않는다. |

이번 문서 변경의 순차 검증 명령은 다음과 같다.

```text
node .github/project-docs/validate-project-docs.mjs --config project-docs.config.json --require-tracked
node .github/project-docs/sync-project-docs-to-notion.mjs --config project-docs.config.json
```

두 번째 명령은 render-only dry run이다. source·required sections·Git tracked links와 렌더링을 검증하며 Notion에 쓰지 않는다. 과거 테스트 수와 운영 상태를 현재 revision의 성공 수치로 재사용하지 않는다. 실제 기기·원격 권한·사용자 흐름은 표에 명시한 환경에서 따로 확인한다.

## 6. 배포·운영·복구

- unit gate는 core:test, app:testDebugUnitTest, desktop-app:test다. ML Kit UI·instrumentation·실계정 RLS는 따로 실행한다.
- 수집 원본·debug JSON은 private 경계에 두고 기본 공유는 검증된 JSON만 제공한다.
- accepted owner checkpoint와 기존 import selector를 재조회한다. 응답 유실 때문에 원본 영수증·canonical import를 새로 생성하지 않는다.

**문서 발행**: main에 병합한 뒤 workflow_dispatch에서 operation=publish와 정확한 PUBLISH 확인으로 발행한다. GitHub Environment는 notion-production이고 canonical branch는 main이다. 발행용 token과 page map은 Environment secret으로 관리하고 Git에 넣지 않는다. 신규 연결은 dedicated mirror를 만들고 본문 갱신은 설정된 GitHub Actions 정책을 따른다.

발행은 모든 페이지 preflight 뒤 configured Intro·Detail만 교체한다. 동일 source SHA·fingerprint면 skip하고 일부 실패는 같은 revision을 재실행해 수렴시킨다. 수동 메모와 원본 데이터는 미러 밖에 둔다.

## 7. 한계, 기술 부채, 다음 단계

- 수정 이력 오류율은 발견된 오류의 하한이다. 전사 정답이나 정확도 향상을 새로 측정한 결과로 표현하지 않는다.
- 개인 credential 주입 APK를 공개 배포하려면 backend/credential 경계를 별도로 바꿔야 한다.
- 다음 우선 작업은 approved source -> exact identity -> 기존 import -> publication -> 재시도를 기기·원격 환경에서 확인하는 것이다.

## 8. 근거와 관련 문서

- [기준 source revision](https://github.com/Yeon-sik/OCR-App/tree/60b0fba1d4c8566ad7509294cf8bb276f9a2e010)
- [Project Intro](./Project_Intro.md)
- [README](../README.md)
- [V5 audit](validation/OCR_V5_PIPELINE_COMPLETION.md)
- [privacy](PRIVACY_BOUNDARIES.md)
- [bundle 계약](YEONSIK_BUNDLE_V1.md)
