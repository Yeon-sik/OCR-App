# 직접 전송·검토 승인·실제 기록 반영 최종 검증

검증 완료일: 2026-11-07 (Asia/Seoul). 이 문서는 [1단계 정보 보관함](2026-11-06-information-intake.md)의 후속 구현을 기록한다. 사용법·지원 adapter·소유 경계는 [전송과 반영](../INFORMATION_DELIVERY_PROJECTION.md)에 있다.

## 완료 범위와 작업 기준

OCR Windows/Android → 소유 앱의 비공개 서버 검토함 → 수신 앱의 계정/프로필 확인 → native mapping 검토와 명시 승인 → 해당 앱의 기존 실제 기록·통계까지 연결했다. OCR은 native 기록을 직접 쓰지 않으며 전송 완료를 검토함 도착으로만 표시한다. 각 앱은 자기 데이터만 반영하고 다른 앱의 식별자는 참조로 보존한다.

각 저장소의 `.worktrees/universal-information-intake`, branch `feat/information-delivery-projection-20261106`에서 진행했다. 원래 checkout의 작업과 물리 기기를 변경하지 않았다. 사용자가 중단한 뒤 재개하면서 미완료 시험과 독립 리뷰를 마무리했다. 앱 기능 커밋과 운영 DB 적용은 끝났지만 웹 호스팅 배포, push/merge, 물리 기기 보존 설치를 수행했다는 뜻은 아니다.

| 저장소 | 이 단계 기능·검증 커밋 |
|---|---|
| OCR | `62b394a` gateway/journal, `5588049` Windows/Android UI, `37a79a8` hosted 시험/정리 |
| Fitness | `627d8a64` native 반영·Room61·회귀, `bd5f03f6` 검증 문서 |
| CashOS | `a043ba1` native 원장/RPC/UI/시험, `fda23cd` 운영 보존 검증 |
| PriceTrace | `fa14d05` DB, `da070c3` UI/조회, `9c57192` 독립 리뷰 archive 보호 |
| HealthTrace | `50870df` 직접 수신·native 반영·SQLite17·검증 |

## 실행 검증

| 앱 | 검증 | 결과와 실제 확인 범위 |
|---|---|---|
| OCR | core 367개(일반 실행 opt-in live 1 skip), desktop 101개(기존 3 skip), Android unit 153개 | 실패 0. 계정/프로젝트 변경, 응답 유실 후 재시도, source 지문/원문 불일치, refresh/비밀 로그, 큰 escaped 응답 검증 |
| OCR UI | Windows 실제 Compose UI, emulator5584 계측 3개 | 전송 계정 미리보기 → 체크 전 비활성 → 명시 승인 → 도착 이력. Windows distributable/Android APK/lint 빌드 통과; lint 기존 Warning 29/Hint 1 |
| Fitness | JVM 659개(기존 opt-in 2 skip), emulator5584 계측 30개, PGlite mailbox | 실패 0. 실제 체중 72.4kg, 식사 500kcal/30P/60C/15F, 운동 volume 600kg 및 120초가 기존 읽기/통계에 반영. unknown은 NULL, 같은 날 체중 충돌은 보존 |
| CashOS | 92파일 478 tests, Playwright 4, lint/typecheck/build/APK | 통과. 승인된 원장 1건과 통계, durable receipt, 원문 불변, replay/rollback/계정/정정 계열 검증 |
| PriceTrace | 88파일 715 tests, 123 migration PGlite harness, Playwright 4, lint/typecheck/build/APK | 통과. 판매처 신청·관리자 승인·가격 공개 승인·public reader/가격 이력, archive/reopen 검증. 기존 img warning 1 |
| HealthTrace | Flutter 503 통과/기존 2 skip, 정보 관련 78개, analyze/APK/PGlite | 실제 SQLite 증상/복용/수면 기록·일일 집계, nullable/단위/정밀도, 승인/digest/rollback/프로필/정정 계열. 증상 승인 UI는 widget, 복용/수면은 repository/domain 시험 |

브라우저 시험의 HTTP는 합성 응답으로 제어한다. 실제 서버 증거는 아래 HTTP/운영 SQL 항목과 구분한다. CashOS E2E는 `CASHOS_E2E_REAL_AUTH=1`이 필요하며 기본값에서 skip된 4개를 통과로 세지 않았다. 위 플래그도 운영 회원 로그인을 뜻하지 않는다.

