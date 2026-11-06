# 정보 수용 범위 감사와 통합 구현 계획

기준일: 2026-11-06. 이 문서는 실제 저장소에서 확인한 범위와 새 구현의 완료 조건을 구분한다. 전체 목표는 원문을 잃지 않고 정보를 구조화하여, 의미를 소유하는 앱에서 기록하고 다른 앱은 참조하여 사용하는 것이다.

## 현재 가장 먼저 완성할 단위

공통 information-record.v1 계약으로 원문 → 구조화 → 소유 앱 검토함 → 재실행/내보내기를 연결한다. 기존 원장·식단·운동·건강 집계·공개 상품 카탈로그의 의미를 보존한다. 운영 반영은 기존 전용 계약 또는 별도 검증된 변환을 통해 수행한다.

"어떤 정보든 보관"과 "어떤 정보든 자동 이해"는 다른 완료 조건이다. 이번 확장 구조는 임의 파일 원문, 텍스트, 정밀 수치, 단위, 날짜, 중첩 JSON, 출처와 관계를 보존한다. 이미지·PDF·음성·영상·센서 로그 각각의 자동 추출 품질, 모순 판단, 질병 판단을 일괄 보증할 수는 없다. 자동 추출이 없는 파일도 원본 바이트는 보관한다.

## 감사 대상과 작업 기준

| 앱 | 실제 저장소 | 확인한 기준과 새 작업 기준 |
|---|---|---|
| Fitness | C:/Github/personal-os/FitnessApp | 현행 branch 08b7430b, origin/main 1df25929. 새 worktree는 main 기반 |
| CashOS | C:/Github/personal-os/CashOS | origin/main 156f3bd. 기존 expense-recoveries 미커밋 작업 보존 |
| PriceTrace | C:/Github/창팡맨 | origin/main 7c16a50. 기존 b318f6f checkout의 미커밋 UI 보존 |
| HealthTrace | C:/Github/HealthTrace | 현행 624dd21 기반. origin/main에는 현행 건강 기능이 없어 기능 branch 보존 |
| OCR | C:/Github/PriceTrace_OCR_APP | origin/main d36fbd8. 기존 retry-repaired-receipt 미커밋 수정 보존 |

각 저장소의 `.worktrees/universal-information-intake`, branch `feat/universal-information-intake-20261106`에서 작업한다. 기존 feature branch의 고유 변경은 자동으로 합쳐졌다고 간주하지 않는다. 특히 Fitness coaching/UI와 HealthTrace main의 수면 mirror 기록은 별도 통합 검토 대상이다.

## 소유 경계

| 정보 | 소유 | 다른 앱의 사용 |
|---|---|---|
| 실제 운동·활동·섭취·체중 | Fitness | HealthTrace는 조회/분석용 참조 |
| 개인 결제·잔액·투자·채권/채무·자산 변동 | CashOS | 구매한 사실은 제품 identity나 섭취 사실을 확정하지 않음 |
| 상품·식당·판매처·서비스·부동산/원자재 등 유상 대상의 identity와 가격 관측 | PriceTrace | CashOS/Health/Fitness가 정확한 owner ID를 참조 |
| 수면·증상·질환·검사·복용 | HealthTrace | Fitness가 운동 준비도 등을 위해 조회 가능 |
| 원문 파일·추출 후보·전달 이력 | OCR | 도메인 원본을 대신 소유하지 않음 |
| 아직 소유 앱이 없는 지식·관계·일정·법률문서·창작물 등 | OCR에서 미분류 원문 보관 | 억지로 네 앱에 분류하지 않음. 반복 사용되는 새 도메인은 전용 owner 도입 |

현재 Nutrition 카탈로그는 Fitness가 소유하며 PriceTrace가 외부 조회한다. 제품 identity와 영양 조성의 기존 분리는 유지한다. 제품의 일반 사실과 개인의 섭취 사건을 구별한다.

구조 검증은 owner_app과 record_type/필드 namespace를 강제한다. 자연어 속 의미까지 자동으로 소유권을 판정하지 못하므로 가져오기 전에 사용자 소유 경계 검토가 필요하다. 외부 ID는 private reference이며 다른 계정/프로젝트 권한을 부여하지 않는다.

## 착수 시 수용 범위와 누락

아래 범위와 누락은 구현 착수 당시 기준이다. 각 절의 “이번 구현”과 마지막 상태 표가 변경 후 상태를 설명한다.

### Fitness

착수 시 운동 session/exercise/set/routine, 중량 단위, RPE/RIR, GPS/거리/시간/심박, 식품/메뉴/섭취량, 영양 snapshot, 체중/신장을 저장했다. Room v57, user scope와 backup이 있었으며 CashOS receipt/item/ledger ID와 PriceTrace 상품/식당/메뉴 ID를 참조했다.

