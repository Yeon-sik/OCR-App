# OCR-App | 원본 검수와 서비스별 projection을 연결하는 수집 콘솔

Android OCR 앱과 Compose Desktop 콘솔이 공통 Kotlin core로 영수증·영양·가격 evidence를 검증하고 서비스별로 전달한다. OCR은 검수 surface이며 identity·Nutrition·거래 authority는 각 도메인 서비스에 둔다.

| 항목 | 내용 |
| --- | --- |
| 문서 갱신 | 2026-10-30 (Asia/Seoul) |
| 기준 소스 | [main@dc66647](https://github.com/Yeon-sik/OCR-App/tree/dc66647459740ec10ef7d8785e4fd2cd98df068b) |
| 저장소 | [Yeon-sik/OCR-App](https://github.com/Yeon-sik/OCR-App) |
| 범위 | 2026-10-30 감사 시점에 병합된 main source와 저장소에 남은 검증 근거. 개발 branch·미커밋 작업은 제외. |

## 1. 30초 요약

OCR-App은 텍스트 추출 결과를 바로 진실로 저장하지 않고 원본 evidence→사람 검수→canonical identity→서비스별 projection을 단계적으로 연결한다.

- 10월 main에는 OCR V5 restaurant purchase/component/options 계약과 owner-confirmed recovery, PriceTrace exact identity 이후 Fitness/CashOS 전달 경로가 확장됐다.
- Android와 Windows reviewer는 purchase identity·linked product·canonical field를 검수하고 draft를 보존하며 bulk edit/confirmation/recovery UX를 제공한다.
- Nutrition publication은 검증된 evidence에서 블로그 draft·nutrition table·Naver package·clipboard/share artifact를 생성하는 별도 경로로 추가됐다.
- target별 checkpoint·idempotency와 unresolved identity 차단은 유지되어 부분 성공이 전체 uploaded 상태로 축약되지 않는다.

## 2. 문제와 해결

**문제**: OCR 텍스트·AI 보정만으로 구매 사실·UUID·섭취를 확정하면 오인식과 중복 저장이 연쇄된다. 여러 서비스의 부분 실패나 reviewer 중단을 하나의 완료 값으로 덮으면 재시도·복구도 잘못된다.

**해결**: 원본·추출 사실·사용자 수정·canonical revision·publication artifact를 분리한다. core가 version별 validate/plan하고 target별 checkpoint·exact identity·idempotency와 reviewer draft를 보존한다.

## 3. 핵심 기능과 결과

| 영역 | 현재 source에서 확인한 범위 |
| --- | --- |
| Android | ML Kit 스캔·한국어 OCR, source review, purchase identity/linked product 검수, Room session과 publication preview/share. |
| Desktop | Compose Desktop drag/drop 수집·검수, grid/bulk edit, confirmation, draft/recovery와 workspace UX. |
| 계약 | receipt.v2, yeonsik OCR V2~V5, restaurant purchase/component/options, .yeonsik bundle와 canonical delivery routing. |
| 서비스 전달 | PriceTrace exact identity, Fitness private import/publication, CashOS eligible receipt/transaction을 독립 checkpoint로 전달. |
| publication | 검증 evidence 기반 nutrition table·blog draft·Naver package·clipboard/share artifact 생성. |
| 복구 | owner artifact/parent 검증, legacy response/import selector 복구, draft·approved checkpoint 보존, 수정 이력 기반 오류 추적. |

## 4. 검증 현황

| 항목 | 상태 | 근거와 한계 |
| --- | --- | --- |
| 최신 main source | 저장소 검증 | `main@dc66647`까지 Android/Desktop/core, V5 계약, reviewer draft/recovery, publication source와 tests를 대조했다. |
| 이전 JVM CI | 통과 기록 | [JVM CI](https://github.com/Yeon-sik/OCR-App/actions/runs/37196081812): core:test, app:testDebugUnitTest, desktop-app:test. 최신 HEAD 전체를 새로 실행한 증거로 확대하지 않는다. |
| 실환경·정확도 | 미검증 | 최신 HEAD의 실문서 OCR 정확도, ML Kit 실기기, Gemini, 운영 RLS/PriceTrace/Fitness/CashOS, share target은 이번 갱신에서 재실행하지 않았다. |

## 5. 현재 한계와 다음 단계

- reviewer/test source 존재는 실제 업무용 문서 정확도나 UI 전체 흐름 완료를 자동으로 증명하지 않는다.
- 개인 credential 주입 APK를 공개 배포하려면 backend/credential 경계를 별도로 바꿔야 한다.
- 다음 우선 검증은 source capture→draft review→exact identity→target별 import/publication→앱 재시작 복구를 실제 기기·원격 환경에서 종단 확인하는 것이다.

## 6. 관련 문서

- [프로젝트 상세](./Project_Detail.md)
- [V5 restaurant purchase](YEONSIK_OCR_V5_RESTAURANT_PURCHASE.md)
- [Blog publishing](BLOG_PUBLISHING_V1.md)
- [README](../README.md)

Git Markdown이 원본이며 Notion은 생성 미러다. main 병합 후 설정된 project-docs 정책에 따라 검증·발행한다.
