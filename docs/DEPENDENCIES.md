# Android, ML Kit 및 Gemini API 의존성 기록

기준일: 2026-08-11. 라이브러리 버전은 `gradle/libs.versions.toml`이 단일 소스입니다.

## 선택 근거

- Android Gradle Plugin 9.3 계열은 API 37을 지원하고 최소 Gradle 9.5 및 JDK 17을 요구합니다. 버그 수정 patch인 AGP 9.3.1과 저장소에서 검증한 Gradle 9.6.1을 사용하고 distribution SHA-256을 고정합니다. 2026-08-11 lint는 Gradle 9.7.0 가용성을 알리지만, wrapper 업그레이드는 AGP 호환과 전체 회귀를 별도 확인한 뒤 진행합니다. [AGP 9.3 release notes](https://developer.android.com/build/releases/agp-9-3-0-release-notes), [Gradle 9.6.1 release notes](https://docs.gradle.org/9.6.1/release-notes.html)
- Kotlin 2.4.10, Compose BOM 2026.06.01, Activity Compose 1.13.0, Lifecycle 2.11.0과 Core 1.19.0을 사용합니다. [Kotlin releases](https://kotlinlang.org/docs/releases.html), [Compose BOM](https://developer.android.com/develop/ui/compose/bom)
- Fragment 1.8.9를 ML Kit의 transitive 요청과 Activity Result 호환 stable release로 명시적으로 정렬합니다. [Fragment release notes](https://developer.android.com/jetpack/androidx/releases/fragment)
- AGP 9 built-in Kotlin과 KSP 2.3.9를 사용하며 Room 2.8.4 schema v4를 유지합니다. [Built-in Kotlin migration](https://developer.android.com/build/migrate-to-built-in-kotlin), [Room release notes](https://developer.android.com/jetpack/androidx/releases/room)
- ML Kit Document Scanner 16.0.0은 Play Services가 scanner UI와 모델을 동적으로 제공하며 full mode에서 갤러리, 경계 수정, 보정, JPEG/PDF를 지원합니다. [Document Scanner Android guide](https://developers.google.com/ml-kit/vision/doc-scanner/android)
- 한국어 Text Recognition v2 bundled model은 `com.google.mlkit:text-recognition-korean:16.0.1`입니다. 한국어, 라틴 문자, 숫자를 다루며 block/line/element 구조를 제공합니다. [Text Recognition v2 Android guide](https://developers.google.com/ml-kit/vision/text-recognition/v2/android)
- compileSdk와 targetSdk는 37, minSdk는 24입니다. Android 17 target 동작은 선택했지만 실제 Android 17 기기 회귀 검증은 별도입니다. [Android 17 SDK setup](https://developer.android.com/about/versions/17/setup-sdk)
- Coroutines 1.11.0과 Kotlin Serialization 1.11.0은 비동기 처리와 JSON 계약에 사용합니다. [Coroutines](https://github.com/Kotlin/kotlinx.coroutines), [Serialization](https://github.com/Kotlin/kotlinx.serialization/releases)
- Gemini는 유지 중단된 예전 Android Gemini SDK나 Firebase SDK를 추가하지 않고 플랫폼 `HttpURLConnection`으로 Interactions API를 직접 호출합니다. vendor-neutral 계약·정책은 `receipt-scanner/correction`, HTTP·이미지 crop·키 저장은 `app`에 둡니다. [Gemini libraries](https://ai.google.dev/gemini-api/docs/libraries), [Interactions API](https://ai.google.dev/gemini-api/docs/interactions-overview)
- 구조화 출력은 Interactions 요청의 `response_format`에 JSON Schema를 지정합니다. 스키마 통과 자체를 신뢰하지 않고 앱 정책으로 다시 검증합니다. [Gemini structured output](https://ai.google.dev/gemini-api/docs/structured-output)
- 기본 모델은 `gemini-3.5-flash-lite`입니다. 모델 가용성·무료 할당량·요금·데이터 사용 조건은 변경될 수 있으므로 실사용 전에 공식 문서를 다시 확인합니다. [Gemini model](https://ai.google.dev/gemini-api/docs/models/gemini-3.5-flash-lite), [Gemini pricing](https://ai.google.dev/gemini-api/docs/pricing)

## 유지보수 경계

- ML Kit 스캐너는 Google Play Services 및 지원 기기에 의존합니다. `unsupported`, `model_download_required`, `user_cancelled`, `capture_failed`를 정상 상태로 처리해야 합니다.
- bundled 한국어 OCR은 설치 크기를 늘리지만 런타임 원격 OCR API나 service key가 필요하지 않습니다.
- Gemini 네트워크는 사용자가 명시적으로 요청할 때만 사용합니다. 무료 tier를 무제한·영구 무료로 가정하지 않습니다.
- Gemini API key는 빌드에 포함하지 않고 Android Keystore로 암호화해 기기에 저장합니다. 직접 mobile API key가 production secret boundary가 될 수 없다는 한계는 별도입니다.
- Room schema 변경 시 migration과 복구 테스트가 필요합니다.
- compileSdk/targetSdk, AGP/Gradle, Kotlin/Compose는 공식 호환표와 전체 빌드·기기 테스트를 함께 갱신합니다.
- 새 의존성을 추가할 때 도메인 public API로 vendor 타입이 유출되는지 먼저 검사합니다.