근거: `FitnessRoomFreshSchema.kt`, `FitnessDatabaseContract.kt`, `NutritionCatalogSyncClient.java`, `CashOsVerifiedReceiptContract.java`, `docs/fitness-meal-verified-import.v1.md`.

누락 및 충돌:

- OCR의 원격 import_verified_meal_v1 결과를 Android에 가져오는 전용 Meal receiver가 없다. catalog sync는 meals를 동기화하지 않는다.
- 원격 kcal/protein NULL을 로컬 NOT NULL 필드에 0으로 넣으면 미상값이 확정값으로 변한다. source confidence/consumed unit도 완전하게 들어갈 공간이 부족하다.
- supplement plans/schedules/intakes/effects와 sleep/energy/check-in은 새 HealthTrace 경계와 충돌한다. 기존 기록 삭제/owner 재표기는 금지한다.
- 임의 활동 측정값·출처·새 기록 유형을 보관할 일반 receiver가 없다.

이번 구현: owner/profile scope의 활동 정보 검토함, Room v57→v58→v59 추가형 migration, import/export, source/typed fields 보존, pending/reviewed/archived metadata, backup 연결. Nutrition 원격 Meal을 현재 계정과 로컬 프로필의 명시적 연결 후 읽어 검토함에 저장한다. nullable 영양값과 원격 전체 snapshot을 보존하며 기존 칼로리 합계에는 자동 반영하지 않는다.

건강 경계도 전환했다. Fitness의 기존 supplement 입력은 읽기 전용으로 남기고 새 복용 입력은 HealthTrace로 안내한다. 기존 기록은 안정적인 ID로 Health 소유 파일을 내보낼 수 있다. 수면 등 과거 health 필드는 보존하고 Fitness의 recovery 편집은 수분·운동 준비도에 한정한다. 실제 Room에서 만든 합성 export를 HealthTrace에서 재수신·원문 대조했다. 기존 이력 삭제나 정식 Health 섭취 기록으로의 자동 변환은 하지 않는다.

### CashOS

착수 시 원장, 계좌, 자산, 매수/매도 lot/allocation, 반복 지출, 이체, 환급, 영수증/주문/결제/품목과 PriceTrace 참조를 저장했다. transaction-ingest.v4가 문서 ID·revision·fingerprint·결제 상태·시점과 provenance를 보존했다.

근거: `src/shared/contracts/transaction-ingest-v4.ts`, `supabase/migrations/20260911090000_transaction_ingest_v4.sql`, `src/shared/contracts/price-trace.ts`.

누락: KRW 밖의 금융 자료, 잔액/부채/계약/보험 등 원장으로 즉시 확정할 수 없는 금융 관측, 새 구조와 원문의 연결. 기존 v4를 새 generic 원장으로 대체하면 안 된다.

이번 구현: finance 소유 금융 자료 검토함. 임의 통화 금액도 decimal 문자열과 currency/unit 필드로 보존하지만 기존 KRW 총액에는 자동 합산하지 않는다. Supabase 추가형 table/RLS/RPC, import/export, 검토 UI를 연결한다.

### PriceTrace

타입에는 retail_product/menu_item/raw_material/property/service가 있으나 실제 입력·가격 경로는 소매/식당에 집중된다. other/unknown 구매는 source/lines만 보관한다. standalone price는 exact product/store와 KRW를 요구한다. 후보에는 retail_product/complimentary_side/meal_component_estimate 제약이 있다.

근거: `src/domain/types.ts`, `src/domain/price-observation-submit.ts`, `docs/contracts/PURCHASE_PRICE_OBSERVATION_V4.md`, `STANDALONE_PRICE_OBSERVATION_V3.md`, `PRODUCT_CANDIDATE_INGEST_V1.md`.

이번 구현: 서비스/원자재/부동산을 포함한 비공개 유상 대상 관측 기록. 이름/관측값/가격/단위/통화/출처를 확장 필드로 보관, 기존 공개 카탈로그에 자동 게시하지 않음. Supabase 추가형 table/RLS/RPC와 UI.

### HealthTrace

착수 시 SQLite v14에 수면/외부 수면, supplement 제품/계획/복용, condition/observation/check-in/research를 저장했다. HealthRepository가 검증·참조·시간 역행 방지·저장·mirror 큐를 처리했고 Fitness 파일/server adapter와 PriceTrace는 조회만 했다.

근거: `lib/features/health/domain/health_record.dart`, `data/health_repository.dart`, `lib/features/sleep/data/sleep_database.dart`, `lib/integrations/fitness`, `lib/integrations/github/github_health_remote.dart`.

누락: 정밀 검사 수치/단위/참조 범위, 범용 source identity, OCR 가져오기와 재실행/충돌 방지. 기존 증상 severity/text만으로는 충분하지 않다.

