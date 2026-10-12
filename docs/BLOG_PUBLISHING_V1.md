# 네이버 블로그 Publication V1

작성일: 2026-10-12

기준: 작업 시작 시 fetch한 main c62e030

브랜치: feature/blog-publishing

검증된 Nutrition 데이터를 소비하는 독립 기능이다. .yeonsik/canonical ingestion을 중간 포맷으로 사용하지 않는다.

## 1. 새로 추가한 파일

core/src/main/java/com/pricetrace/receiptscanner/publishing/:

- model/PublicationSource.kt
- model/BlogDraft.kt
- adapter/NutritionPublicationAdapter.kt
- generator/BlogDraftGenerator.kt
- generator/PublicationText.kt
- evidence/PublicationEvidenceResolver.kt

core/src/test/java/com/pricetrace/receiptscanner/publishing/:

- NutritionPublicationAdapterTest.kt
- BlogDraftGeneratorTest.kt

app/src/main/java/com/pricetrace/receiptocr/publishing/:

- generator/NutritionTableRenderer.kt
- generator/PublicationAssetStore.kt
- evidence/SessionPublicationEvidenceResolver.kt
- naver/NaverBlogPackageGenerator.kt
- share/PublicationClipboardExporter.kt
- share/PublicationShareExporter.kt
- ui/PublicationSamples.kt
- ui/PublicationPreviewScreen.kt
- ui/NutritionPublicationEntry.kt

app/src/androidTest/java/com/pricetrace/receiptocr/publishing/:

- PublicationAndroidTest.kt
- PublicationShareTest.kt
- PublicationPreviewTest.kt

문서: docs/BLOG_PUBLISHING_V1.md

신규 21개 파일. 외부 라이브러리 추가 없음.

## 2. 수정한 기존 파일

최초 V1 통합에서는 app/src/main/java/com/pricetrace/receiptocr/ReceiptScreens.kt 하나를 수정했다.

일반 NutritionReviewScreen에 진입 버튼을 연결한다.
현재 draft, 실제 pages, 기존 resolvePageFile을 신규 컴포넌트에 전달한다.
isCanonicalReview가 true이면 진입점을 제외한다.
기존 Review 내부 전체 화면 dialog를 사용하므로 AppScreen 변경이 없다.

## 3. 수정하지 않은 핵심 파일

ReceiptAppViewModel.kt, MainActivity.kt, ReceiptAppUiState/AppScreen 선언,
NutritionLabelModels.kt의 Draft/Field/Status/Validator,
YeonsikOcrEnvelope*, CanonicalReviewController, AndroidCanonicalJsonValidator,
IngestionModels/Orchestrator, canonical codec/projection/bundle writer,
Nutrition networking, Supabase 계약 및 DB visibility/schema,
build.gradle.kts, AndroidManifest.xml.
file_paths.xml은 리뷰 보완에서 생성 cache 자산의 최소 공유 경로만 추가했다. 기존 files-path는 유지했다.

기존 main 체크아웃의 아이콘 변경은 별도 worktree 사용으로 보존했다.

## 4. .yeonsik 브랜치와 예상 충돌 파일

직접 수정한 공유 지점은 ReceiptScreens.kt의 import와 NutritionReviewScreen 버튼 주변이다.
다른 브랜치가 같은 영역을 바꾸면 충돌 가능성이 있다.
독립 기능 개발 동안 신규 파일만 추가하고 기존 화면 연결을 마지막 커밋으로 분리했다.

## 5. NutritionLabelDraft → PublicationSource 매핑

| 기존 데이터 | Publication |
| --- | --- |
| documentId, productName, brand | 기존 값 snapshot |
| basisAmount, basisUnit | 기존 값 보존 |
| nutrients | NutritionField.entries 순서로 존재하는 값만 목록화 |
| NutritionField | wireKey, koreanLabel, canonicalUnit 보존 |
| sourceType, sourceReference, sourceVersion | 기존 출처 보존 |
| confirmedAt | 기존 값 보존; 유효한 날짜만 표시 |
| evidence | 실제 pageId의 비어 있지 않은 값만 중복 제거 |
| attachment IDs | 일반 draft에 없어 빈 목록 |

11개 영양소를 모두 지원한다. 부재와 실제 0을 구분한다.
category/parserVersion은 기존 validator가 검사하며 DTO에 중복 저장하지 않는다.
Android Uri는 core 경계 모델에서 문자열로 표현한다.
본문에는 긴 상품명 전체를 보존한다. 제목·표에서는 표시 길이를 제한한다.

## 6. Publication 가능 조건

일반 draft에 대해:

- USER_VERIFIED
- 비어 있지 않은 documentId
- NutritionLabelValidator.validate(draft).errors가 비어 있음
- UI에서 Fitness 전송·AI 대조 중이 아님