PriceTrace PGlite는 이전부터 문서화된 역사 migration bootstrap/test 보정을 포함한다. 모든 과거 migration을 아무 보정 없이 재생했다는 주장이 아니다. 로컬 Node 22.19.0은 CashOS/PriceTrace의 22.22.0 권장보다 낮아 경고가 있었으나 위 검증은 통과했다.

## 실제 HTTP 전달과 운영 보존

`InformationDeliveryLiveTest`를 opt-in으로 실행해 production gateway의 실제 로그인 → prepare → owner RPC → 서버 원문 재조회 → 같은 UUID 재전송을 **네 앱 모두 통과**했다. 운영 native/public 기록을 생성하지 않는 합성 비공개 자료만 사용했다. 쓰기 전에 UUID/소유자/정확한 payload manifest를 저장했고 네 자료 모두 UUID+계정+원문이 일치하는 경우에만 제거했다. 이후 각 검토함 0행을 확인했다. 비밀번호/토큰/원문은 Git에 커밋하지 않았다. 기본 회귀 실행에서 이 live 시험은 의도적으로 건너뛴다.

- **CashOS:** 실제 운영 RPC로 합성 원장 1건, 금액 12,345·캐시백 345·순지출 12,000 KRW와 일별 summary를 확인했다. 승인/digest 오류, 재시도, 불변 receipt, 조상/미수신/archived, 다른 주체/anon 차단 후 ROLLBACK. `INFORMATION_NATIVE_LEDGER_SUMMARY_ROLLBACK_PASS`.
- 시험 전후 인증 사용자 1명, 원장 3,408행, 계좌 24개, 금융/건강 inbox 0, projection receipt 0. 기존 전체 원장 행 해시 `b478b4b032c9e16b784ee34971409d5c`, 계좌 해시 `07079380bd9bad5f7c82a865e0e90f14` 동일. Auth 회원 생성·삭제 없음. 타 사용자 검증은 실제 두 번째 회원 로그인 대신 합성 JWT subject와 DB 역할/RLS 시험이며 구분해 기록한다.
- **PriceTrace:** 기존 관리자/두 번째 계정/검증 상품을 이용해 합성 판매처 신청 → 관리자 검토 → 공개 미리보기 → 승인 → native price → replay → anon public reader를 검증하고 ROLLBACK. archive 상태와 stale 미리보기 차단, 반영 후 archive receipt 재조회도 운영에서 확인했다. `INFORMATION_SELLER_TO_NATIVE_PUBLIC_PRICE_ROLLBACK_PASS`.
- 시험 후 상품 113, 기존 비공개 가격 3, 공개 가격 0, 승인 판매처 0, inbox/receipt/판매처 신청 각 0. 실제 판매처를 추측하여 새로 등록하지 않았다.
- **Fitness/Nutrition:** mailbox 실제 authenticated/foreign/anon rollback과 4앱 HTTP 시험 완료. 최종 inbox 0, meal_records 0, meal_record_items 0. 기존 전용 원격 Meal이 빈 상태이므로 실제 사용자 Meal의 비어 있지 않은 수신은 미검증이다.
- **HealthTrace:** 별도 health mailbox의 owner/RLS/불변/계정 전환/중복 시험 완료. CashOS와 같은 호스트를 사용하지만 health 소유 테이블과 별도 앱 세션을 사용한다. native 건강 행을 서버 mailbox에 복사하지 않는다.

CashOS security advisors의 신규 경고는 인증 사용자가 native apply SECURITY DEFINER를 실행할 수 있다는 내용이다. client의 receipt 직접 쓰기를 막고 원자적 저장을 수행하는 의도된 RPC이며, auth.uid/expected user/owner 검증·고정 search_path·anon 권한 회수를 실제 역할 시험으로 확인했다. 기존 프로젝트 전체 보안 지적 해소를 뜻하지 않는다.

## 적용한 DB 변경