이번 구현: Health 소유 관측/측정 정보 검토함. 수치·단위·시점·출처·외부 참조, local profile isolation, 추가형 SQLite migration, import/export·검토 UI. 검토함 자체는 진단 또는 수면/복용 사건을 생성하지 않는다.

### OCR

착수 시 yeonsik-ocr.v1~v5 고정 모드는 receipt/merchant/restaurant/product/purchase 중심이며 목적지는 CashOS/PriceTrace/Fitness 세 곳이었다. Desktop evidence 선택은 이미지 중심이었다. 원문 archive/revision/retry 구조는 있었으나 모든 문서형식의 보관과 범용 Health 전달은 없었다.

근거: `IngestionModels.kt`, `IngestionDestination.kt`, `YeonsikOcrEnvelopeCodec.kt`, `DesktopFileInput.kt`, `DesktopSessionStore.kt`.

이번 구현: 기존 pipeline과 별도 namespace의 정보 보관함. 임의 파일 스트리밍 보관·SHA-256·텍스트 수집·유효하지 않은 JSON도 원문 보존·typed field 편집·소유 앱 검토·immutable 정정·export. 자동 미디어 실행이나 임의 원격 fetch는 없음. 형식당 자동 인식기는 후속 작업.

## 공통 계약과 검증

정확한 wire 규칙은 `contracts/information-record.v1.md`, 공통 정상/오류 시험은 `contracts/information-record.v1.cases.json`과 `contracts/information-record.v1.raw-cases.json`이다. 원문 fixture는 DOM 변환 전에 중복 JSON 키를 거절하는지 검증한다. 네 앱은 각자의 인증/로컬 profile로 저장하며 imported user_id를 받지 않는다. `scripts/verify-information-contract-mirrors.mjs`로 네 수신 앱의 계약 사본이 일치하는지 반복 검사한다.

필수 수용 시험:

1. 각 owner 예제가 원문 → 저장 → 재시작 → export에서 값/출처를 유지한다.
2. 큰 십진수, 끝자리 0, false, 빈 문자열, json null, 중첩 custom JSON을 보존한다.
3. wrong owner, 잘못된 날짜, offset 없는 시각, 중복 key/source, 알 수 없는 source 참조를 쓰기 전에 거절한다.
4. 같은 ID/동일 content 재실행은 한 행, 다른 content는 conflict다.
5. 정정은 새 ID와 supersedes_record_id를 사용한다.
6. local migration은 기존 기록/계정을 보존한다. remote RLS는 anon/다른 사용자를 거절한다.
7. 입력/검수/오류/재시도/내보내기가 UI에서 도달 가능하다.
8. 새 generic 기록으로 기존 원장·칼로리·운동·공개 가격·건강 집계가 변하지 않는다.

## 구현 상태와 다음 완료 조건

| 작업 | 현재 상태 | 남은 완료 조건 |
|---|---|---|
| 공통 수용·각 앱 검토함 | 구현·저장/계약/UI 검증 완료 | 대량 장기 사용과 실기기 사용감 확인 |
| 기존 도메인 집계 반영 | 기존 전용 계약 유지, generic 검토함은 분리 | 자주 쓰는 자료부터 기존 validator에 연결하고 영향 preview/확인 제공 |
| Fitness 원격 Meal 수신 | 계정↔프로필 확인, nullable snapshot 수신·중복 검사 구현 | 운영 계정에 실제 Meal이 생긴 뒤 비어 있지 않은 원격 기록 대조; 현재 실제 GET은 200/0행 |
| supplement/health 경계 | 신규 Fitness 건강 입력 제한, 안정 ID export와 Room 합성 export의 Health 저장소 수신 검증 | 검토함 자료를 정식 Health 수면/섭취 기록으로 전환하는 별도 검수 계약 |
| 형식별 자동 추출 | 기존 영수증/제품 경로와 임의 원문 보관 사용 가능 | PDF/음성/영상/센서별 추출기·실제 샘플·오류율·수동 수정 검증 |
| 운영 DB | CashOS/PriceTrace 초기+중복키 후속 migration 적용, RLS/rollback 시험 통과 | 운영 웹 호스팅 반영 및 다른 feature branch와의 통합 검토 |
| 설치 파일 | Windows 실행 폴더와 Android debug APK 빌드 완료 | 물리 기기 데이터·서명 대조와 보존 설치; 이번 작업에서는 설치하지 않음 |

로컬 테스트·에뮬레이터·운영 DB·실기기·설치 상태는 `docs/validation/2026-11-06-information-intake.md`에 구분한다. 임의 정보의 보존·수동 구조화 기반은 구현했지만 모든 매체의 자동 이해나 모든 분야의 정식 도메인 기능까지 완성한 것은 아니다.
