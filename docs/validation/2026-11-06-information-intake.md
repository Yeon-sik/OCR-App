# 통합 정보 수용 검증 기록

기준일 2026-11-06. 모든 변경은 `feat/universal-information-intake-20261106` 기능 worktree에서 진행했다. 원본 checkout의 기존 변경을 보존했다. 로컬 커밋과 빌드는 완료했으며 원격 push, 운영 웹 배포, 물리 기기 설치는 수행하지 않았다.

## 구현된 사용 흐름

1. OCR 정보 보관함에서 임의 파일 또는 텍스트를 보관한다. 원본은 SHA-256으로 검증하며, 지원하지 않는 형식이나 잘못된 JSON도 원문은 남긴다.
2. 소유 앱을 정하고 텍스트·십진수·단위·날짜·시각·참/거짓·복합 JSON으로 구조화한다. 네 앱의 범위 밖 자료는 미분류 원문으로 보관한다.
3. 소유 범위를 확인한 뒤 `information-record.v1` 파일로 내보내고 각 앱의 정보/자료함에서 가져온다. 현재 계정 또는 로컬 프로필에 저장된다.
4. 같은 ID/같은 내용은 중복 처리하고 다른 내용은 충돌로 거절한다. 정정은 새 ID와 이전 기록 참조로 남긴다. 검토 상태는 본문과 따로 관리한다.

공통 전달 경로는 파일이다. OCR의 기존 전용 전송 기능은 유지하며 새 generic 기록의 네 앱 자동 전송 큐는 아직 구현하지 않았다. 원문 전체는 OCR에 남고 수신 앱에는 선택한 발췌·해시·참조가 전달된다. 새 검토함 기록은 기존 원장·칼로리·운동·수면/복용 집계·공개 상품 카탈로그에 자동 합산하지 않는다.

## 공통 계약

- owner 정상 4개/오류 25개 JSON 사례와 원문 정상 4개/중복 키 오류 16개를 공유한다.
- 정밀 decimal 문자열, JSON 큰 숫자와 끝자리 0, false/null/빈 문자열, 배열 순서, 출처·외부 참조, 계정 경계, 깊이/크기 제한을 검사한다.
- 중복 객체 키는 최상위·중첩·배열 내부·Unicode escape 동등 이름 모두 DOM 변환 전에 거절한다. 수동 JSON 필드 편집도 동일한 검증을 사용한다.
- 네 수신 앱의 spec/두 fixture 사본이 일치함을 검증했다. BOM·줄바꿈·파일 끝 공백만 정규화한다.
- 원문 파일 512 MiB, 구조화 기록 UTF-8 1 MiB, 객체/배열 중첩 64개 제한. Fitness 원격 묶음 응답만 12 MiB이며 개별 기록은 1 MiB다.

## 로컬·에뮬레이터 검증

| 앱 | 검증 범위 | 결과 |
|---|---|---|
| OCR core | 최종 전체 359 tests | 실패/skip 0 |
| OCR Windows | 최종 전체 100 tests, Compose 800×700 입력·검토·저장 UI | 실패 0, 기존 skip 3 |
| OCR Android | 최종 전체 153 unit tests, 계측 2개, lint/build | 통과; lint warning 29개와 상태값 최적화 hint 1개 |
| Fitness | 최종 전체 650 JVM tests; 계측 20개, UI 재확인 2개, 최종 DB 보존 4개 재실행 | 실패 0, JVM 기존 skip 2 |
| CashOS | 전체 450 tests 후 최종 관련 66개; Java 파일 내보내기 3개; 최종 E2E 3개 | 통과, lint/typecheck/build 통과 |
| PriceTrace | 최종 699 tests/85 files, 119 migration PGlite 검증, E2E desktop/mobile 2개 | 통과, lint 오류 0/기존 img warning 1 |
| HealthTrace | 전체 465 tests/기존 skip 2 이후 최종 관련 58개 | 통과, analyze/build 통과 |

OCR Windows는 원문 보관/재시작/검증 내보내기, invalid JSON 원문 보존, 중복/충돌, review gate, 정정, 손상 원문 차단을 검사했다. `createDistributable` 통과. Android Studio JBR에 jpackage가 없어 OpenJDK 24.0.1로 패키징했다. 패키지 새 프로세스는 격리 LOCALAPPDATA/설정에서 기동·응답 상태와 예외 없음을 확인했다. 정상 종료는 확인하지 못해 해당 시험 프로세스를 종료했다. 최종 파서 수정 후 패키지는 다시 빌드했으며 기동 시험 자체를 반복하지는 않았다.

