# OCR-App | 원본 검수와 서비스별 projection을 연결하는 수집 콘솔

Android OCR 앱과 Compose Desktop 콘솔이 공통 Kotlin core로 영수증·영양·가격 evidence를 검증하고 서비스별로 전달한다. OCR은 검수 surface이며 identity·Nutrition·거래 authority는 각 도메인 서비스에 둔다.

| 항목 | 내용 |
| --- | --- |
| 문서 갱신 | 2026-10-05 (Asia/Seoul) |
| 기준 소스 | [main@60b0fba](https://github.com/Yeon-sik/OCR-App/tree/60b0fba1d4c8566ad7509294cf8bb276f9a2e010) |
| 저장소 | [Yeon-sik/OCR-App](https://github.com/Yeon-sik/OCR-App) |
| 범위 | 병합된 main의 source와 명시한 검증 근거. 개발 branch·미커밋 작업은 제외. |

## 1. 30초 요약

Android OCR 앱과 Compose Desktop 콘솔이 공통 Kotlin core로 영수증·영양·가격 evidence를 검증하고 서비스별로 전달한다. OCR은 검수 surface이며 identity·Nutrition·거래 authority는 각 도메인 서비스에 둔다.

- 최근 main에는 V5 외식 Nutrition publication, unresolved identity 차단, Evidence owner/RLS repair, legacy publication checkpoint 복구와 exact identity 이후 review 상태 보존이 포함된다.

## 2. 문제와 해결

**문제**: OCR 텍스트·AI 보정만으로 구매 사실·UUID·소비를 확정하면 오인식과 중복 저장이 연쇄된다. 여러 서비스의 부분 실패를 하나의 uploaded 값으로 덮으면 재시도도 잘못된다.

**해결**: 원본·추출 사실·사용자 수정·canonical revision을 분리한다. core가 version별 validate/plan하고 target별 checkpoint·exact identity·idempotency를 보존하며 독립 commit을 재시도한다.

## 3. 핵심 기능과 결과

| 영역 | 현재 source에서 확인한 범위 |
| --- | --- |
| Android | ML Kit 스캔·한국어 OCR, bounding-box 검수·행·합계 수정 이력, Room session과 앱 전용 파일. |
| Desktop | Compose Desktop 수집·검수·전송. Android와 같은 core validator·planner. |
| 계약 | receipt.v2, version별 yeonsik OCR envelope, .yeonsik evidence, 라벨·추정·external reference·단독 가격의 별도 dispatch. |
| V5 완료 | PriceTrace exact identity 후 Fitness private canonical import와 atomic dining-out publication까지 완료해야 Nutrition uploaded. |
| 복구·평가 | owner artifact/parent 검증, legacy response·import selector 복구, 승인 fingerprint에 따른 READY 복원, 수정 이력 기반 오류율. |

## 4. 검증 현황

| 항목 | 상태 | 근거와 한계 |
| --- | --- | --- |
| 현재 main JVM CI | 통과 | [JVM CI](https://github.com/Yeon-sik/OCR-App/actions/runs/37196081812): core:test, app:testDebugUnitTest, desktop-app:test. |
| V5 audit 기록 | 저장소 문서 근거 | OCR_V5_PIPELINE_COMPLETION.md의 재현·수리·배포 기록은 해당 문서 증거 범위를 따른다. 원격 검증을 이번 갱신에서 재실행하지 않았다. |
| 실환경·정확도 | 이번 갱신에서 미실행 | 실문서 정확도·ML Kit 기기·Gemini·현재 RLS/publication·교차 앱·기기 UI는 unit CI로 보장하지 않는다. |

위 결과는 연결한 기준 source revision의 증거다. 이번 변경은 문서·게시 설정만 갱신하며 제품 runtime을 새로 검증한 작업으로 설명하지 않는다. 문서 validator, tracked path·link 검사와 Notion render-only dry run을 수행한다. 병합 뒤 반영은 별도 게시 workflow와 source fingerprint로 확인한다.

## 5. 현재 한계와 다음 단계

- 수정 이력 오류율은 발견된 오류의 하한이다. 전사 정답이나 정확도 향상을 새로 측정한 결과로 표현하지 않는다.
- 개인 credential 주입 APK를 공개 배포하려면 backend/credential 경계를 별도로 바꿔야 한다.
- 다음 우선 작업은 approved source -> exact identity -> 기존 import -> publication -> 재시도를 기기·원격 환경에서 확인하는 것이다.

## 6. 관련 문서

- [프로젝트 상세](./Project_Detail.md)
- [README](../README.md)

Git Markdown이 원본이며 Notion은 생성 미러다. main에 병합한 뒤 workflow_dispatch에서 operation=publish와 정확한 PUBLISH 확인으로 발행한다. 개인 원본과 인증 정보는 게시하지 않는다.