이미 적용한 migration 파일은 수정하지 않고 후속 migration을 추가했다. 원격 실제 이력을 fetch한 staging에 새 파일만 추가해 dry-run/apply했다. CashOS migration은 공유 호스트의 더 늦은 Health 버전보다 앞선 timestamp여서 그 staging의 유일한 누락 파일을 확인한 후 `--include-all`로 적용했다. 원본 checkout을 bulk push하지 않았다. Docker catalog cache 경고와 무관하게 운영 history/schema/rollback 검증은 통과했다.

| 프로젝트 | migration | SHA-256 |
|---|---|---|
| Fitness/Nutrition | `20261106135251_fitness_information_inbox` | `CE41867B9C37AF3C564E76BE71A200F95797F5842464A0163496EA9BBCC7821D` |
| CashOS | `20261106135528_finance_information_projection` | `C07EA8B54156F6644A3840C6B92B434E5300FAAC82B28ED6C6E8C8BD0F38D7EB` |
| HealthTrace | `20261106140136_health_information_mailbox` | `828A0ECCC8D3C4403A3B74BBEE115791B9BC0416891867EA2DE43F40639D62B3` |
| PriceTrace | `20261106135123_information_public_price_projection` | `0267596A284EF5239BCDD332F1CF684520DD32657D4528D1F399420ABE246B88` |
| PriceTrace | `20261106140713_information_seller_review` | `3BADCF2D25704FE87B9E562B365B222539048925B1ADFA18D24A7B5C350FA94C` |
| PriceTrace | `20261106142738_information_projection_observation_lock` | `7317268BCBCBC5AD840CCD1B75C9C4850CD3BB264EFDF645DAF361447A95C0B1` |
| PriceTrace | `20261106155626_information_projection_archive_guard` | `7388F6B5538F2204CE26184690E6F622A5FC43F434A2C1C6FAAE25C3EAB74133` |

로컬 DB는 Fitness Room 59→60→61, Health SQLite 15→16→17이다. Fitness v60 원문/native/매핑과 backup 복원을 보존하고 정정 계열 root만 추가했다. Health 최종 APK `install -r`로 emulator5584의 v15→17을 확인했다. 기존 19개 테이블 전체 행(프로필/settings/native/자료 1개 포함)이 설치 전후 동일하고 새 3개 테이블만 추가되었으며 integrity_check와 앱 기동이 통과했다. 실제 A25는 수정하지 않았다.

## 독립 리뷰와 수정

별도 검토자가 OCR 계정 승인·owner RPC·전송 이력, 각 수신 앱의 native 트랜잭션·receipt·정정 계열·RLS·공개 경계를 읽기 전용으로 검토했다. 아래 P2 네 건을 수정하고 좁은 재검토에서 남은 확정 P1/P2 없음을 확인했다. 전체 시험이나 실기기 흐름을 reviewer가 독립 재실행한 것은 아니다.

| 발견 | 수정과 회귀 |
|---|---|
| PriceTrace archived 신규 공개 허용 | 후속 SQL에서 preview/apply 차단, UI 안내, 명시 reopen. 기존 receipt replay는 유지. 로컬 SQL/브라우저/운영 rollback 통과 |
| Health PostgreSQL/Dart Unicode 정렬 지문 불일치 | 서버/로컬 지문 분리와 불변 수신 영수증(v17), 실제 PG Unicode fixture를 수신·중복·재시작까지 검증 |
| Fitness 단위 누락 수치 자동 확정 | 정확한 명시 단위가 맞을 때만 추천, 미상은 빈 값, kg/kcal 등 회귀 |
| Fitness offset 시각 손실 | 같은 instant를 기기 시간대로 변환하고 날짜·시각 함께 추천. 명시 날짜 충돌은 수동 확인, UTC↔Seoul 자정 회귀 |

## 실행 산출물

모두 각 저장소의 `.worktrees/universal-information-intake` 기준이며 debug 서명 APK다. 기존 실기기 서명과의 호환성을 검사하거나 교체 설치하지 않았다.

