# 피트스탑 (PitStop) 0.4.0

이용 가능한 공중화장실과 주차장을 찾고, 이용 불가 시 다음 후보와 원래 목적지 안내까지 연결하는 Pleos 차량용 앱입니다.

## APK 및 설치
- 출품팀: 화장실수호대
- APK: [apk/화장실수호대_PitStop_release.apk](apk/화장실수호대_PitStop_release.apk)
- 패키지: `ai.pleos.pitstop`
- 버전: `0.4.0` / versionCode `4`
- SHA-256: `fc5e47a18d032678ccb846ffda7d18073c4ddedd6aa47646896b3aef049d198d`

Pleos Connect 에뮬레이터를 실행하고 Android SDK Platform Tools의 adb를 PATH에 등록합니다.
저장소 루트에서 다음을 실행합니다. 이미 같은 패키지의 다른 서명 APK가 설치되어 있다면
기존 앱과 제출 APK의 서명이 달라 업데이트가 실패할 수 있습니다. 테스트 데이터를 보관한 뒤 기존 설치를 정리하거나 깨끗한 에뮬레이터를 이용합니다.

```sh
adb devices
adb install -r "apk/화장실수호대_PitStop_release.apk"
adb shell am start -n ai.pleos.pitstop/.MainActivity
```

여러 기기가 연결되어 있다면 `adb -s 기기ID`로 대상 기기를 지정합니다.
일반 휴대전화용 앱이 아니라 Android Automotive와 Pleos 차량 서비스를 전제로 합니다.

## 실행 및 필요한 설정
1. 홈의 **PitStop** 아이콘을 실행하고 P단에서 최초 권한 요청을 처리합니다.
2. 화면의 예제 기능으로 후보 비교·대안·복귀 흐름을 확인합니다. 예제 데이터는 가상입니다.
3. Gleo 연동을 갖춘 환경에서는 “피트스탑, 화장실 안내해줘” → “피트스탑, 여기 닫혔어” → “피트스탑, 원래 목적지로 가자” 순서로 시험합니다.
4. 실제 음성 인식과 스키마 등록·승인은 별도 검증 대상입니다. 원래 목적지 조회가 실패하면 목적지를 덮어쓰는 새 내비 요청을 진행하지 않습니다.

차량 기능별 지원 여부와 권한, P단 및 Car UX 제한을 확인해야 합니다.
권한을 강제로 부여하는 우회 절차는 사용하지 않습니다.

## 포함 기능과 한계
- 공중화장실의 일반 이용 가능 여부와 제한 조건을 확인해 후보를 선정합니다.
- 화장실 탐색, 이용 불가 후보 제외, 원래 목적지 안내 재개를 지원하는 앱 측 Gleo 함수가 포함되어 있습니다.
- 수원 파일럿 시설 데이터 4건이 포함됩니다. 실제 내비 진입구·운영시간·대기시간이 모두 검증된 자료는 아닙니다.
- 가상 입력 예제 모드는 실제 내비게이션을 바꾸지 않습니다. 실제 경로 시간 자료가 부족하면 TTT를 만들어 표시하지 않습니다.

## 개발 및 테스트 환경
원본 패키지에 기록된 개발 대상 환경입니다. 이번 재패키징 작업에서 에뮬레이터를 실행해 확인한 값은 아닙니다.

| 항목 | 값 |
| --- | --- |
| Pleos Connect 에뮬레이터 | 2.1.0 / AVD Pleos_Coach |
| Android / ABI | API 34 / x86_64 |
| 화면 | 2560 × 1440 px / 가로 / 160 dpi |
| JDK / Kotlin | 17 / 2.0.0 |
| Android Gradle Plugin / Gradle | 8.8.2 / 8.10.2 |
| compileSdk / minSdk / targetSdk | 35 / 33 / 34 |
| Android Build Tools | 35.0.0 |
| Pleos SDK | NaviHelper 3.0.5, Vehicle 2.3.2 |

## 빌드 및 검사
JDK 17과 Android SDK 35를 설치하고 `local.properties`의 `sdk.dir`에 SDK 경로를 설정합니다.
필요한 의존성 저장소는 `settings.gradle.kts`에 선언되어 있습니다.

```sh
./gradlew :pitstop:assembleDebug
./gradlew :pitstop-core:test :pitstop:testDebugUnitTest :pitstop:lintDebug
```

Windows에서는 `gradlew.bat` 또는 `BUILD_PITSTOP.cmd`를 사용합니다.
기존 BUILD 파일은 debug 빌드·검사용이며 제출 APK를 새로 만드는 명령이 아닙니다.

release 재빌드는 `signing.properties.example`을 참고해 본인의 서명 키 설정을 만든 뒤
`gradlew.bat :pitstop:assembleRelease`를 실행합니다.
산출물은 `pitstop/build/outputs/apk/release/`에서 확인합니다.
서명 키와 비밀번호는 포함하지 않았습니다. 기존 제출 APK의 서명을 유지하려면 기존 키가 필요합니다.

원본 검증 기록에는 단위 테스트 67개, 실패·오류 0개와 lint 오류 0개가 기록되어 있습니다.
기존 실행 환경에서 Gradle 테스트 프로세스 연결 문제가 있어 JUnitCore로 검사했다는 기록입니다.
이는 원본 기록이며 이번 패키징에서 테스트를 재실행한 결과가 아닙니다.

이번 패키징에서는 APK 파일 해시, v2 서명과 서명된 내용의 무결성, manifest 버전과 debuggable 비활성 상태를 확인했습니다.
**에뮬레이터 설치·실행, 실제 음성·차량 제어·내비게이션 동작은 미검증입니다.**

## 소스 구성
- `pitstop/`: Android 화면·서비스·Pleos 연동
- `pitstop-core/`: 판단 및 상태 관리 코어
- `apk/`: 제출 release APK
- `licenses/`: 포함 글꼴의 라이선스

이 폴더에는 소스코드·빌드 파일, release APK, README와 포함 글꼴의 라이선스가 있습니다.
이 폴더의 내용을 앱별 GitHub 저장소 루트에 올리거나, 폴더를 압축해 내려받을 수 있는 링크로 제출할 수 있습니다.