PARSED, 필수 값 누락, 잘못된 값, 기준량 null은 차단한다.
기존 validator가 기준량을 필수로 보므로 V1 진입점도 같은 계약을 따른다.
pure generator 자체는 기준량 없는 PublicationSource의 표현을 지원한다.
새 Nutrition 상태머신이나 별도 검수 확정 API는 추가하지 않았다.

## 7. Evidence 이미지 resolution

현재 저장 구조의 ReceiptPage.storageKey와 기존 resolvePageFile callback을 사용한다.

1. 같은 documentId에 속한 실제 ReceiptPage만 선택
2. evidence pageIds가 있으면 그 페이지만 선택
3. pageIds가 비어 있거나 공백 값뿐이면 원본 이미지 없이 안내만 반환. 문서 전체·첫 페이지 자동 선택 없음
4. canonical 경로가 filesDir/receipt-scanner 내부인지 확인
5. 파일 존재·읽기 가능·이미지 bounds·PNG/JPEG MIME·저장 SHA-256 확인
6. 기존 FileProvider로 content URI 생성

NutritionFieldEvidence의 rawText, SourceAttachment.label, LocalEvidence를 경로로 사용하지 않는다.
attachmentId 매핑이 없으면 경로를 추측하지 않고 제외 안내를 표시한다.
누락·읽기 실패·resolver 실패가 있어도 가능한 텍스트·생성 표는 제공한다.

원본은 읽기만 한다. 생성 표는 별도 저장한다.

- 원본: filesDir/receipt-scanner/<documentId>/pages/...
- 생성 표: cacheDir/receipt-scanner/publication-assets/<UUID>/nutrition_table.png

## 8. Evidence 실제 연결 성공 여부

실제 저장 페이지용 resolver 및 UI 연결을 구현했다. Mock 전용 연결이 아니다.
다만 실제 기기에서 원본 content URI를 읽는 실행 검증은 하지 않았다.
ReceiptFileStore에 합성 PNG를 저장하고 URI 읽기·원본 바이트 보존을 확인하는
instrumentation 테스트를 작성했지만 실행하지 않았다.
실제 사진과 네이버 앱 수신까지 성공했다고 보장할 수 없다.

## 9. Mock으로만 남은 부분

PublicationSamples와 독립 Compose Preview는 샘플 상품·표를 사용한다.
공유 테스트의 content URI 및 앱 실행 실패는 테스트용 값·주입 동작이다.
운영 진입점은 실제 draft/pages/FileProvider를 사용하며 샘플 fallback이 없다.

canonical Nutrition와 Supabase 과거 행 읽기는 V1 대상에서 제외했다.

## 10. 네이버 공유 방식

Android Sharesheet를 사용한다.