| 앱 | 상대 경로 | SHA-256 |
|---|---|---|
| OCR Windows | `desktop-app/build/compose/binaries/main/app/Yeonsik Ingestion Console/Yeonsik Ingestion Console.exe` | 같은 폴더 전체 필요 |
| OCR Android | `app/build/outputs/apk/debug/app-debug.apk` | `20CA414709A3CB0F078FEE060D78257CF7BFDE25B859CAFA36FFEBD177583895` |
| Fitness | `app/build/outputs/apk/debug/app-debug.apk` | `375F473C5B7B2542FB334ACDDF33782F02B7371CA090C9A78375903C13E7DC29` |
| CashOS | `android/app/build/outputs/apk/debug/app-debug.apk` | `4623A5609734E9020865D43DCC2A37C3CA7DE72C84619E64496A6AF2309389AD` |
| PriceTrace | `android/app/build/outputs/apk/debug/app-debug.apk` | `5AC86E88C1B49112DC58ED8A576C020A25020BE6C4E75A1A657379653E8E9C39` |
| HealthTrace | `build/app/outputs/flutter-apk/app-debug.apk` | `F2A4ED5A2637DE6FC38B50DC823353118F275C22522BDED9DB30A7586384A91C` |

CashOS APK는 최신 dist 47파일 모두 바이트 일치 확인 후 확정했다. Health APK에는 Health·PriceTrace·Nutrition 공개 URL/키만 추가했으며 비밀번호나 세션을 넣지 않았다. 생성 로그·캡처·Serena 설정·기존 generated 파일의 줄바꿈 표시는 기능 커밋에 포함하지 않고 보존했다.

## 재현 명령과 증거

- OCR: `gradlew.bat :core:test :desktop-app:test :app:testDebugUnitTest :app:assembleDebug :app:lintDebug :desktop-app:createDistributable -Dorg.gradle.java.home=C:/Users/nwhck/.jdks/openjdk-24.0.1 --console=plain`.
- OCR 실제 HTTP opt-in: `INFORMATION_DELIVERY_LIVE_ENV`를 로컬 인증 env 경로로 설정하고 `:core:test --tests '*InformationDeliveryLiveTest'` 실행. 새 합성 운영 자료를 쓰는 시험이다. `build/information-delivery-qa/hosted-fixtures-<UUID>.json`이 각 실행의 정리 manifest를 보존한다. `node scripts/prepare-information-fixture-cleanup.mjs <manifest> <output>`은 정확한 합성 payload 삭제 SQL만 준비하며 자동 실행하지 않는다. 실행 후 관련 운영 프로젝트에서 정리·0행 확인이 필요하다.
- 공통 계약: `node scripts/verify-information-contract-mirrors.mjs <Fitness> <CashOS> <PriceTrace> <HealthTrace>`. 최종 4사본의 계약/정상·오류 fixture/raw fixture SHA 일치.
- CashOS: `pnpm lint`, `pnpm typecheck`, `pnpm test`, `pnpm build`; 환경변수 `CASHOS_E2E_REAL_AUTH=1` 후 `pnpm test:e2e -- tests/e2e/information-inbox.spec.ts --workers=1`.
- PriceTrace: `npm run lint`, `npm run typecheck`, `npm test`, `npm run test:purchase-sql`, `npm run build`, `npm run test:e2e -- e2e/information-inbox.spec.ts --workers=1`, `npm run android:debug`.
- 운영 rollback: OCR `scripts/verify-fitness-information-hosted.sql`, `scripts/verify-health-information-hosted.sql`; CashOS `scripts/verify-information-projection-hosted.sql`; PriceTrace `supabase/tests/information_projection_hosted_rollback.sql`.
- Fitness 증거: `docs/validation/2026-11-07-information-delivery-projection.md`, `fitness-projection-final-instrumentation.log` 및 후속 경계 시험 로그.
- Health 증거: `docs/INFORMATION_DELIVERY_PROJECTION.md`, `build/information-native-qa/` 최종 시험·설치 전후 DB 증거.
- UI 캡처: OCR `desktop-app/build/reports/information-ui/`, `build/information-delivery-qa/`; PriceTrace `.impeccable/review/`; 나머지 각 앱 검증 문서 참조.

지원하지 않는 native adapter, 자동 미디어 추출, 이미 반영된 정정의 전용 통합 편집, 대량 장기 사용, 물리 기기 사용감과 배포는 이번 검증의 범위 밖이다. 기록 원문 보존과 현재 지원 adapter의 승인 후 실제 반영을 모든 종류 정보의 자동 이해로 확대하지 않는다.