OCR Android는 에뮬레이터에서 홈 → SAF 파일 선택 → 비정형 바이너리 보관 → 시스템 내보내기를 실행했고 원본/보관/내보내기 해시가 일치했다. 마지막 파서 수정은 전체 unit tests와 APK 재빌드로 검증했다.

CashOS와 PriceTrace는 에뮬레이터의 실제 Android 시스템 저장창에서 내보내기·취소를 확인했다. 내려받은 JSON은 각각 1,322/1,331 bytes로 기대 바이트와 SHA-256이 일치했다. 취소가 성공으로 표시되지 않고 임시 파일이 정리됐다. 테스트 API는 합성 fixture로 차단/대체하여 운영 기록을 쓰지 않았다. CashOS의 기존 JSON/CSV 백업도 같은 SAF 저장 경로를 사용한다.

Fitness는 Room v57→v58→v59, profile/project 격리, backup/재시작, 검토 상태, 원격 Meal 계정 확인을 검사했다. 기존 supplement 이력은 보존하면서 신규 입력을 HealthTrace로 안내한다. Room에서 생성한 실제 합성 건강 export를 HealthTrace에 입력해 원문/원본 ID/시각·중복·프로필 격리·기존 집계와 mirror 불변을 확인했다. HealthTrace SQLite v14→v15는 기존 행을 보존한다. HealthTrace의 Android 파일 선택/공유 OS 동작은 아직 기기 검증하지 않았다.

독립 검토는 OCR Windows/Android, Fitness, PriceTrace 캡처 7개의 확인 범위에서 차단 결함이 없다고 판단했다. 중복 JSON 키 값 유실 P2를 발견해 수정했고 좁은 재검토에서 새 결함은 없었다. 모든 화면의 접근성·키보드·실기기 동작 검증을 뜻하지 않는다. 첫 reviewer 실행은 지원되지 않는 모델 설정으로 실패하여 기본 에이전트로 다시 수행했다.

## 운영 DB 반영

| 프로젝트 | 적용 migration | SQL SHA-256 |
|---|---|---|
| PriceTrace | `20261106122747_pricetrace_information_inbox` | `7BBEF2588CF0CEC8420D59C0729F3A72E37957EEC8F836001B510085B97C35A0` |
| PriceTrace | `20261106131954_reject_duplicate_information_keys` | `51D050AA66216BF276AC9B8A50CC3495C51658C0126E923E509A64601E9E49D6` |
| CashOS | `20261106124305_finance_information_inbox` | `0A02F7854FA9DF366923D37F50D0C5DAD508207182E0624D571F45717B04A31B` |
| CashOS | `20261106132220_finance_information_reject_duplicate_keys` | `87EE4AC15D4E06ED767E8591566FE62BE197E83A39697032FA153E93E967A610` |

네 migration을 hosted DB에 적용했고 history를 확인했다. 기존 적용 파일은 수정하지 않았다. 원격 실제 migration history를 임시 staging으로 fetch한 뒤 신규 파일 하나씩 dry-run/apply했다. 과거 local/remote history 차이 때문에 원본 checkout 전체를 bulk push하지 않았다. 반복 가능한 절차는 `scripts/deploy-information-migration.ps1`에 있다.

두 DB에서 실제 authenticated/anon 역할로 owner import/replay/conflict, 원문 불변, owner state update, foreign read/update 차단, account-switch 차단, anon read/RPC 차단을 검사했다. 후속 migration 이후 중복 키 canonical 함수와 RPC 거절도 재검사했다. 시험은 전부 ROLLBACK했다. CashOS는 기존 원장/계좌 행 전체 해시도 비교했다.

- CashOS: 새 inbox 0행, 원장 3,408행, 계좌 24개, 자산 0행 유지.
- PriceTrace: 새 inbox 0행, 상품 113행, 가격 관측 3행 유지.
- RLS 활성화 확인. 초기 적용 후 security advisors에 새 정보함 관련 지적 없음. 기존 DB 전체 지적이 해소됐다는 뜻은 아니다.
- 실제 HTTP 인증 후 GET 200, 익명 GET 401, 계정 불일치 RPC 403, 잘못된 payload 400 확인. HTTP 양성 쓰기는 하지 않았으며 양성 저장은 DB role rollback 시험으로 검증했다.
- Nutrition 실제 관계 포함 GET은 200/빈 배열 0행이었다. 비어 있지 않은 실제 운영 Meal 형태까지 검증하지는 못했다. nullable·정밀도·페이지 수신은 fixture로 검사했다.
- PriceTrace 플러그인 계정은 프로젝트 접근 권한이 없어 기존 인증 CLI를 사용했다. 설치된 v2.109.1을 유지했다. 적용 후 Docker catalog cache 경고가 있었지만 원격 schema/history/role 검증은 성공했다.

