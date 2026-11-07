# 2026-11-07 실기기 범용 기록 전달 검증

## 결과와 검증 범위

Galaxy A25 / Android 16에 OCR, Fitness, CashOS, PriceTrace, HealthTrace를 기존 서명과 호환되는 `adb install -r`로 업데이트했다. 삭제 설치, 앱 데이터 초기화, 사용자 DB 교체는 하지 않았다. 다섯 앱을 실제 기기에서 실행하고 설치된 APK 해시를 빌드 파일과 대조했다.

OCR의 JSON 가져오기 → 소유 앱·출처 검토 → 수신 계정 확인 → 명시 전송 → 각 앱 검토 → 실제 기록 승인 → native 화면과 집계까지 아래 대표 시나리오 4개를 통과했다. 카메라 촬영·문자 인식 정확도 시험이나 모든 adapter의 실기기 시험을 뜻하지 않는다. 기존 단위/통합/에뮬레이터 결과는 `2026-11-07-information-delivery-projection.md`에 별도로 기록되어 있다.

| 수신 앱 | 실기기에서 승인한 자료 | 실제 기록과 집계 | 정리 결과 |
|---|---|---|---|
| CashOS | 합성 지출 1 KRW, 현금 계정 명시 선택 | 원장 1건, 현금 유출 1원, 순현금흐름 -1원 | 해당 원장만 UI에서 soft delete. 월간 통계 0원 복원 |
| Fitness | 합성 식사 1 kcal, 명시된 탄·단·지 각 0 g | 식사 1건·식사항목 1건, 홈 1끼, 식단 1 kcal. 재실행 후 유지 | 해당 식사만 UI에서 soft delete. 0끼 복원 |
| HealthTrace | 합성 증상, 강도 0, 지속 시간 미입력 | 증상 1건, 최근 28일 증상 집계 1건, 지속 시간 null | 해당 증상만 UI에서 soft delete. 증상 집계 0건 복원 |
| PriceTrace | 기존 verified 시장가 자료의 과거 관측 | 공개 관측 1건. 최근·최저·최고가 1,800원, 2026-07-28 이력 | 실제 과거 관측과 승인 판매처를 유지. 합성 가격은 게시하지 않음 |

## 설치와 보존

설치 전 각 앱을 중지하고 APK, 전체 앱 데이터 tar, 파일 SHA-256 목록, SQLite 사본을 보관했다. 설치 직후 실행 전의 기존 영구 파일은 OCR 22개, Fitness 13개, CashOS 110개, PriceTrace 50개, HealthTrace 11개가 바이트 단위로 동일했다. 캐시와 런타임 렌더링 자료는 비교 대상에서 제외했다. 백업에는 세션 등 개인정보가 있으므로 Git에 넣지 않는다.

- OCR DB v13 유지. 기존 receipt DB의 9개 테이블 값이 최종까지 동일하다. Google datatransport 런타임 텔레메트리는 실행에 따라 변했다.
- Fitness DB v57→61. Room schema metadata를 제외한 기존 42개 테이블의 원래 행은 모두 보존됐다. 식사 83건·식사항목 120건·운동 493건·체중 50건·루틴 7건을 덮어쓰지 않았다.
- Fitness는 최초에 기존 QA 키로 서명한 debug 빌드를 잠시 설치·실행했고, 기존 APK의 BuildConfig를 대조한 뒤 원래 `qa / test-friends` 변형으로 다시 빌드·교체했다. 최종 설치는 아래 QA APK다. 임시 debug 실행 중 빈 기본 루틴 1건이 자동 생성됐으며 기존 7건은 모두 동일하다. 이 추가 행을 기존 사용자 데이터로 오인해 삭제하지 않았다.
- Fitness 공통 계정과 기존 프로필은 유지했다. 별도 Nutrition 세션이 만료되어 정상 UI에서 동일 계정으로 재로그인했다. Nutrition 계정과 기존 로컬 프로필의 명시 연결을 거쳐 수신했다.
- HealthTrace DB v12→17. 실제 마이그레이션 전에 기존 DB 사본으로 production migration dry run을 통과했고, 기기 실행 직후 기존 13개 테이블의 원래 값이 모두 유지됐다. 시험 정리 시점에도 원래 행의 삭제·변경은 없었다.
- 중단 후 최종 스냅샷에서는 HealthTrace 수면 초안 1건이 없어지고 2026-11-07 12:43:52 KST에 생성된 수면 1건과 대응 동기화 작업이 추가돼 있었다. 변경 주체는 이 증거만으로 단정하지 않는다. 시험 증상과 별개이므로 복원·삭제하지 않았다. 기존 완료 수면 36건과 나머지 기존 행은 그대로다.
- CashOS 기존 원장 3,408건과 계좌 24건의 전체 행 해시는 시험 전후 동일하다. 시험 원장 1건은 별도 삭제 이력으로 남는다.
- PriceTrace 기존 catalog 113건, private price 3건, market price 1건의 전체 행 해시는 동일하다. 기존 로그인과 관리자 권한을 사용했고 권한을 새로 부여하지 않았다.

## 소유 경계와 공개 자료

수신 전후를 확인해 자료함 도착 자체가 native 기록이나 통계를 생성하지 않는 것을 검증했다. 각 앱의 승인 UI가 실제 저장을 수행했다. HealthTrace는 독립 세션과 기존 로컬 건강 프로필의 명시 연결을 사용했다. CashOS 세션을 복사하지 않았다.

