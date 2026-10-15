# Restaurant component identity finalization

이 문서는 PT 배포 전 검증 기록이다. 승인된 실제 배포·signed HTTP smoke·OCR 최종 gate는 [후속 배포 보고서](2026-10-15-component-deployment-and-ocr-merge-gate.md)를 따른다.

검증일: 2026-10-15 KST. 현재 local 판정: READY_FOR_PT_MERGE. 운영 활성화 판정이 아니다.

## 1. 발견한 기존 문제

V2 owner read가 owner_menu_id만 읽어 restaurant_menu_id exact/global 부모의 owner-private component를 누락했다. 별도 실제 purchase가 같은 canonical fingerprint/local nutrition key를 사용할 때 owner-wide unique가 23505를 발생시켰다. 두 문제 모두 수정 전 fixture 실패로 재현했다.

## 2. 실제 수정

V2만 owner menu와 resolved_restaurant_menu_id를 통해 두 저장 부모를 읽는다. component row 자체를 한 번씩 집계하여 evidence revision/purchase replay로 목록을 늘리지 않는다. user_id를 양쪽에 제한하며 global authority를 부여하지 않는다. owner-wide unique만 제거하고 source-bound PK와 RPC immutable parent/facts 검사를 유지했다. OCR runtime에는 추가 결함을 확인하지 못해 production 코드는 더 변경하지 않았다. exact/owner bundle 재시작·replay 및 cross-contract 테스트를 보강했다.

## 3. Forward migration

배포 대상 PriceTrace 전체 feature delta의 순서:

1. 20261014091055_restaurant_component_identity_resolution.sql (기존 feature)
2. 20261014161626_restaurant_component_parent_reads.sql (신규 V2 read)
3. 20261014161627_restaurant_component_source_scope.sql (신규 unique scope)

원격 read-only 이력을 대조한 결과 위 세 component migration은 미적용이다. 일부 기존 purchase/authority migration의 remote timestamp는 repo와 다르므로 실제 remote history를 보존한 별도 deployment staging에서 이 세 파일만 추가해야 한다. 기존 migration을 재적용하거나 history repair로 timestamp를 맞추지 않는다. 기존 migration 수정/삭제/repair는 수행하지 않았다.

## 4. OCR 변경 파일

최종화 추가 delta:

- core/src/test/java/com/pricetrace/receiptscanner/ingestion/OwnerConfirmedPurchaseRecoveryTest.kt
- core/src/test/java/com/pricetrace/receiptscanner/ingestion/RestaurantPurchaseComponentTest.kt
- scripts/generate-producer-component-examples.cjs
- docs/producer-candidate/00_YEONSIK_OCR_SCHEMA_ROUTER.md
- docs/producer-candidate/05_YEONSIK_BUNDLE_SPEC.md
- docs/producer-candidate/06_YEONSIK_OCR_V5_RESTAURANT_PURCHASE_SPEC.md
- docs/producer-candidate/07_YEONSIK_OCR_V5_RESTAURANT_PURCHASE_EXAMPLES.md
- docs/PROJECT_V5_CONTRACT_PATCH.md
- docs/RESTAURANT_COMPONENT_IDENTITY_IMPLEMENTATION.md
- 이 보고서

기존 feature runtime/UI 변경 목록은 [기존 구현 보고서](../RESTAURANT_COMPONENT_IDENTITY_IMPLEMENTATION.md)에 있다. 기본 checkout의 아이콘/README 및 private 설정은 보존했다. 격리 worktree의 빌드는 synthetic .env를 사용했다.

## 5. PriceTrace 변경 파일

- supabase/migrations/20261014161626_restaurant_component_parent_reads.sql
- supabase/migrations/20261014161627_restaurant_component_source_scope.sql
- supabase/tests/restaurant_component_parent_reads.sql
- supabase/tests/restaurant_component_identity_resolution.sql
- scripts/test-purchase-price-sql.mjs
- scripts/verify-ocr-component-contract.mjs
- docs/contracts/RESTAURANT_COMPONENT_IDENTITY_RESOLUTION.md
- .github/workflows/restaurant-component-contract.yml

## 6. 테스트와 증명한 invariant

SQL read A–F: owner/exact parent 모두 표시, 다른 owner 미노출, component UUID 중복 없음, financial observation 증가 없음, source 불변, V1 function/response equality. authenticated 직접 insert/update/delete 및 anon read/RPC도 거부한다.

SQL scope Case 1–5: 독립 purchase의 같은 fingerprint/key 허용, 동일 source replay reuse, 같은 source/revision/key 부모 또는 facts 변경 conflict, 새 revision evidence append, 다른 owner 독립.

OCR core 기존 및 신규 회귀:

| 요구 | 실제 검증 |
| --- | --- |
| legacy 3-key | encoder 기본 optional key 생략, link dependency 문자열 및 canonical roundtrip 동일 |
| main + 국물 | 실제 1행/6,000원, nutrition 2개, 국물 menu_component |
| paid side | 독립 line_key/price, 문서 C parser 검증 |
| duplicate main / dangling / duplicate relation | strict validator reject |
| fake/missing food_photo | declared type/ref 및 로컬 evidence gate reject |
| role change | nutrition/provenance 유지, audit/undo |
| consumption | 구매-only는 Meal 적격성 없음; consumption 임의 생성 없음 |
| runtime search/select/defer/restart | canonical 불변, choice/current-review 검증, durable Desktop session 회귀 |
| exact parent | component parentRestaurantMenuId 복구, soup publication/private paid link 없음 |
| owner parent | component parentOwnerMenuId 복구, main private link만 유지 |
| replay / response loss | source ingest·component register·Nutrition/CashOS 성공 sink 재전송 없음 |
| source checkpoint | 재시작 getter/context sync 이후 source/checkpoint 보존 |
| Fitness private component | 2개 private import; main exact publication 또는 owner private link와 soup 분리 |
| canonical authority UUID | producer canonical UUID 없음, server IDs는 runtime metadata만 |

현실적 같은 fixture: `examples/yeonsik-ocr.v5.restaurant-purchase-components.example.json` → unchanged .yeonsik ZIP/manifest/hash/byte binding → strict parser → evidence gate → canonical review → 실제 PT/Fitness/CashOS adapters → runtime checkpoint/retry. OCR-generated PT request를 real local PGlite source ingest/merchant candidate/owner resolver/component RPC/V2 read에 재생했다. 실제 1 purchase line, 1 paid observation, 1 국물 component/source binding, 2 Nutrition imports, 1 CashOS item, Meal 0을 확인했다. 무료 국물 가격/구매 행 없음; 원문/evidence refs 불변; replay duplicate 없음.

한계: PT bridge SQL은 실제 로컬 DB 실행이다. Fitness/CashOS transport는 모의 응답이며 실제 해당 DB 저장 검증은 아니다. exact authority adapter fixture는 server metadata를 모의 제공하고, 실제 exact/global SQL parent/read는 별도의 read fixture로 검증했다. 실제 서명 HTTP JWT/evidence Storage archive/운영 DB end-to-end는 UNVERIFIED다.

## 7. 테스트 결과

| 검사 | 결과 |
| --- | --- |
| PT lint / typecheck | PASS |
| PT full Vitest | 62 files / 573 PASS |
| PT production static build | PASS; synthetic API config |
| PT migration chain | 104 local migrations; 기존 harness bootstrap 보정 포함 |
| PT SQL fixtures | 기존·신규 9개 PASS |
| OCR-generated → real PT SQL bridge | PASS |
| relevant production-export Playwright | owner/component/public/logout 5 + public restaurant 1 = 6 PASS |
| OCR core | 340 PASS |
| Android unit | 142 PASS |
| Desktop | 53 PASS / 기존 remote smoke 3 SKIP |
| receipt-scanner | 176 PASS |
| OCR 합계 | 714 중 711 PASS, 3 SKIP, 실패 0 |
| Android lint | errors 0 / warnings 28 |
| receipt-scanner lint | errors 0 / warnings 0 |
| assembleDebug / Desktop jar | PASS |
| 07 complete JSON examples | 5개 strict decode/validate PASS |
| PT PR #64 계약 및 문서 CI | head 2400a3684b6cf4ee40e50dbddf10bd3fb3493db2 SUCCESS; MERGEABLE/CLEAN |
| PT remote schema read-only | component table/source table/register RPC/read V2 모두 없음; 기존 read V1/resolver 존재 |

명령:

```text
npm.cmd run lint
npm.cmd run typecheck
npm.cmd test -- --maxWorkers=2 --minWorkers=1
npm.cmd run build
node scripts/test-purchase-price-sql.mjs <pglite-directory>
node scripts/test-purchase-price-sql.mjs <pglite-directory> --ocr-component <OCR-worktree>/core/build/component-contract-owner.json
npx.cmd playwright test e2e/owner-confirmed-restaurants.spec.ts e2e/restaurant-menu.spec.ts --config=test-results/component.playwright.config.cjs
gradlew.bat :core:test :app:testDebugUnitTest :desktop-app:test :receipt-scanner:testDebugUnitTest lint assembleDebug :desktop-app:jar --no-daemon
gradlew.bat :core:test --no-daemon
node scripts/generate-producer-component-examples.cjs
```

기본 assertion 제한을 바꾸지 않았다. 로컬 Playwright는 synthetic API fixture를 대상으로 production export를 loopback 서버에서 실행하며 test budget만 90초다.

## 8. 기존 failure와 한계

historical V4 CASE install syntax, qualified POSITION, min(uuid) 결함은 기존 harness에서 재현/보정한다. 새 migration 자체는 그대로 실행한다. 보정 없는 빈 PostgreSQL 설치를 PASS로 주장하지 않는다. 이전 보고의 public restaurant timeout은 이번 관련 실행에서 통과했다. 과거 shopping Nutrition timeout의 해당 전체 spec은 이번 작업에서 재실행하지 않아 UNVERIFIED다. 전체 브라우저 green 주장 없음.