- 텍스트: ACTION_SEND, text/plain, 제목·본문
- 이미지 하나: ACTION_SEND와 EXTRA_STREAM
- 여러 이미지: ACTION_SEND_MULTIPLE과 모든 EXTRA_STREAM
- PNG/JPEG 혼합: image/*
- 전체 공유: 제목·본문과 원본·생성 이미지
- 이미지 없는 전체 공유: 텍스트 공유

FileProvider content URI, ClipData, FLAG_GRANT_READ_URI_PERMISSION을 사용한다.
원본 쓰기 권한을 부여하지 않는다.
네이버 package name, 로그인, 자동 게시, 브라우저 자동화에 의존하지 않는다.
실행 실패 시 복사 기능 안내를 표시한다.

[Android 공식 공유 문서](https://developer.android.com/develop/ui/compose/sharing/send)를 기준으로 구성했다.

## 11. 실제 Android 사용 흐름

1. 일반 Nutrition Review에서 원본 대조 및 기존 확정 흐름 수행
2. 검증된 USER_VERIFIED draft에서 블로그용 게시물 생성
3. 원본·표·제목·본문 확인
4. 제목/본문/전체 텍스트 복사 또는 텍스트/이미지/전체 공유
5. Sharesheet에서 수신 앱 선택
6. 네이버 블로그에서 내용을 확인하고 사용자가 최종 발행

기존 제약: 일반 draft 검수 확정은 Fitness 전송 액션과 묶여 있으며
Review 확정 버튼은 로그인 조건을 요구한다.
이번 기능은 이미 검증된 draft를 소비한다. 검수·전송 흐름은 변경하지 않았다.
Publication 생성·복사·공유 자체는 네트워크나 Supabase 로그인에 의존하지 않는다.

생성 중 닫으면 coroutine이 취소된다.
표 bitmap은 저장 후 해제하며 메모리·파일 실패 시 가능한 텍스트 기능을 유지한다.

## 12. 테스트 결과

아래는 최초 V1 구현 검증 이력이다. 리뷰 보완의 최종 HEAD 검증 결과는 해당 작업 완료 보고를 참조한다.

중단 지시 전에 수행한 결과:

- core 테스트 15개 통과: adapter 5, generator 10
- 실패 0, 오류 0, skip 0
- Android instrumentation 18개 작성 및 컴파일 완료
- Android 실행 테스트, 시각 QA, 실제 네이버 수신 확인은 미수행

사용자의 빌드·에뮬레이터 중단 지시 이후 테스트를 실행하지 않았다.
최종 화면 연결은 코드 검토만 수행했다.

## 13. 빌드 결과

아래는 최초 V1 구현 검증 이력이다. 리뷰 보완 요청으로 최종 HEAD 빌드·테스트를 별도 재실행한다.

중단 지시 전에 시작한 작업:

- :app:compileDebugKotlin 성공
- :app:assembleDebug, :app:assembleDebugAndroidTest 성공

빌드 완료 후 Gradle daemon 및 환경 확인용 ADB 서버를 종료했다.
에뮬레이터는 실행하지 않았다.
이후 저장 실패·취소 처리 보완과 최종 ReceiptScreens 연결을 재빌드하지 않았다.
따라서 위 결과는 최종 integration 직전 구현에 대한 결과다.
실제 계정 정보 대신 합성 테스트 설정을 사용했다.

## 14. main 병합 전 남은 integration 작업

코드 연결은 완료했다. 추가 모델·ViewModel·Navigation 변경은 필요하지 않다.
실행이 허용되는 환경에서 최종 변경 이후 빌드·instrumentation,
일반 draft 진입·닫기·복사·공유 및 canonical 진입점 제외를 확인해야 한다.
실제 라벨, 여러 페이지, 파일 누락, 수신 앱 동작 및 표 시각 확인도 남아 있다.

main 병합과 원격 push는 이번 구현 작업에서 수행하지 않았다.

## 15. .yeonsik 병합 후 다시 확인할 지점

- NutritionReviewScreen에 전달되는 isCanonicalReview의 의미
- uiState.nutritionDraft가 일반 draft인지 canonical projection인지
- USER_VERIFIED·편집 후 상태 초기화 및 기존 validator 계약
- 동일 문서의 pages 목록·파일 경로·FileProvider 설정
- 버튼 위치·busy 상태와 기존 일반 검수 확정 흐름

향후 필요하면 별도 CanonicalNutritionPublicationAdapter를 추가한다.
.yeonsik을 Publication 중간 포맷으로 사용하지 않는다.

## 16. 기술 부채

- 생성 PNG는 OS가 회수할 수 있는 cacheDir에 저장. 공유 직후 삭제하지 않으며 고정 보존 기간을 보장하지 않음
- dialog 생성물은 런타임 상태; 회전·프로세스 재시작 후 재생성 필요
- attachmentId-to-file 계약이 생기면 resolver 확장 필요
- 일반 검수 확정과 Fitness 전송 분리는 별도 작업
- 이미지 선택·편집, 템플릿, PublicationRecord/게시 URL 기록 미구현
- 실제 기기에서 레이아웃 및 수신 앱 호환성 확인 필요

## 17. 보장할 수 없는 부분

수신 앱의 제목·본문·이미지 처리 방식, 네이버 최종 게시 성공과 URL,
OCR·사용자 검증값 자체의 사실 정확성, 삭제·손상 원본 복구,
공유 URI의 장기 보존, 최종 integration 이후 빌드·기기 테스트 통과,
다른 브랜치 병합 후 UI 의미·저장 계약 유지 여부는 보장할 수 없다.

공유 성공은 공유 창을 연 것을 뜻한다. 게시 완료 상태를 기록하지 않는다.

## 리뷰 보완: 생성 자산과 원본 provenance

- 새 생성 표를 filesDir 영속 자산에서 cacheDir 임시 자산으로 변경했다.
- 기존 FileProvider에 publication_assets cache-path만 추가했다. 기존 files-path와 authority는 유지했다.
- 공유 직후 삭제 또는 원본 삭제 로직은 추가하지 않았다. cache 회수 시점은 Android가 관리한다.
- 구버전 filesDir에 이미 저장된 생성 자산의 일괄 삭제는 이번 수정에서 수행하지 않는다.
- pageIds가 없으면 명시적 provenance가 없다는 안내와 이미지 0개를 반환한다.
- 유효한 요청 pageId만 같은 문서의 실제 ReceiptPage로 resolve하며 중복 제거·SHA·파일 검증을 유지한다.
- 회귀 테스트를 보완했다: provenance 없음, 요청 ID 부분집합, 다른 문서·누락 ID, SHA·읽기·저장소 밖 경로,
  cache 위치·FileProvider URI·PNG signature·원본 바이트 보존, 렌더링·저장 실패 시 텍스트 유지.
- Nutrition domain, adapter/generator 구조, ReceiptScreens/ViewModel, canonical/.yeonsik 코드는 추가 수정하지 않았다.