PriceTrace는 기존 `market_price_observations`의 verified 관측 `c53e5660-cf82-4445-bb2b-183dc012265d`를 출처로 삼았다. 상품 `bc44c062-a7e3-4c8d-ae60-533e2542b380`의 verified 상태·330 g·포장 1개·판매처명·URL·1800 KRW·과거 관측일을 조회했다. 현재 외부 판매가를 새로 검증한 것은 아니다. 해당 근거로 판매처 신청 → 기존 관리자 계정의 별도 승인 → 정확한 상품/판매처/가격 필드/관측일 선택 → 공개 영향 미리보기 → 명시 가격 승인을 진행했다.

공개 관측 ID는 `a21f8244-7c0d-46f7-8bb6-ba727fad9be3`다. 실기기 상품 목록과 가격 이력에서 1건·1,800원·2026-07-28을 확인했다. 로그인 토큰 없이 실제 hosted RPC를 조회해 응답 열이 observation_id, catalog_product_id, product_name, store_id, store_name, location_label, observed_on, unit_price_krw의 8개뿐임을 확인했다. 원문, 검토 근거, 개인 계정, 다른 앱 기록은 포함되지 않았다.

## 재전송과 남는 이력

동일 원문 UUID 4개를 OCR UI에서 각각 다시 전송했다. 서버 검토함은 앱별로 1건, CashOS·PriceTrace 반영 영수증은 각 1건, PriceTrace 공개 가격은 1건으로 유지됐다. HealthTrace의 서버 새로 받기는 새 자료 0개를 표시했다. Fitness·HealthTrace 최종 로컬 반영 이력도 각각 1건이며 삭제한 시험 기록은 활성 기록으로 되살아나지 않았다. 이미 반영한 PriceTrace 자료를 다시 열면 동일 관측 ID의 영수증만 표시됐다. 이 시험을 모든 장애·동시 요청 조합 검증으로 확대 해석하지 않는다.

원문·전송 이력·승인 영수증과 soft-delete 감사 기록은 유지한다. HealthTrace에는 시험 증상의 삭제 동기화 작업 1건이 pending으로 남는다. 기존 동기화 작업은 변경하지 않았고 GitHub 푸시는 실행하지 않았다. 중단 후 추가된 실제 수면 관련 작업 역시 유지했다. 시험 원문은 제목에 DEVICE_QA_20261107를 표시했다.

이번 검증으로 새 DB migration이나 제품 코드 수정은 필요하지 않았다. 이미 구현된 migration이 실기기에서 적용됐다. 웹 배포, 원격 Git push, GitHub mirror 게시를 수행하지 않았다. 설치 파일의 차이와 세션 만료는 기존 설정을 유지하는 정상 업데이트·재로그인 경로로 처리했다.

## 최종 설치 APK

| 앱 | 버전 | SHA-256 |
|---|---|---|
| OCR | 0.2.0 | `20ca414709a3cb0f078fee060d78257cf7bfde25b859cafa36ffebd177583895` |
| Fitness QA | 1.1.0 | `af83ab4d63739be0ac84f5e18db39d46270e11f8f082e6ac57ca51bd3a124107` |
| CashOS | 1.0 | `4623a5609734e9020865d43dcc2a37c3ca7de72c84619e64496a6af2309389ad` |
| PriceTrace | 1.0 | `5ac86e88c1b49112dc58ed8a576c020a25020be6c4e75a1a657379653e8e9c39` |
| HealthTrace | 0.2.1 | `f2a4ed5a2637de6fc38b50dc823353118f275c22522bded9db30a7586384a91c` |

## 증거 위치와 재현

비공개 증거 루트: `build/physical-device-qa/20261107/` (Git 제외).

- `before-summary.json`, 각 package의 `before/`, `installed-before-launch/comparison.json`: 설치 전 백업과 설치 직후 보존.
- `final-installed-manifest.json`, `fitness-final-installed.json`: 실제 설치 SHA-256와 최종 QA 변형. `install-results.json`은 최초 설치 이력을 그대로 남겼으므로 Fitness 최종 해시는 이 두 파일을 우선한다.
- `ocr-*-delivered.{xml,png}`, `ocr-*-replay.{xml,png}`: 실제 기기 OCR 전송·재전송 화면.
- `cash-approved.txt`, `cash-stats-approved.txt`, `cash-stats-restored.txt`: 승인·통계 변화·복원.
- `fitness-native-meals.{xml,png}`, `fitness-meal-stats-restored.{xml,png}`: 1끼·1 kcal 및 정리.
- `health-observation-preview.xml`, `health-observation-approved.xml`, `health-symptom-count.xml`, `health-symptom-stats-restored.xml`, `health-receive-replay.xml`: 승인 전후와 재수신.
- `price-publication-preview.txt`, `price-publication-approved.txt`, `price-native-stats.txt`, `price-native-history.txt`, `price-reopened-receipt.txt`, `price-anonymous-read.json`: 실제 공개 반영과 native 조회·익명 응답.
- `price-before-publication.json`, `price-after-publication.json`, `*-replay-counts.json`, `final-original-row-preservation.json`, 각 package의 `final-after-replay/`: 서버/로컬 사후 대조.

Android 네이티브 UI는 ADB input + UIAutomator XML + screencap으로 조작·확인했다. CashOS/PriceTrace는 연결된 물리 기기의 실제 WebView에 ADB 포워딩/CDP로 연결해 DOM 폼을 조작했다. fixture HTTP나 데스크톱 브라우저 결과가 아니다. SQL과 익명 HTTP는 별도의 서버 검증 증거다.

검증용 Download JSON 4개와 임시 UI XML은 정리했다. 검증에 사용한 ADB 포워딩 9511/9512는 종료 시 존재하지 않음을 확인했다. 로컬 백업과 증거는 복구·검토를 위해 유지한다.
