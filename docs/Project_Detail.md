# OCR-App | Project Detail

2026-10-30 (Asia/Seoul) 갱신. Primary source boundary는 [main@dc66647](https://github.com/Yeon-sik/OCR-App/tree/dc66647459740ec10ef7d8785e4fd2cd98df068b)이다. source 설명과 실제 검증 결과를 구분한다. [빠른 소개](./Project_Intro.md)를 참고한다.

## 1. 문서 목적과 범위

Android OCR 앱과 Compose Desktop 콘솔이 공통 Kotlin core로 영수증·영양·가격 evidence를 검증하고 서비스별로 전달한다. OCR은 검수 surface이며 identity·Nutrition·거래 authority는 각 도메인 서비스에 둔다.

원본·추출 사실·사용자 수정·canonical revision·publication artifact를 분리한다. core가 version별 validate/plan하고 target별 checkpoint·exact identity·idempotency를 보존하며 Android/Desktop reviewer가 중단돼도 draft와 approved state를 복구할 수 있게 한다.

2026-10-05 이후 main에는 V5 restaurant purchase/component/options, owner-confirmed recovery, verified receipt product/nutrition linking, Windows reviewer grid·bulk edit·draft/recovery, Android linked-product review, Nutrition publication/blog package가 추가됐다.

미병합 branch, 사용자 미커밋 작업과 명시하지 않은 운영 검증은 기능 완료 근거에 포함하지 않는다.

## 2. 시스템 아키텍처

```text
Android app / Desktop collector
  -> common core codec / evidence gate / canonical review
  -> versioned OCR envelope V2~V5
  -> projection planner / target checkpoint
      -> PriceTrace canonical gateway -> exact Product/Brand/Restaurant/Menu IDs
      -> Fitness import -> eligible meal / nutrition publication
      -> CashOS eligible receipt/transaction
  -> publication adapter
      -> nutrition table / blog draft / Naver package / clipboard/share
reviewer state -> draft store / approved checkpoint / recovery
```

## 3. 데이터 모델과 불변식

- OCR은 사람이 검수한 source evidence를 소유한다. AI에게 server UUID·identity authority·`user_verified` 권한을 부여하지 않는다.
- Android/Desktop는 core validator·planner를 공유하며 UI별 draft/presentation state는 adapter 경계에 둔다.
- line/source identity와 purchase options/component parent를 보존한다. 이름만으로 product/menu/brand UUID를 추정하지 않는다.
- private import만 성공한 상태를 전체 publication 완료로 표시하지 않는다. pending·ambiguous·unverified identity는 fail closed 한다.
- accepted 금액·날짜·수량·evidence를 변경하면 같은 import를 replay하지 않고 revision conflict로 처리한다.
- target별 독립 commit과 idempotency key를 유지한다. 응답 유실 때문에 canonical record를 새로 생성하지 않는다.
- reviewer draft는 approved checkpoint와 구분한다. 앱/콘솔 재시작이 미승인 편집을 승인된 사실로 승격하지 않는다.
- publication text/image asset은 verified evidence에서 파생되며 원본 identity나 Nutrition authority를 대체하지 않는다.
- token·이미지 원본·OCR 개인 본문을 프로젝트 문서에 넣지 않는다.

## 4. 핵심 기술 의사결정

### 결정 1. 공통 core와 platform adapter
ML Kit/Android URI/Room과 Desktop UI를 core 계약에 넣지 않아 동일 검증 규칙을 유지한다.

### 결정 2. 검수와 authority 분리
정확한 downstream response만 canonical identity로 사용하고 unresolved 상태를 자동 승인하지 않는다.

### 결정 3. target별 durable checkpoint
PriceTrace/Fitness/CashOS 일부 실패와 응답 유실에서도 accepted state와 idempotency identity를 잃지 않는다.

### 결정 4. reviewer draft와 publication 분리
검수 중간 상태와 승인된 evidence, 승인 evidence에서 파생된 게시 artifact를 서로 다른 생명주기로 관리한다.

## 5. 테스트와 검증 전략

| 검사 | 결과 | 근거·환경과 한계 |
| --- | --- | --- |
| 최신 main source 감사 | 저장소 검증 | `main@dc66647`까지 core/Android/Desktop, V5 contracts, reviewer draft/recovery, publication source와 unit/instrumented tests를 확인했다. |
| 이전 JVM CI | 통과 기록 | [JVM CI](https://github.com/Yeon-sik/OCR-App/actions/runs/37196081812). 최신 HEAD 전체를 재실행한 증거로 사용하지 않는다. |
| 저장소 validation 문서 | 근거 존재 | 2026-10-11~23 options/component/Windows reviewer validation 문서가 있다. 각 문서가 기록한 revision·환경 범위만 인정한다. |
| 실환경·정확도 | 미검증 | 최신 HEAD의 실문서 OCR, ML Kit 기기, Gemini, 운영 RLS, PriceTrace/Fitness/CashOS와 Android share target은 별도 검증 대상이다. |

## 6. 배포·운영·복구

- unit/CI gate는 저장소의 현재 workflow를 따른다. ML Kit UI·instrumentation·실계정 RLS는 별도다.
- 수집 원본·debug JSON은 private 경계에 두고 기본 공유는 검증된 canonical JSON과 명시적 publication artifact만 제공한다.
- accepted owner checkpoint와 기존 import selector를 재조회한다. 응답 유실 때문에 원본 영수증·canonical import를 새로 생성하지 않는다.
- reviewer draft/recovery는 마지막 승인 상태와 분리하고 workspace 경계를 넘어 자동 합치지 않는다.

**문서 발행**: Git Markdown이 canonical이다. main 병합 후 project-docs workflow가 검증·발행하며 Notion은 read-only mirror다.

## 7. 한계, 기술 부채, 다음 단계

- 자동 테스트 수와 validation 문서가 실문서 정확도·실기기 OCR 성능을 대신하지 않는다.
- 개인 credential 주입 APK를 공개 배포용 구조로 일반화하지 않는다.
- 다음 우선 작업은 capture→review draft→exact identity→target별 import→publication→프로세스 재시작 복구를 실제 기기·원격 환경에서 종단 검증하는 것이다.

## 8. 근거와 관련 문서

- [기준 source revision](https://github.com/Yeon-sik/OCR-App/tree/dc66647459740ec10ef7d8785e4fd2cd98df068b)
- [Project Intro](./Project_Intro.md)
- [V5 restaurant purchase](YEONSIK_OCR_V5_RESTAURANT_PURCHASE.md)
- [Blog publishing](BLOG_PUBLISHING_V1.md)
- [Windows reviewer validation](validation/2026-10-23-windows-reviewer-ux.md)
- [README](../README.md)
