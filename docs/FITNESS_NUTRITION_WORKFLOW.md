# Fitness 영양성분 OCR·DB 저장

## 목적

`Fitness App` 탭은 상품 라벨 사진을 공통 촬영·OCR 파이프라인으로 읽고, 사용자가 원본과 대조해 확정한 값만 Fitness Nutrition Supabase의 `nutrition_foods`에 본인 소유 `private` 행으로 저장합니다.

이 기능은 PriceTrace 상품 검색·연결·공개 기능을 소유하지 않습니다. 상품명 또는 브랜드 유사도로 `catalog_product_id`를 만들지 않으며, `product_nutrition_links`도 쓰지 않습니다.

## 확인한 계약 기준

2026-08-11 로컬 fetch 이후 다음 원격 기준을 대조했습니다.

- PriceTrace: `origin/main` `34c06d0`
  - 영수증 외부 계약은 `receipt.v2`입니다.
  - PriceTrace 상품 읽기는 `product-read.v1`, namespace `pricetrace`, 정확한 catalog/standard product ID를 사용합니다.
  - 이름 유사도는 exact link identity를 대신하지 않습니다.
- FitnessApp: `origin/main` `25081ed`
  - Nutrition DB는 Fitness 기본 DB와 별도 URL·publishable/anon key·사용자 세션을 사용합니다.
  - 신규 `data_version=2` 식품은 열량, 단백질, 탄수화물, 지방, 나트륨, 포화지방, 당류 7종이 모두 필요합니다.
  - 식이섬유, 첨가당, 트랜스지방, 콜레스테롤은 라벨에 없으면 `null`입니다. `0`은 실제 0으로 확인한 경우에만 입력합니다.
  - 일반 동기화는 사용자 소유 `private` 행만 push하며, 공개 전환과 PriceTrace link는 별도 승인/RPC가 소유합니다.
  - 최신 개발 탭의 검증된 단일식품 카탈로그는 별도 seed/조회 경계이며, 이 앱이 쓰는 사용자 소유 완제품 라벨 행 계약을 바꾸지 않습니다.
  - `revision`을 사용해 최신 원격 행을 무조건 덮어쓰지 않습니다.

실제 Nutrition 프로젝트에 위 migration이 적용되었는지는 이 저장소의 로컬 빌드로 증명되지 않습니다.

## 저장 행

확정 후 전송하는 핵심 값은 다음과 같습니다.

| 열 | 값/규칙 |
| --- | --- |
| `id` | `ocr-nutrition:<document-id>`; 같은 세션 재시도에 안정적 |
| `owner_id` | 로그인 응답의 `user.id` |
| `name`, `brand` | 사용자가 검수한 상품명, 선택 브랜드 |
| `kind` | `external_menu` |
| `category` | 기본 `processed`, 검수 화면에서 Fitness 허용 값 중 선택 |
| `basis_amount`, `basis_unit` | 사용자 확정 기준량과 지원 단위 |
| `prep_state`, `cooking_method` | `unspecified` |
| 필수 영양소 | `calories_kcal`, `protein_grams`, `carbs_grams`, `fat_grams`, `sodium_mg`, `saturated_fat_grams`, `sugars_grams` |
| 선택 영양소 | `fiber_grams`, `added_sugars_grams`, `trans_fat_grams`, `cholesterol_mg`; 모름은 JSON `null` |
| `source_type` | `product_label_ocr` |
| `source_reference` | `ocr-document:<document-id>` |
| `source_version` | `nutrition-label-parser.v1` |
| `data_version` | `2` |
| `visibility` | `private` |

서버 payload에는 원본 이미지, 전체 OCR 문자열, bounding box, OCR evidence, Gemini 키, 비밀번호 또는 PriceTrace 상품 ID를 넣지 않습니다.

## 앱 설정과 사용

1. 홈에서 `Fitness App` 탭을 선택합니다.
2. 상품 라벨의 상품명·기준량·영양성분 표가 읽히도록 촬영하거나 이미지를 선택합니다.
3. OCR 뒤 상품명, 분류, 기준량·단위, 필수 7종을 원본과 대조합니다.
4. 라벨에 없는 선택 영양소는 비워 둡니다.
5. 검수 화면에서 별도 Nutrition Supabase HTTPS URL과 publishable/anon key를 저장합니다.
6. Fitness 계정 이메일·비밀번호로 로그인합니다. 비밀번호는 요청에만 사용하고 저장하지 않습니다.
7. `원본 대조 확정 후 DB 저장`을 누릅니다.

publishable/anon key는 클라이언트 배포용 값이며 앱 설정에 저장됩니다. access/refresh token은 Android Keystore AES-GCM으로 암호화한 뒤 private SharedPreferences에 저장합니다. `service_role` 키를 입력하거나 앱에 포함하지 않습니다.

## 충돌·재시도

- 최초 저장은 `on_conflict=id`와 `resolution=ignore-duplicates`를 사용합니다.
- 기존 OCR 행을 다시 저장할 때는 먼저 본인 행의 `revision`과 `source_reference`를 읽습니다.
- 같은 OCR source인 경우에만 `id + owner_id + revision` 조건으로 PATCH합니다.
- 원격 revision이 먼저 바뀌었거나 source가 다르면 덮어쓰지 않고 충돌로 반환합니다.
- 401/403이면 저장된 refresh token으로 한 번만 갱신한 뒤 재시도합니다.
- 네트워크·인증·계약·서버·충돌 실패는 구분하며, 사용자가 확정한 로컬 초안은 삭제하지 않습니다.

## 확인된 것과 남은 것

확인됨:

- 합성 한국어 영양 라벨 파싱, 단위 변환, 중복값 fail-closed, 필수 7종 검증
- 로컬 draft JSON round trip과 모름/null 보존
- 가짜 HTTPS 응답을 사용한 로그인, private insert, revision PATCH, source 충돌, 401 refresh
- UI/Room 계측 테스트 소스 컴파일

미확인:

- 실제 상품 라벨에서의 OCR 정확도
- 실제 Nutrition Supabase URL·키·사용자 계정 로그인
- 원격 migration/RLS 적용 상태와 실제 insert/update
- Fitness App이 저장된 행을 pull해 화면에 표시하는 종단간 흐름
- 오프라인 장기 retry queue; 현재는 로컬 확정 세션에서 사용자가 다시 누르는 방식
