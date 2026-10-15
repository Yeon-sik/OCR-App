# Component deployment and OCR merge gate

검증일: 2026-10-15 KST. 승인된 PT 배포와 remote smoke 후 OCR PR을 준비한 기록이다. OCR main 병합과 Producer 활성화는 별도 gate다.

## PriceTrace deployment

[PR #64](https://github.com/Yeon-sik/PriceTrace/pull/64)는 squash merge되었다. 최신 main은 `24f73ccca1167e484670266036c155b5d9f67393`이며 검증한 PR의 tree와 같다. [GitHub Pages deployment](https://github.com/Yeon-sik/PriceTrace/actions/runs/38682710330)는 success다.

실제 remote history 99개를 별도 staging에 fetch하여 보존했다. dry-run에서 미적용 목록이 아래 세 파일뿐임을 확인하고 사용자 승인 후 적용했다. seed/roles/vault 변경은 없었다. 적용 후 history는 102개이며 세 원래 version이 그대로 기록되었다.

1. `20261014091055_restaurant_component_identity_resolution.sql`
2. `20261014161626_restaurant_component_parent_reads.sql`
3. `20261014161627_restaurant_component_source_scope.sql`

기존 production migration timestamp를 repo에 맞추기 위한 repair나 재적용은 하지 않았다. component 두 테이블, 등록/search/selection RPC와 owner read V2 존재를 확인했다. 실제 signed HTTP 호출이 성공하여 PostgREST schema cache의 callable contract도 확인했다.

## Remote authenticated smoke

실제 Supabase Auth password grant로 격리 test owner 2개의 JWT를 발급받고 `/auth/v1/user`의 ID가 각각 일치함을 확인했다. 이후 저장·조회·replay·거부 검증은 실제 원격 `/rest/v1/rpc/*` 및 table HTTP 요청으로 수행했다. role simulation이나 adapter mock을 remote HTTP 증거로 사용하지 않았다.

| 불변식 | 확인 결과 |
| --- | --- |
| owner parent | owner-confirmed menu 아래 component 저장, V2 read 표시 |
| exact/global parent | 기존 verified registry fixture의 exact menu 아래 owner-private component 저장; resolved owner menu의 V2 read 표시 |
| public mapping authority | 수동 public 선택은 owner_confirmed, authoritativeIds=null |
| cross-purchase | A/B가 같은 fingerprint + food-soup key를 사용해 독립 evidence binding 저장; component identity 재사용 |
| replay | 동일 source/revision/key 등록·getter 응답 유지 |
| immutable facts | 같은 source/revision/key의 name/facts 변경 23505 |
| immutable parent | 두 실제 유료 행이 있는 source에서 같은 revision/key를 다른 부모로 변경하면 23505 |
| revision history | 새 revision은 evidence append, V2 component UUID 중복 표시 없음 |
| owner isolation | 타 owner RPC 차단, table RLS는 본인 component만 반환, V2에서 다른 owner 음식 미노출 |
| anonymous | read V2/등록 RPC/직접 table read 거부 |
| direct writes | authenticated INSERT/PATCH/DELETE 거부 |
| source/checkpoint | 모든 fixture source payload가 제출 원문과 동일; getter source ID 유지 |
| price/authority | component 가격 column/0원 관측 없음; actual paid line 수와 관측 수 일치; registry identity 추가 생성 없음 |

실제 DB aggregate를 추가 검사했다: source 5개, 실제 유료 line 6개, owner paid observation 5개와 exact paid observation 1개, component identity 3개, component evidence history 6개. global Restaurant/Menu는 준비한 fixture 각 1개뿐이었다.

기존 getter의 foreign source 거부는 `HTTP 500 / P0002 / purchase response was not found`다. 배포 전부터 존재하는 함수 정의의 not-found 계약과 일치하며 source data를 반환하지 않는다. 신규 component mutation은 `403 / 42501`로 거부한다. 최초 probe의 일괄 42501 가정은 기존 getter 계약과 달라 분리해 검사했다. 이 HTTP status 분류는 non-blocker 후속 정리 대상이고 production 코드는 변경하지 않았다.

## Fixture cleanup and security

두 test owner의 세션을 global sign-out했다. 테스트 marker/UUID를 확인하고 foreign reference가 없음을 검사한 뒤, admin cleanup transaction에서 해당 test owner 소유 자료와 fixture registry만 정리했다. append-only trigger가 적용된 synthetic rows 정리에는 transaction-local replication setting을 사용했다. 종료 후 auth/public owner references, test users와 registry/product/menu 잔여 0을 검사했다. 인증 토큰/비밀번호를 담은 로컬 임시 파일도 제거했다. migration과 운영 보안 설정은 유지했다.

배포된 RPC argument names와 OCR 실제 request shape를 대조했다. 등록은 `p_purchase_source_id, p_line_key, p_canonical_fingerprint, p_components, p_user_verified`, search/selection/platform도 기존 adapter 인자와 일치한다. 모두 empty search_path, anon execute=false이며 authenticated execute=true다. read V2는 security invoker다.

Supabase advisor의 authenticated SECURITY DEFINER 실행 경고에는 의도한 owner RPC가 포함된다. 실제 owner/foreign HTTP 검증과 함수 ACL/auth checks를 함께 확인했다. [advisor 설명](https://supabase.com/docs/guides/database/database-linter?lint=0029_authenticated_security_definer_function_executable). 기존 public-read 함수 및 Auth 설정 경고는 이번 배포 범위에서 변경하지 않았다.

## OCR rebase and verification

배포 검증 후 OCR remote main을 fetch했다. main은 `887948203a13917e3513269a9ed5fb6348fcc728`으로 이전 baseline과 같았다. `git rebase origin/main` 결과 up to date이며 충돌/semantic delta가 없었다. `e0446fd48e8d130eb1881042754e202a2bd52e07`를 `feat/restaurant-component-before-deployed-rebase-20261015` 백업 branch에도 보존했다.

최종 로컬 명령은 아래 전체 task를 `--rerun-tasks`로 다시 실행했다. 132개 task 모두 실제 실행하여 BUILD SUCCESSFUL을 확인했다.

```text
gradlew.bat :core:test :app:testDebugUnitTest :desktop-app:test :receipt-scanner:testDebugUnitTest lint assembleDebug :desktop-app:jar --no-daemon --rerun-tasks
```

| 검사 | 최종 로컬 결과 |
| --- | --- |
| core | 340 PASS |
| Android unit | 142 PASS |
| Desktop | 53 PASS / 기존 원격 smoke 3 SKIP |
| receipt-scanner | 176 PASS |
| 합계 | 714개 중 711 PASS, 3 SKIP, 실패 0 |
| Android lint | errors 0 / warnings 28 |
| receipt-scanner lint | errors 0 / warnings 0 |
| assembleDebug / Desktop jar | PASS |

remote GitHub CI는 생성된 OCR PR의 해당 head 상태를 따른다. local PASS를 원격 CI PASS로 대신하지 않는다.

회귀 범위: legacy 3-key encode/checkpoint, main+complimentary component, 실제 paid side, exact/owner parent, restart/replay, duplicate main/dangling/fake-food-photo rejection, provenance 보존, Fitness private component, no implicit Meal, no component PT price/CashOS item, canonical UUID 금지. 00/05/06/07 후보의 완전 예제 5개도 core parser 테스트에 포함한다. 이전 로컬 fixture와 mock 경계는 [최초 검증 보고서](2026-10-15-restaurant-component-finalization.md)에 있다.

## Remaining gate

OCR PR head의 remote CI와 review 후 사용자 승인으로 OCR main에 merge한다. 그 다음 latest-main parser에서 예제를 다시 decode/validate하고 00/05/06/07 완성본을 제공한다. 실제 ChatGPT Project Source of Truth는 제공되지 않았으며 repo 후보는 여전히 INACTIVE_RUNTIME_CANDIDATE다. 현재 Producer activation을 완료로 판정하지 않는다.