Desktop skipped: DesktopApprovedCheckpointRecoveryTest, DesktopApprovedReceiptNutritionLinkTest, DesktopCheckpointRemoteIntegrationTest의 기존 운영 smoke. Windows sandbox realpath 및 worktree build 접근 실패는 실행 권한 조정 후 통과했다. 기기 설치/GUI 전체 flow는 UNVERIFIED다.

## 9. Security / authority boundary

owner RLS와 anonymous/direct write deny, owned source/line 및 foreign identity 선택 reject, empty search_path, internal helper execute revoke를 기존 및 신규 SQL에서 검증했다. caller-issued Restaurant/Menu UUID는 component payload shape에 없고 부모는 server persisted line response에서만 얻는다. public suggestion 선택은 owner-confirmed mapping이고 authoritativeIds=null이다. source-pair exact/global authority는 별도 기존 경로다. 참고: [Supabase RLS](https://supabase.com/docs/guides/database/postgres/row-level-security), [database functions](https://supabase.com/docs/guides/database/functions).

## 10. Backward compatibility

V1 owner read function/response unchanged를 SQL로 비교했다. V2/V4/V5 전체 기존 회귀와 legacy 3-key default encoder/dependency 문자열을 유지했다. 무료 component는 PT 가격/CashOS 항목을 늘리지 않는다. 기존 V2 receipt-context component RPC와 V4 purchase/CashOS transaction path를 유지한다. bundle/version/manifest 구조 변경 없음.

## 11. Producer 00/05/06/07

00은 V5 routing/main+included components와 기존 V2/V4/needs_split 경계를 명시한다. 06은 실제 optional keys/cardinality/V2 complimentary kind/provenance/consumption/runtime UUID 경계를 따른다. 07은 legacy, main+국물, paid side, options, runtime selection의 완전 JSON 5개를 생성기로 작성하고 parser 검증한다. 05는 V5 schema 및 history/food evidence 지원만 명시하며 transport shape/version/hash/binding은 유지한다.

사용자 확인: 실제 Project 원본은 없다. 따라서 이 네 파일은 INACTIVE_RUNTIME_CANDIDATE다. 실제 Source of Truth와 동일하다고 주장하지 않으며 Project를 수정/활성화하지 않았다.

## 12. 아직 수행하지 않은 production 작업

PriceTrace main merge, 운영 migration apply, 배포 후 schema-cache/실제 signed HTTP owner·foreign·anonymous smoke, 실제 PT exact component/replay smoke, OCR deployed-contract rebase/remote CI/merge, 실제 Project Source of Truth 적용/활성화 및 실제 downstream writes. 이 gate는 별도 승인 이후 진행한다. 원격에서 수행한 것은 migration 이력과 schema 존재 여부 SELECT뿐이다. PT main merge는 현재 deploy-pages workflow를 자동 실행하므로 코드 병합에도 website production 배포 영향이 있다.

## 13. 정확한 merge/deploy 순서

1. PriceTrace PR 준비 → 해당 head CI/review.
2. PriceTrace merge (GitHub Pages 자동 배포 영향 포함) → latest main SHA 재확인.
3. 승인된 절차에서 실제 remote history를 대조해 위 세 forward migration 중 미적용만 순서대로 적용.
4. remote migration/RPC/schema cache 확인 → authenticated owner smoke → anon/foreign deny → exact/global component private read → replay.
5. 배포된 PT 계약에 맞춰 OCR feature를 최신 main으로 최종 rebase → OCR CI → OCR merge.
6. 00/05/06/07을 실제 Project Source of Truth에 반영·대조 → 현실적 signed HTTP/bundle cross-contract smoke → Producer activation.

identity correction은 이번 blocker가 아니다. 원본 selection immutable + correction/supersession event append + effective identity 별도 계산 + old evidence/source/audit 보존을 후속 설계 task로 분리한다. 현재 안전한 correction path는 증명하지 못했고 기존 binding UPDATE/DELETE는 구현하지 않았다.

## 14. Activation 판단

PT PR #64의 해당 head 계약/문서 CI는 모두 통과했고 merge conflict가 없다. 외부 review submission은 아직 없다. 원격 schema/migration 조회로 component 기능 미배포를 확인했다. 실제 Project 원본은 없고 네 후보 파일은 비활성이다. Producer 활성화 불가.

Git delivery: PT `2400a3684b6cf4ee40e50dbddf10bd3fb3493db2`는 feature remote와 PR head 일치. OCR 추가 검증/후보 문서는 로컬 feature commit에만 있으며 PT 배포 전 push/merge하지 않았다. PriceTrace main `d04cb5cc249b9211a0b72ec88a2821ee20ed8148`, OCR main `887948203a13917e3513269a9ed5fb6348fcc728`은 최종 fetch에서도 동일했다.

판정: READY_FOR_PT_MERGE.
