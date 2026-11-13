# 처방전·복약·전체 건강 정보 전달

HealthTrace는 공식 **약 제품**을 선택하고, 그 제품의 성분 여러 개를 연결한다. 제품 기준량과 개인의 1회 복용량은 별도 필드다. 처방전 한 번에 여러 약을 등록하면 동일한 `prescription_group_id`로 저장되며, 각 복용 기록은 당시 처방·제품 성분표를 고정한 `prescription_snapshot_json`을 보존한다.

## 등록 경로

1. HealthTrace → 건강 관리의 처방약 → **처방전 한 번에 등록**.
2. 직접 제품명 검색, 사진의 기기 내 한국어 OCR, 또는 OCR 처방 JSON 가져오기.
3. 병 상태와 증상, 처방일, 병원·약국을 확인한다. 기존 증상 관측은 명시적 ID로 연결한다. 자유 입력한 증상은 처방 당시의 사용자 설명이며 새 증상 관측 시각을 만들지 않는다.
4. 각 약의 제조사·제품명·성분표 기준량을 확인해 선택한다. 1회 수량·복용 단위는 처방전에서 확인해 입력한다. 성분표의 ‘1정’은 개인 복용량을 의미하지 않는다.
5. 저장 후 해당 약의 **복용 기록**에서 복용/건너뜀, 시각, 실제 수량을 기록한다.

동일 문서 ID를 다시 저장하면 중복 등록하지 않는다. 같은 처방전 내 동일 품목의 중복도 차단한다. 여러 약 저장과 원문 보관은 하나의 로컬 DB 트랜잭션이다. 기존 단일 처방·복약 기록은 schema 19의 추가 열만으로 호환되며 삭제·재생성하지 않는다.

## OCR App의 파일 형식

- 원본 사진: JPEG/PNG/WebP, 최대 25 MiB. OCR App **정보 보관함 → 원문 → HealthTrace에서 처방 사진 확인**. 원본은 OCR App에 유지하며 Android 읽기 권한이 있는 `content://` URI로 해당 앱에만 전달한다. HealthTrace의 한국어 OCR은 기기에서 실행한다.
- 구조화 파일: UTF-8 `.json`, 최대 1 MiB. 최상위는 기존 `information-record.v1`, `owner_app=healthtrace`, `record_type=healthtrace.prescription`. `fields`의 `prescription` JSON 값만 `healthtrace.prescription.v1` 구조를 사용한다. 예시: [health-prescription.example.json](examples/health-prescription.example.json).
- OCR App **구조화 JSON 가져오기 → 소유 앱·출처 검토 완료 → HealthTrace에서 처방전 등록**. 기존 원격 HealthTrace 검토함 전송도 유지된다. 수신함의 **처방전 확인·등록**으로 같은 검토 화면을 연다.
- `.yeonsik` 영수증 번들에 건강 정보를 넣지 않는다. 병원·약국 장소와 결제는 기존 PriceTrace/CashOS 경로를 사용하고 개인 약·증상·진단을 공개 장소 정보에 복제하지 않는다.
- `prescribed_on`, 제조사, 병원, 약국, 1회 수량, 횟수, 기간 등이 원문에 없으면 `null`이다. 성분명·공식 품목번호를 LLM이 추측하지 않는다. 후보 이름은 HealthTrace에서 공식 조회 후 선택한다. 처방전은 실제 복용 증거가 아니다.
- 원본 사진의 사람 이름·연락처·환자번호는 필요하지 않은 구조화 필드로 옮기지 않는다. 파일 참조는 원문 ID/이름/SHA-256으로 유지한다. 생산자가 주장한 해시는 원본 대조 전까지 미검증이다.

## ChatGPT 프로젝트 파일

[프로젝트 추가 지침](producer-candidate/09_HEALTH_PRESCRIPTION_PROJECT_INSTRUCTIONS.md), [처방 JSON 예시](examples/health-prescription.example.json), 이 계약 문서를 프로젝트 파일에 추가한다. 기존 영수증·영양 V1–V5 지침은 유지한다. 이 추가 지침은 건강 자료의 분기만 지정한다. 기존 온라인 ChatGPT 프로젝트의 원본 지침은 이 저장소에 없으므로 온라인 프로젝트가 자동 수정되었다고 간주하지 않는다.

## 하나의 건강 JSON

**AI 분석 파일**은 `healthtrace.analysis-bundle.v2` JSON 하나로 수동 수면·수면 체크인, 웨어러블 수면 세션/단계/원래 시간대, 수면 연결, 영양제 제품/계획/섭취, 병 상태, 증상, 하루 확인, 처방/복약, OCR·수신 정보와 관측 연결, 건강 참고 메모를 전달한다. 기본값은 저장된 전체 기간이다. 요약 통계만 화면에서 고른 최대 366일을 사용한다.

`records`의 자료는 출처별로 분리한다. 웨어러블과 수동 수면을 두 번 합산하지 않는다. `information_records.record.payload_json`은 큰 수·중첩 JSON 정밀도를 유지하기 위한 원문 JSON 문자열이다. 미검토 정보·원문·projection 영수증은 추가 관측 사건이 아니다. 삭제 본문, 자격증명, 임시 초안, 다른 앱의 식단 캐시, 사진 바이너리는 제외한다. 사진은 원문 참조와 추출 텍스트로 연결한다. 8 MiB 초과 시 명시적 오류를 내고 부분 파일을 만들지 않는다. 공유는 사용자 조작으로 수행하며 GitHub 자동 미러 경로는 바뀌지 않는다.

## 식약처 API 설정

공식 자료: [공공데이터포털 의약품 제품 허가정보](https://www.data.go.kr/data/15095677/openapi.do). 로그인 → 활용신청 → 승인된 일반 인증키(Decoding)를 사용한다. 실제 검증한 서비스는 `DrugPrdtPrmsnInfoService08`의 목록·상세 조회다. 상세의 `MATERIAL_NAME`과 `TOTAL_CONTENT`를 구분하여 내수/수출 등의 성분표를 제공한다.

서버 비밀값은 `MFDS_SERVICE_KEY`다. 개인 `.env.drug.local`은 Git 제외 대상이다. Supabase CLI의 `secrets set --env-file .env.drug.local --project-ref <HealthTrace project>`로 등록하고 `functions deploy medicine-catalog --project-ref <HealthTrace project> --use-api`로 배포한다. 앱 빌드에는 기존 `HEALTHTRACE_SUPABASE_URL`, `HEALTHTRACE_SUPABASE_PUBLISHABLE_KEY` 공개 설정만 사용한다. 식약처 키·service role 키는 APK에 넣지 않는다.

공개 제품 데이터만 조회 서버로 보내며, 사진·환자 정보·증상은 전송하지 않는다. 검색 캐시 15분/상세 캐시 24시간, 인스턴스당 1분 100회 제한으로 쿼터를 완화한다. 이는 전역 분산 쿼터 보장은 아니다. 카탈로그 장애 시 기존 처방·복용 기록과 최근 선택 제품은 로컬에서 사용할 수 있다.