## 실행 산출물

각 저장소의 `.worktrees/universal-information-intake` 기준이다. APK는 debug 서명 빌드다. 기존 물리 기기에 설치된 서명·데이터와 호환성은 아직 대조하지 않았다.

| 앱 | 파일 | SHA-256 |
|---|---|---|
| OCR Windows | `desktop-app/build/compose/binaries/main/app/Yeonsik Ingestion Console/Yeonsik Ingestion Console.exe` | 같은 폴더 전체가 필요 |
| OCR Android | `app/build/outputs/apk/debug/app-debug.apk` | `A7A4329A04DDBD489E5A44A31778091059F9CBD4846CF3E27443FA8341C1EC8D` |
| Fitness | `app/build/outputs/apk/debug/app-debug.apk` | `FB5F47E83696A6AF5810DD382BEFE9601CB2144C9FF71CFA3B2B318E2685A449` |
| CashOS | `android/app/build/outputs/apk/debug/app-debug.apk` | `D0B506CF615641A45AECCAC7F1FA7A5AE0A0E2C533807BDAF8D605192D556D4A` |
| PriceTrace | `android/app/build/outputs/apk/debug/app-debug.apk` | `CAB1B7B3BC323F0EFF27C1E1022D171A4EB52FB27FDB6FCD4D48FEFEC4309143` |
| HealthTrace | `build/app/outputs/flutter-apk/app-debug.apk` | `0A3A606D78A8EBE243A1BE61C90A0736E5D0656D00C6794AD8F15DF3CEC5E404` |

주요 기능 커밋: OCR `e63f3ad`, `64e374c`, `2a8bbeb`, `2275d74`; Fitness `c5992a24`, `1b1734fe`, `aaf148b0`, `c9066f9d`, `941ffdb1`; CashOS `00a1a54`, 문서 `e89810e`; PriceTrace `82b86e9`, `59dcac2`, `36f6c36`; HealthTrace `aeb7b45`, `2070df0`, `49fbc83`, `735594e`.

## 재실행과 증거 위치

- OCR 최종: `gradlew.bat :core:test :desktop-app:test :desktop-app:createDistributable :app:testDebugUnitTest :app:assembleDebug -Dorg.gradle.java.home=C:/Users/nwhck/.jdks/openjdk-24.0.1 --console=plain`.
- 계약 사본: `node scripts/verify-information-contract-mirrors.mjs <Fitness worktree> <CashOS worktree> <PriceTrace worktree> <HealthTrace worktree>`.
- 운영 role/rollback: `scripts/verify-cashos-information-hosted.sql`, `scripts/verify-pricetrace-information-hosted.sql`.
- HTTP: `node scripts/verify-information-http.mjs <로컬 env 파일> cashos|pricetrace`; Nutrition: `scripts/verify-nutrition-meal-read.mjs`. 비밀번호/토큰은 출력하지 않는다.
- OCR Android: `build/information-android-qa/`; Windows 결과: `desktop-app/build/reports/tests/test/`.
- Fitness: `artifacts/information-intake/`; CashOS: `.codex/information-intake-validation/`; PriceTrace: `.impeccable/review/`와 native export 검증 로그. 생성 증거는 일반적으로 Git에 커밋하지 않는다.

## 남은 범위

임의 원문 보존과 소유 앱별 수동 구조화·검토·저장의 기반은 구현했다. 모든 매체 자동 인식, 도메인 밖 자료의 새로운 owner, generic 기록 자동 전송, 기존 통계로의 검수된 변환, 대량 장기 자료함 조회, 운영 웹 배포, 기존 feature branch 통합과 물리 기기 보존 설치는 남아 있다. Nutrition 계정에 실제 Meal이 생긴 뒤 수신 대조도 필요하다. 검토함 구현을 자동 영양 계산·질환 판단·모든 정보의 자동 이해 완료로 표현하지 않는다.
