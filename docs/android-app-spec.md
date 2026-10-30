# Senior Care Android 앱 기술 명세 v0.6

> 개정 기준일: 2026-10-30. 화면·권한·상태 처리와 인수 기준을 정의한다.

## 관련 저장소

| 저장소 | 주소 | 이 문서와의 관계 |
|---|---|---|
| 로봇 본체 | https://github.com/next-year-robotics/mobile-robot | STM32 펌웨어, micro-ROS, RPi5 ROS 2 패키지, 폰 브리지 서버. 이 문서의 로봇 측 구현이 여기 들어간다. |
| Android 앱 | https://github.com/next-year-robotics/senior-care-android | 이 문서가 사는 저장소. 앱 구현 전체. |
| 웹 대시보드 | https://github.com/next-year-robotics/senior-care-dashboard | 보호자용 웹/PWA. 앱과 별도 제품이며 이 문서의 프로토콜을 공유하지 않는다. |

로봇 기준: `mobile-robot/main` `67a3839`. 계약 사본의 출처는 `contracts/SOURCE`에 기록한다.

이 문서는 앱을 ROS 2나 ArUco 구현을 몰라도 독립적으로 완성할 수 있게 **앱 쪽 규격**을 고정한다. 앱과 RPi5 사이의 프로토콜은 이 문서가 아니라 **`contracts/phone-bridge/v1/PROTOCOL.md`**(정본은 mobile-robot `software/contracts`)가 정한다. 확정되지 않은 내용은 `[OPEN-*]`으로 표시한다.

---

## 1. 프로젝트 개요

### 1.1 제품 한 줄 정의

어르신이 로봇과 함께 미리 지정해 둔 장소로 이동할 때 쓰는 앱으로, 휴대폰이 로봇의 상태를 보여 주고 로봇이 요청하면 즉시 위치를 회신한다.

핵심 사용자는 어르신이므로 설정 화면은 복잡해도 되지만 매일 쓰는 메인 화면은 단순해야 한다. 이 원칙이 다른 UI 판단보다 우선한다.

### 1.2 개발 규칙

- CI의 빌드·단위 테스트·lint를 통과한 변경을 병합한다.
- 지도 키·서명 키·핫스팟 비밀번호·실제 사용자 좌표는 커밋하지 않는다.
- `contracts/`는 mobile-robot의 사본이다. 프로토콜은 정본에서 변경한 뒤 `scripts/sync-contracts.sh`로 동기화한다.
- Android 작업에서 로봇의 ROS 2 패키지와 펌웨어를 수정하지 않는다.

### 1.3 앱은 ROS 2에 직접 접속하지 않는다

앱은 `rosbridge_server`에 직접 붙지 않는다. 제품 경로는 RPi5의 전용 WebSocket 게이트웨이다.

```text
[Android 앱]
  UI / 위치 캐시 / ForegroundService / WebSocket client
                   │
                   │ phone-bridge/1 JSON
                   ▼
[RPi5: mr_phone_bridge]
  WebSocket server / 검증 / 중복 제거 / places.yaml / ROS 변환
                   │
                   │ typed ROS 2 topics
                   ▼
[mission / safety / future ArUco nodes]
                   │
                   ▼
[현재 base stack: /cmd_vel, /joint_states, /base/status, /odom]
```

- 앱에는 ROS 2 라이브러리, ROS 메시지 타입, 토픽 이름을 노출하지 않는다.
- 앱 개발자는 이 문서의 JSON과 mock bridge만으로 개발·테스트한다.
- WebSocket 클라이언트가 임의의 ROS 토픽, 특히 `/cmd_vel`에 접근하지 못하게 한다.
- ROS 토픽이나 ArUco 내부 구현이 바뀌어도 RPi5 어댑터만 수정한다.

`rosbridge_suite`는 로봇 담당자의 로컬 디버깅에만 쓸 수 있으며 앱의 제품 의존성이 아니다.

---

## 2. 범위

### 2.1 포함

- 어르신이 큰 타일에서 목적지를 고르고 주행 시작 또는 취소를 요청한다.
- 보호자가 설정 화면에서 장소 프리셋을 국내 지도 검색 또는 지도 핀 이동으로 등록·수정·삭제한다.
- 앱에서 지정한 목적지 좌표와 반경은 RPi5에 동기화하며, ROS 2 mission 계층이 목적지 설정과 도착 판단에 사용한다.
- RPi5의 현재 상태와 연결 상태를 앱에 표시한다.
- RPi5가 SOS를 요청하면 앱이 즉시 수신 확인을 보내고 캐시 위치와 새 위치를 순서대로 회신한다.
- 앱이 화면 밖으로 이동하거나 화면이 꺼져도 사용자가 시작한 연결 세션 동안 소켓과 위치 기능을 유지한다.
- 실제 RPi5 없이 개발할 수 있도록 mock 실행 도구와 프로토콜 fixture를 앱 저장소에 둔다.
- 향후 ArUco 추적 노드가 SOS를 발행할 수 있는 ROS 2 연결 지점을 미리 정의한다.

### 2.2 제외

- ArUco 인식·추적 알고리즘과 추종 제어
- `/cmd_vel` 생성, 모터 정지, 경로 계획, Nav2
- 폰 GPS를 로봇의 주행 제어 또는 로컬라이제이션 입력으로 사용하는 기능
- 앱 안의 경로 안내(턴바이턴 내비게이션). 지도는 프리셋 좌표 지정 용도로만 쓴다.
- 자동 전화, 자동 SMS, 보호자 서버 전송
- 웹 대시보드 구현, iOS 앱, 스토어 출시 작업

### 2.3 안전 원칙

1. 폰 브리지의 실패가 기존 추종 또는 베이스 제어 루프를 직접 중단시키면 안 된다.
2. 앱과 브리지는 `/cmd_vel`을 발행하지 않는다.
3. 폰 GPS는 SOS 참고 정보와 사용자 화면 표시용이다. 제어 루프에 넣지 않는다.
4. 앱의 `sos.ack`는 "메시지를 받았다"는 뜻이지 "보호자에게 구조 요청이 전달됐다"는 뜻이 아니다.
5. 안전 정지와 로컬 부저 같은 최종 폴백은 폰이 아니라 로봇이 소유한다.

---

## 3. 로봇 연동 기준 (2026-10-30)

앱이 알아야 하는 로봇 쪽 사실은 아래가 전부다. 나머지는 브리지가 숨긴다.

| 항목 | 상태 |
|---|---|
| ROS 2 배포판 | Jazzy (Ubuntu 24.04, RPi5) |
| 베이스 | `/cmd_vel`(TwistStamped, twist_mux가 유일 발행자), `/joint_states` 50 Hz, `/base/status` 10 Hz, `/odom` + TF |
| ArUco | `mr_aruco` 패키지: `marker_track`(`mr_aruco_msgs/MarkerTrack`: LOST/TRACKING/HOLDING), follow/align 노드, `AlignToMarker` 액션. 로봇 저장소에서 구현 |
| 상위 인터페이스 | `mr_care_msgs`(TripCommand, PhoneLocation, PhoneLinkStatus, PlaceCatalog, RobotAppStatus, SosRequest, SosClear, SosAck)  |
| 폰 브리지 | `mr_phone_bridge` 서버·ROS 어댑터·mock bridge 구현 |
| mission 계층 | `mr_mission`에서 주행·정지·추적 상실 SOS·앱 연결 상실 정지 처리. **도착 판정 수단 미결(OPEN-N1)** |
| 배터리 | 인터페이스 없음 → `battery_percent=null` |

앱은 위 토픽을 직접 전제로 삼지 않는다. 앱에 필요한 상위 상태는 브리지가 `robot.status`로 보내며, 아직 제공할 수 없는 값은 `null` 또는 `unavailable`/`unknown`으로 온다.


## 4. 담당 범위와 산출물

### 4.1 Android 개발자 담당

- Kotlin 기반 Android 앱 전체
- Jetpack Compose UI와 아래 화면 전부(어르신 홈, 설정, 지도 프리셋 편집 포함)
- WebSocket 연결, 재연결, heartbeat, 메시지 검증과 직렬화
- 포그라운드 서비스, 알림, 위치 권한, 위치 캐시, SOS용 새 측위
- 국내 지도 SDK 연동, 키워드 장소 검색, 핀 지정, 역지오코딩
- 프로토콜 단위 테스트와 상태 머신 테스트
- 실제 RPi5 없이 실행 가능한 mock bridge 연결 도구
- 디버그/릴리스 빌드, 기기 설치, 지도 키 주입, 권한별 동작을 담은 `README.md`
- 접근성 검사와 지정된 실기기 시험 결과

Android 개발자가 하지 않는 것: `mobile-robot` 저장소의 ROS 2 패키지·펌웨어 수정, ROS 2 메시지 설계 변경, ArUco 노드 구현, 모터 명령·안전 정지 로직 구현.

### 4.2 로봇/ROS 2 담당자 담당

- `mr_phone_bridge` WebSocket 서버와 ROS 2 어댑터
- `mr_care_msgs`의 상위 인터페이스 메시지 관리
- `places.yaml` 저장·검증·원자적 갱신
- bridge용 launch/config/systemd 구성
- ArUco 추적 노드의 상태 및 SOS 요청 연결
- 앱 mock과 같은 동작을 하는 실제 RPi5 통합 시험 환경
- ACK 3회 실패 시 로컬 안전 폴백 결정과 구현
- 지도 API 키 발급과 전달

### 4.3 공동으로 확인할 것

- 프로토콜 v1 fixture의 실제 값
- 지원할 Android 실기기와 OS 버전
- 핫스팟 환경의 mDNS 동작 여부
- SOS 500 ms 응답 측정 방법
- 각 마일스톤의 시연과 인수

---

## 5. Android 기술 기준

### 5.1 권장 스택

| 항목 | 기준 |
|---|---|
| 언어 | Kotlin |
| UI | Jetpack Compose + Material 3 (Material 3 Expressive 컴포넌트 사용 가능) |
| 비동기/상태 | Kotlin Coroutines + `Flow`/`StateFlow` |
| WebSocket | OkHttp WebSocket 또는 동등한 유지보수 라이브러리 |
| JSON | `kotlinx.serialization` |
| 위치 | Google Play services Fused Location Provider |
| 지도 | Kakao Map Android SDK (기본) — 6장 참조 |
| 장소 검색/역지오코딩 | Kakao Local REST API (기본) — 6장 참조 |
| 소규모 설정 저장 | Jetpack DataStore |
| 테스트 | JUnit, coroutine test, Compose UI test |

의존성 주입 프레임워크 사용 여부는 개발자가 선택하되, 네트워크·위치·지도·시간 제공자는 인터페이스로 분리해 테스트 대역을 주입할 수 있어야 한다.

### 5.2 SDK 기준 (확정)

- `applicationId`: `com.nextyearrobotics.seniorcare` (debug는 `.debug` 접미)
- 지원 기기: **Galaxy S26 Ultra 1대**(개발·시연 공용). `OPEN-A1` 종결.
- `minSdk` **36**, `compileSdk` **37.2**(`compileSdk = 37`, `compileSdkMinor = 2`), `targetSdk` **37**. `app/build.gradle.kts`가 정본이며 변경 시 이 줄을 고친다. targetSdk 37이므로 11.2의 `ACCESS_LOCAL_NETWORK` 런타임 권한 흐름이 적용된다.
- 빌드: Gradle 9.8 wrapper, AGP 9.4(built-in Kotlin), Kotlin 2.4, Compose BOM 2026.09 — `gradle/libs.versions.toml`.
- 화면 기준: 세로 방향, 최소 폭 360 dp. 큰 글꼴 200%에서 핵심 동작이 잘리면 안 된다.
- 기본 언어: 한국어. 사용자에게 보이는 문자열은 모두 resource로 분리한다.


### 5.3 권장 코드 경계

작은 앱이므로 처음부터 다중 Gradle 모듈을 강제하지 않는다. 단일 `app` 모듈에서도 패키지 경계는 유지한다.

```text
app/
  ui/              Compose screen, component, theme
  domain/          Trip/SOS/Place/connection state and use cases
  protocol/        phone-bridge/1 DTO, codec, validation
  network/         discovery, WebSocket session, reconnect
  location/        permission, cache, fresh fix
  places/          map SDK, place search, geocoding, preset editing
  service/         foreground service and notification
  data/            settings and repository implementations
```

`ui`가 WebSocket 객체, 위치 API, 지도 SDK를 직접 호출하면 안 된다. UI는 domain 상태와 사용자 action만 주고받는다.

---

## 6. 지도와 장소 검색

프리셋 좌표를 지정하려면 국내 지도가 필요하다. **Kakao로 확정했다(2026-09-27)**: 키워드 검색이 페이지당 15건(Naver 지역검색은 5건), 결제수단 등록 없이 키 발급, 이 문서가 이미 Kakao 기준. Google Maps는 국내 장소 검색·도로 데이터 제약이 있어 쓰지 않는다.

### 6.1 기본 선택: Kakao

| 용도 | 수단 | 비고 |
|---|---|---|
| 지도 표시·핀 지정 | Kakao Map Android SDK (v2 계열 최신 안정 버전) | 네이티브 앱 키 필요. 정확한 아티팩트 버전은 PR에 기록한다. |
| 키워드 장소 검색 | Kakao Local REST API `GET /v2/local/search/keyword.json` | 헤더 `Authorization: KakaoAK {REST_KEY}`. 응답의 `x`=경도, `y`=위도, WGS84. |
| 좌표 → 주소 | Kakao Local REST API `GET /v2/local/geo/coord2address.json` | 핀 위치의 도로명 주소 표시용. |

대안은 Naver Maps Android SDK + Naver 지역 검색/지오코딩이다. 좌표계와 화면 흐름이 같으므로 `places/` 패키지 안의 인터페이스만 갈아 끼울 수 있게 구현한다. 즉 지도·검색은 `PlaceSearchProvider`, `MapPinPicker` 같은 앱 내부 인터페이스 뒤에 둔다.

모든 좌표는 WGS84(EPSG:4326) 십진도로 다루며 화면·프로토콜·`places.yaml` 어디에서도 다른 좌표계를 쓰지 않는다.

### 6.2 키 취급

- 네이티브 앱 키와 REST 키는 `local.properties`에 두고 `BuildConfig`로 주입한다. 저장소에 커밋하지 않는다.
- CI는 더미 키로 빌드가 통과해야 한다. 키가 없으면 지도 화면만 "지도를 사용할 수 없음"으로 열화되고 앱 전체가 죽으면 안 된다.
- `[OPEN-M1]` 앱에 REST 키를 넣으면 추출될 수 있다. 현장 릴리스 전에 키 사용량 제한을 걸지, RPi5나 대시보드 백엔드를 검색 프록시로 둘지 결정한다. MVP 개발은 앱 직접 호출로 진행한다.

### 6.3 인터넷 연결 전제

RPi5는 휴대폰 핫스팟에 접속하고, 휴대폰은 셀룰러로 인터넷에 나갈 수 있다. 따라서 로봇에 연결된 상태에서도 지도 타일과 검색 API는 정상 동작한다.

그래도 지도·검색은 실패할 수 있으므로 폴백을 반드시 구현한다.

- 검색 실패: 지도에서 핀으로 직접 지정
- 지도 실패: 위도·경도 직접 입력 필드
- 두 경로 모두 같은 저장 흐름(PROTOCOL.md 4.3 `place.upsert`)을 쓴다.

---

## 7. 네트워크·프로토콜·프리셋·위치·SOS — 계약 문서 참조

이 네 주제(v0.4의 7·8·9·10장)는 **`contracts/phone-bridge/v1/PROTOCOL.md`**로 옮겼다. 정본은 mobile-robot `software/contracts/phone-bridge/v1`이고 이 저장소의 복사본은 `scripts/sync-contracts.sh`로 갱신한다. 기계가 읽는 정본은 `schema/messages.schema.json`과 `fixtures/*.json`(26개)이며 앱 단위 테스트가 그대로 읽는다.

앱 구현자가 특히 봐야 하는 절:

| PROTOCOL.md | 내용 | 앱 쪽 구현 위치 |
|---|---|---|
| 2장 | `ws://mobile-robot.local:8765/bridge/v1`, mDNS `_mr-phone-bridge._tcp`, 발견 순서, 수동 endpoint, 보안 단계 | `network/` |
| 3장 | envelope, `hello`/`hello.ack`, `ack`/`error` 11개 코드, heartbeat 2 s/10 s, 재연결 backoff | `protocol/`, `network/` |
| 4장 | 프리셋 모델, `place.catalog`, `place.upsert`/`place.delete`, `trip.start`/`trip.cancel`, `robot.status` enum | `domain/`, `places/` |
| 5장 | 위치 캐시 30 s, `location.update`, `sos.request`/`sos.ack`/`sos.clear`, 500 ms·30 s 시간 제한 | `location/`, `service/` |
| 7.1 | mock bridge 시나리오 12종 | `tools/mock-bridge/run.sh` |

앱이 지켜야 할 요약(자세한 값은 PROTOCOL.md가 우선):

- 앱은 ROS 토픽을 모르고 `/cmd_vel`을 만들지 않는다. 폰 GPS는 SOS 참고 정보다.
- 알 수 없는 `type`·enum은 crash 없이 `unknown`으로. 필수 필드 누락은 `INVALID_MESSAGE`.
- 같은 `id` 재전송에 side effect를 반복하지 않는다. command는 연결 끊김 중 큐잉하지 않는다.
- `sos.request`에는 `sos.ack`와 캐시 위치를 각각 500 ms 안에, 새 측위는 최대 30 s.
- `robot.status`가 5 s 동안 없으면 `stale` 표시. 로봇이 멈췄다고 앱이 단정하지 않는다.


### 앱 평문 연결 정책 (2026-09-27)

Android XML은 IP 대역을 표현할 수 없어 cleartext를 허용하고, 브리지 transport가 ws 목적지를 사설 IPv4(RFC1918), 링크로컬, IPv6 ULA 리터럴로 제한한다. DNS 이름은 NSD에서 IP로 해석한 후 전달한다. debug만 loopback/localhost를 추가 허용한다. redirect는 금지한다. 지도·검색은 HTTPS만 사용한다. 이 검사는 개인 핫스팟 여부를 인증하지 않으며 계약의 개인 핫스팟 전용 운영 전제와 OPEN-R1은 그대로 유지한다.

## 11. Android 백그라운드 실행과 권한

### 11.1 세션 생명주기

1. 사용자가 앱 화면에서 `로봇 연결 시작`을 누른다.
2. 앱이 대상 SDK에 필요한 로컬 네트워크 권한, 위치 권한, 알림 권한을 순서대로 요청한다.
3. 위치 권한이 허용되고 기기의 위치 서비스가 켜진 것을 확인한 뒤, 화면이 보이는 상태에서 location type ForegroundService를 시작한다.
4. 서비스가 WebSocket, heartbeat, 재연결, 위치 캐시, SOS 처리를 소유한다.
5. 사용자가 `연결 종료`를 누르거나 ongoing notification의 종료 action을 누르면 서비스를 종료한다.

Android 12 이상에서는 백그라운드에서 임의로 ForegroundService를 시작할 수 없고, Android 14 이상 target에서는 서비스 유형과 해당 권한이 맞아야 한다. 그러므로 최초 시작은 반드시 보이는 Activity에서 사용자 action으로 수행한다.

어르신이 매번 이 절차를 밟지 않도록, 마지막에 연결됐던 로봇이 있으면 앱 실행 시 홈 화면에서 버튼 한 번으로 재연결되게 한다. 권한이 이미 허용돼 있으면 추가 다이얼로그를 띄우지 않는다.

위치 권한이 없거나 기기의 위치 서비스가 꺼져 있으면 연결 세션을 시작하지 않는다. 앱은 위치가 SOS의 필수 기능임을 설명하고 권한 요청 또는 시스템 설정으로 이동하는 경로를 제공한다.

### 11.2 Manifest/런타임 권한

| 권한/설정 | MVP | 용도 |
|---|---:|---|
| `INTERNET` | 필수 | WebSocket, 지도 타일, 장소 검색 |
| `ACCESS_NETWORK_STATE` | 필수 | 연결 상태 감지 |
| `ACCESS_LOCAL_NETWORK` | API 37+ 런타임 필수 | RPi5 직접 연결, `.local` 해석, mDNS/`NsdManager` 탐색 |
| `ACCESS_COARSE_LOCATION` | 런타임 필수 | 연결 세션과 위치 기능의 기본 권한 |
| `ACCESS_FINE_LOCATION` | 요청 | SOS 정확 위치와 지도 "현재 위치", 거절 시 approximate로 열화 |
| `FOREGROUND_SERVICE` | 필수 | 서비스 |
| `FOREGROUND_SERVICE_LOCATION` | 대상 SDK에서 필수 | location FGS |
| `POST_NOTIFICATIONS` | API 33+ 요청 | ongoing/SOS 알림 |
| `WAKE_LOCK` | 필수 | SOS 새 측위의 짧은 처리 구간 |
| `ACCESS_BACKGROUND_LOCATION` | 기본 제외 | 현재 설계는 실행 중인 location FGS 사용 |
| `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | 제외 | 정책·기기 시험 없이 직접 예외 요청 금지 |

정확 위치가 거절되고 approximate 위치만 허용돼도 앱은 연결과 trip 기능을 제공하되 SOS 화면에는 `정확한 위치 권한이 없어 위치 정확도가 낮을 수 있음`을 표시한다. 모든 위치 권한이 거절됐거나 위치 서비스가 꺼져 있으면 연결 세션을 시작하지 않는다. 실행 중 권한이 회수되거나 위치 서비스가 꺼지면 가능한 경우 `source=none`을 한 번 보내고 세션을 종료한 뒤 권한 안내 화면으로 이동한다.

포그라운드 서비스를 종료한 뒤에도 위치를 수집해야 한다는 별도 요구가 생기기 전에는 `ACCESS_BACKGROUND_LOCATION`을 요청하지 않는다. 배터리 최적화 예외 역시 실기기 장시간 시험에서 핵심 기능 문제가 재현되고 배포 정책상 허용될 때만 별도 결정한다.

### 11.3 알림

- 연결 서비스 notification channel과 긴급 SOS channel을 분리한다.
- 연결 알림은 `로봇 연결 중`, robot id, 연결 상태를 표시하고 `연결 종료` action을 제공한다.
- SOS 알림은 높은 중요도, 소리와 진동을 사용하되 사용자의 시스템 설정을 존중한다.
- 알림 탭 시 기존 Activity의 SOS 화면으로 이동하며 중복 Activity를 쌓지 않는다.
- 알림 권한이 거절돼도 앱 내부 SOS 화면과 프로토콜 ACK는 동작해야 한다.

---

## 12. 앱 화면과 상태 모델

### 12.1 두 개의 층으로 나눈다

이 앱에는 성격이 다른 두 층이 있다. 이 구분을 코드와 내비게이션에서도 유지한다.

| 층 | 사용자 | 사용 빈도 | 설계 원칙 |
|---|---|---|---|
| **사용 층** (홈 → 확인 → 주행 중 → SOS) | 어르신 | 매일 | 화면당 결정 1개, 큰 글씨, 큰 버튼, 스크롤 최소화 |
| **설정 층** (프리셋 관리, 지도 편집, 연결, 진단) | 보호자·설치자 | 초기 설정과 가끔 | 정보가 많아도 된다. 항목 수와 단계 수를 줄이려고 기능을 빼지 않는다 |

어르신이 목적지를 눌러 출발하기까지 최대 2번 탭한다. 목적지 타일 1탭 + 확인 화면의 출발 1탭이 전부다.

### 12.2 사용 층 화면

#### 홈 (기본 화면)

```text
┌────────────────────────────────┐
│  ● 로봇과 연결됨          [⚙]  │  ← 상태 1줄(아이콘+글자), 톱니는 길게 눌러야 열림
├────────────────────────────────┤
│   어디로 갈까요?                │
│                                │
│   ┌──────────┐  ┌──────────┐   │
│   │   🏠     │  │   🏪     │   │  ← 타일: 아이콘 + 라벨
│   │   집     │  │   시장   │   │
│   └──────────┘  └──────────┘   │
│   ┌──────────┐  ┌──────────┐   │
│   │   🛒     │  │   🏛     │   │
│   │   마트   │  │  노인정  │   │
│   └──────────┘  └──────────┘   │
│   ┌──────────┐  ┌──────────┐   │
│   │   ⛪     │  │   🛕     │   │
│   │   교회   │  │    절    │   │
│   └──────────┘  └──────────┘   │
├────────────────────────────────┤
│  출발: 집               바꾸기 │  ← 보조 줄. 평소엔 건드릴 일이 없다
└────────────────────────────────┘
```

- 타일은 2열 grid, 최소 높이 140 dp, 아이콘 56 dp 이상, 라벨 24 sp 이상.
- 프리셋 6개 이하를 권장한다. 그보다 많으면 세로 스크롤하되 `sort_order` 순서를 지킨다.
- `enabled=false` 프리셋은 흐리게 표시하고 누르면 이유를 알려 준다. 사라지게 만들지 않는다.
- 출발지는 `default_origin_place_id`를 자동으로 쓴다. `바꾸기`를 누르면 같은 모양의 타일 목록에서 고른다.
- 연결이 끊겨 있으면 상단 상태 줄이 `로봇을 찾는 중` / `연결 안 됨 · 다시 연결`로 바뀌고, 타일은 비활성이 되며 큰 재연결 버튼이 뜬다.
- 아이콘은 이모지가 아니라 Material Symbols 등 벡터 아이콘을 쓴다. 위 그림은 배치 설명용이다.

`kind` → 기본 아이콘·기본 라벨 매핑:

| kind | 기본 라벨 | 아이콘 성격 |
|---|---|---|
| `home` | 집 | 집 |
| `market` | 시장 | 전통시장/가게 |
| `mart` | 마트 | 장바구니 |
| `senior_center` | 노인정 | 사람들이 모인 건물 |
| `church` | 교회 | 교회 |
| `temple` | 절 | 절 |
| `hospital` | 병원 | 의료 |
| `pharmacy` | 약국 | 약 |
| `other` | 장소 | 지도 핀 |

라벨은 사용자가 바꿀 수 있고 바뀐 라벨이 항상 우선한다.

#### 출발 확인

- `집에서 시장으로 갑니다` 한 문장을 28 sp 이상으로 크게 보여 준다.
- 큰 `출발` 버튼(높이 72 dp 이상)과 `돌아가기` 버튼만 둔다.
- ACK를 기다리는 동안 버튼을 사라지게 하지 말고 비활성 + 진행 표시로 바꾼다.

#### 주행 중

- 현재 상태를 한국어 한 문장으로 크게 보여 준다. `시장으로 가는 중입니다`, `도착했습니다`, `멈추는 중입니다`.
- 큰 `멈춤` 버튼 하나. 상세 상태(tracking, 연결, 배터리)는 접힌 영역에 둔다.
- 로봇 상태가 `stale`이면 문장 아래에 `상태를 확인하는 중입니다`를 덧붙인다.

#### SOS

- 화면 전체를 SOS 상태로 바꾼다. 사유를 쉬운 말로 쓴다(`로봇이 어르신을 놓쳤어요`).
- 위치 전달 상태를 `위치를 보냈어요` / `조금 오래된 위치를 보냈어요` / `위치를 확인하지 못했어요`로 표시한다. 좌표 숫자는 기본으로 노출하지 않는다.
- 깜빡이는 애니메이션을 쓰지 않는다. 색·아이콘·글자를 함께 사용한다.
- `확인`을 눌러도 `sos.clear` 전까지 상단 배너와 알림은 유지한다.

### 12.3 설정 층 화면

톱니 아이콘을 1.5초 이상 길게 눌러야 설정에 들어간다. 짧게 누르면 `설정은 길게 누르세요` 안내만 뜬다. 어르신이 실수로 들어가는 것을 막기 위한 최소한의 마찰이다.

`[OPEN-D2]` 4자리 PIN을 추가할지는 현장 시험 후 결정한다. MVP는 롱프레스만 구현하되 PIN을 나중에 끼울 수 있게 진입 지점을 한 곳으로 모은다.

| 화면 | 내용 |
|---|---|
| 설정 홈 | 아래 항목으로 가는 목록 |
| 장소 프리셋 관리 | 프리셋 목록, 추가, 편집, 삭제, 순서 변경, 기본 출발지 지정, 사용 여부 |
| 지도에서 위치 지정 | 13장 규격 |
| 로봇 연결 | 자동 탐색 결과, robot id, 수동 IP·포트 입력, 재탐색 |
| 권한 상태 | 위치·알림·정확 위치 허용 여부와 시스템 설정 열기 |
| 알림 확인 | SOS 알림·진동 테스트 |
| 화면 크기 미리보기 | 현재 글꼴 배율에서 홈 화면이 어떻게 보이는지 |
| 정보·진단 | 앱 버전, 프로토콜 버전, 서버 버전, catalog revision, 로그 내보내기 |
| 개발자용(디버그 빌드만) | mock bridge 주소, 강제 SOS, 강제 연결 끊기 |

설정 층은 목록이 길어도 되고 스크롤해도 된다. 대신 각 항목의 제목은 한국어로 명확해야 하고 되돌리기 어려운 동작(삭제, 초기화)은 항상 확인을 받는다.

### 12.4 필수 화면 목록

| 화면 | 층 | 핵심 내용 | 주요 action |
|---|---|---|---|
| 시작/권한 안내 | 사용 | 앱 역할, 위치 사용 이유, 로컬 전송 안내 | 계속, 종료 |
| 홈 | 사용 | 연결 상태, 목적지 타일, 기본 출발지 | 목적지 선택, 재연결, 출발지 바꾸기 |
| 출발 확인 | 사용 | 출발지→목적지 한 문장 | 출발, 돌아가기 |
| 주행 중 | 사용 | 상태 문장, 상세 접힘 영역 | 멈춤 |
| SOS | 사용 | 사유, 위치 전달 상태 | 확인, 설정 열기 |
| 설정 홈 | 설정 | 항목 목록 | 각 항목 이동 |
| 프리셋 목록 | 설정 | 프리셋 카드 목록 | 추가, 편집, 삭제, 순서, 기본 출발지 |
| 프리셋 편집 | 설정 | 이름·종류·반경·사용 여부 | 저장, 지도 열기, 삭제 |
| 지도에서 위치 지정 | 설정 | 검색, 지도, 중앙 핀, 주소 | 이 위치로 지정 |
| 로봇 연결 | 설정 | 탐색 상태, robot id, 수동 IP | 연결, 다시 찾기 |
| 정보·진단 | 설정 | 버전, 권한, 로그 | 로그 내보내기 |

### 12.5 앱 상태 머신

```text
STOPPED
   └─ user start → DISCOVERING → CONNECTING → SYNCING → READY
                                    │            │
                                    └─ failure ──┴→ RECONNECTING

READY → START_PENDING → ACTIVE → CANCELLING → READY
                         └────────→ ARRIVED ─────→ READY

모든 연결 상태에서 sos.request → SOS_ACTIVE ── sos.clear → 이전 화면
```

- 서버의 `robot.status`가 mission 상태의 최종 원본이다.
- 프리셋 편집은 별도 상태가 아니라 `READY` 안의 설정 화면에서 수행한다. 주행 중에는 편집 저장을 막는다.
- 화면 회전이나 Activity 재생성으로 WebSocket을 새로 만들지 않는다.
- 서비스 상태는 `StateFlow`로 UI에 전달한다.
- `START_PENDING`에서 네트워크가 끊기면 자동 재전송하지 않고 사용자에게 결과 불명을 알린 뒤 재동기화한다.
- `ACTIVE`에서 연결이 끊겨도 앱이 로봇 동작을 추측해 `cancelled`로 바꾸지 않는다.

---

## 13. 지도 프리셋 편집 화면 규격

프리셋 좌표는 **국내 지도 검색** 또는 **지도 핀 이동** 두 경로로 지정한다. 좌표 직접 입력은 폴백이다.

### 13.1 화면 구성

```text
┌────────────────────────────────┐
│ ← 위치 지정                     │
│ ┌────────────────────────────┐ │
│ │ 🔍 장소나 주소를 검색하세요 │ │  ← 키워드 검색
│ └────────────────────────────┘ │
│                                │
│            ┃                   │
│         ───╋───                │  ← 화면 중앙 고정 핀 + 반경 원
│            ┃                   │     지도를 움직여 위치를 맞춘다
│                                │
│                       [내 위치] │
├────────────────────────────────┤
│ 경상북도 ○○시 ○○로 12          │  ← 역지오코딩 주소
│ 반경  ●━━━━━━━  30 m           │
│ ┌────────────────────────────┐ │
│ │      이 위치로 지정         │ │  ← 높이 64 dp 이상
│ └────────────────────────────┘ │
└────────────────────────────────┘
```

### 13.2 동작 규칙

- **검색**: 키워드를 입력하면 결과를 목록으로 보여 준다. 각 항목은 장소명, 도로명 주소, (가능하면) 현재 위치로부터의 거리. 항목을 고르면 지도가 그 좌표로 이동하고 라벨 입력값이 비어 있으면 장소명을 기본 라벨로 채운다.
- **핀 지정**: 마커를 손가락으로 끌게 하지 않는다. 핀은 화면 중앙에 고정하고 지도를 움직인다. 손떨림이 있어도 정확히 맞출 수 있고, 마커를 놓쳤을 때 생기는 오조작이 없다.
- 지도 이동이 멈추면(디바운스 400 ms) 중앙 좌표로 역지오코딩해 주소를 갱신한다. 실패하면 주소 줄을 비우고 좌표를 대신 보여 준다.
- **반경**: 슬라이더로 5~500 m를 조절하고 지도 위에 원으로 그린다. 기본 30 m.
- **내 위치**: 위치 권한이 있으면 현재 위치로 카메라를 옮긴다. 권한이 없으면 버튼을 비활성으로 두고 이유를 표시한다.
- `이 위치로 지정`을 누르면 편집 화면으로 좌표·주소·`source`를 돌려주고 저장은 프리셋 편집 화면의 `저장`에서 `place.upsert` 한 번으로 수행한다. 지도 화면이 직접 서버로 보내지 않는다.
- 지도 SDK 초기화 실패, 키 없음, 네트워크 없음일 때는 위도·경도 직접 입력 폼으로 전환하고 `source=manual_coords`로 저장한다.
- 저장 직전에 좌표 유효성(범위, 0,0 근처 금지)과 라벨 길이를 검사한다.

### 13.3 접근성과 크기

- 검색 입력, 결과 항목, 반경 슬라이더, 확정 버튼 모두 최소 48 dp 터치 영역을 갖는다.
- 지도는 TalkBack에서 건너뛸 수 있게 하고 현재 선택 좌표·주소는 텍스트로 읽히게 한다.
- 지도 화면은 설정 층이므로 어르신 홈의 글자 크기 기준(24 sp)을 그대로 따를 필요는 없다. 본문 16 sp 이상, 확정 버튼 라벨 20 sp 이상이면 된다.

---

## 14. 디자인 시스템

Material 3(Expressive 컴포넌트 허용)를 쓰되 어르신 사용성이 우선이다. 크고 빠른 모션, 낮은 대비의 장식, 밀도 높은 레이아웃은 사용 층에서 쓰지 않는다. 둘이 충돌하면 이 문서의 크기·대비·단순성 규칙이 이긴다.


### 14.2 브랜드 색상 토큰

| token | 색상 | 용도 | M3 role 매핑(권장) |
|---|---|---|---|
| `Background` | Cream `#EEE8DF` | 앱 기본 배경 | `background` |
| `Surface` | Warm White `#F8F4EE` | 카드와 sheet | `surface`, `surfaceContainerLow` |
| `Primary` | Deep Ocean `#2C365A` | 버튼, 핵심 텍스트, 아이콘 | `primary` |
| `SecondarySurface` | Beige `#C4BCB0` | 구분선, 비활성 면, 장식 | `outlineVariant`, `surfaceVariant` |
| `AccentSurface` | Powder Blue `#DCE7F2` | 선택 상태, 정보 카드 | `secondaryContainer` |
| `SecondaryText` | Slate `#5A6075` | 보조 본문 | `onSurfaceVariant` |
| `Error` | `#A43A38` | 오류 텍스트/아이콘 | `error` |
| `Success` | `#3E6B57` | 정상 상태 텍스트/아이콘 | 커스텀 토큰 |

Deep Ocean과 Cream의 대비는 약 `9.67:1`이지만 Beige와 Cream은 약 `1.54:1`이라 본문이나 상태를 전달하는 텍스트 색으로 쓰지 않는다. Beige는 면, 선, 비활성 장식에만 쓴다.

브라스/골드 계열은 작은 divider, badge 테두리 같은 장식에만 제한하며 주요 버튼, 본문, 경고 상태에는 쓰지 않는다.

dynamic color(Material You)는 브랜드 대비를 깨뜨릴 수 있으므로 사용 층에서는 끄고 고정 팔레트를 쓴다.

### 14.3 화면 원칙

- 기본 배경은 Cream, 카드는 Warm White, 핵심 action은 Deep Ocean.
- 한 화면에 primary button은 원칙적으로 하나만 둔다.
- 모든 터치 영역은 최소 48×48 dp. 사용 층의 핵심 action(출발, 멈춤, SOS 확인)은 높이 64 dp 이상, 목적지 타일은 140 dp 이상.
- 사용 층 본문은 20 sp 이상, 핵심 문장은 28 sp 이상. 설정 층은 16 sp 이상.
- 시스템 글꼴 배율을 막지 않는다. 200%에서도 핵심 action이 잘리면 안 된다.
- 상태는 색만으로 구분하지 않고 아이콘과 한국어 label을 함께 쓴다.
- 긴급 화면에서도 깜빡이는 animation을 쓰지 않는다.
- 문구는 존댓말 평서문으로 짧게 쓴다. `실행 취소` 대신 `되돌리기`처럼 쉬운 말을 고른다.
- 영어 단어를 사용 층에 노출하지 않는다(`SOS`는 예외로 허용하되 한국어 설명을 붙인다).
- `TalkBack` 읽기 순서, content description, button role을 지정한다.
- 로딩 중에는 버튼을 사라지게 하지 말고 비활성 + 진행 상태를 보여 준다.
- dark theme은 MVP 필수가 아니다. `[OPEN-D1]` 필요하면 별도의 검증된 palette를 정의한다.

웹 대시보드(`senior-care-dashboard`)는 같은 브랜드 정서를 쓸 수 있지만 이 문서의 화면 구성이나 상태 머신을 복제할 필요는 없다. 색 토큰 값만 참고한다.

---

## 15. RPi5 ↔ ROS 2 내부 계약 — 참조

로봇 쪽 내부 계약(`mr_care_msgs`, 토픽·QoS, `places.yaml` 규칙, ArUco 연결 지점)은 `contracts/phone-bridge/v1/PROTOCOL.md` 6·7장에 있다. 앱 구현자는 읽기만 하면 된다. 앱은 여기 나오는 토픽·메시지를 직접 다루지 않는다.


## 16. 실패 모드

| 상황 | 앱 동작 | RPi5/로봇 동작 |
|---|---|---|
| mDNS 실패 | 수동 endpoint 제공 | 서비스 endpoint를 로그/설정에 표시 |
| 로컬 네트워크 권한 거절(API 37+) | 연결 시작 차단, 권한 이유와 재요청·설정 경로 안내 | 앱 연결 없음 |
| 연결 끊김 | stale 표시 후 backoff 재접속 | 10초 후 link disconnected 발행 |
| handshake version 불일치 | 업데이트 필요, 재시도 중단 | 오류 후 연결 종료 |
| ACK timeout | 결과 불명 표시, 자동 command 반복 금지 | 중복 id면 같은 결과 반환 |
| 위치 권한 거절 | 연결 시작 차단, 권한 이유와 재요청·설정 경로 안내 | 앱 연결 없음 |
| GPS 꺼짐 | 연결 시작 차단 또는 실행 중 세션 종료, 시스템 설정 안내 | link disconnected 후 로컬 폴백 유지 |
| 오래되거나 부정확한 위치 | 사용자에게 경고 표시 | 낮은 신뢰도로 기록, 폐기하지 않음 |
| 앱 process 종료 | 사용자가 재시작할 때 재연결 | ACK 실패 후 로컬 폴백 |
| 같은 SOS 재수신 | ACK/위치 재전송, UI 중복 금지 | 같은 `sos_id` 유지 |
| SOS 중 재연결 | active SOS 화면/알림 복원 | active `sos.request` 재전송 |
| `sos.clear` 수신 | 해당 SOS ongoing 표시 종료 | 같은 `sos_id`의 사건 종료 |
| 알 수 없는 place id | catalog 갱신 후 선택 해제 | `UNKNOWN_PLACE` 반환 |
| 프리셋 저장 중 revision 충돌 | 최신 catalog 반영 후 재확인 요청 | `CATALOG_CONFLICT` 반환, 파일 미변경 |
| 프리셋 상한 초과 | 삭제 후 추가하도록 안내 | `PLACE_LIMIT` 반환 |
| 사용 중 장소 삭제 시도 | 주행 종료 후 삭제하도록 안내 | `PLACE_IN_USE` 반환 |
| 지도 SDK 실패·키 없음 | 좌표 직접 입력으로 전환 | 영향 없음 |
| 장소 검색 API 실패 | 지도 핀 지정 경로 안내 | 영향 없음 |
| 프리셋 0개 | 홈에서 `설정에서 장소를 등록해 주세요` 안내 | 빈 catalog 전송 |
| ArUco 미구현 | tracking unavailable 표시 | 수동 SOS test publisher 사용 |
| 배터리 데이터 없음 | 잔량 UI 숨김 또는 확인 불가 | `battery_percent=null` |

어떤 실패에서도 앱이 로봇이 정지했다고 미리 단정하면 안 된다. 사용자가 멈춤을 누르면 실제 정지 명령을 보내되 `robot.status`로 결과를 확인하기 전에는 `멈추는 중입니다`로 표시한다.

---

## 17. 로그, 개인정보, 진단

- 기본 로그에 `lat`, `lon`, 핫스팟 비밀번호, 지도 API 키를 기록하지 않는다.
- 위치 로그는 `has_fix`, accuracy 구간, age 구간, failure reason만 남긴다.
- 실시간 위치의 정확한 좌표는 메모리 캐시 외에 영구 저장하지 않는다.
- 프리셋 좌표는 사용자가 명시적으로 등록한 값이므로 RPi5의 `places.yaml`과 앱의 메모리 catalog에 보관한다. 다만 로그와 진단 파일에는 좌표를 넣지 않고 `place_id`와 `label`만 남긴다.
- 지도 검색어와 검색 결과를 저장하거나 로그에 남기지 않는다.
- 분석 SDK, 광고 SDK, 원격 crash upload는 사전 승인 없이 추가하지 않는다.
- 진단 내보내기는 사용자의 명시적 action으로 수행하고 좌표를 제외한다.
- 진단 로그가 필요하면 `message id`, `type`, 송수신 시각, 결과 code만으로 양쪽 기록을 맞춘다.
- RPi5는 protocol 오류를 rate limit해 잘못된 client가 로그를 고갈시키지 못하게 한다.

---

## 18. 앱 저장소 필수 산출물

```text
senior-care-android/
  README.md                      JDK·SDK·키 주입·빌드·설치·mock 실행
  app/                           단일 모듈 (5.3 패키지 경계)
  contracts/phone-bridge/v1/     PROTOCOL.md · schema · fixtures 26개 (복사본, sync 스크립트로만 갱신)
  scripts/sync-contracts.sh
  tools/mock-bridge/             mock 실행·SOS 계측·12종 회귀 검사
  docs/
    android-app-spec.md          이 문서
  .github/workflows/android.yml  assembleDebug · unit test · lint · contracts-drift
```

mock bridge는 이 저장소가 아니라 mobile-robot에 있다(`mr_phone_bridge.mock`). 실제 서버와 같은 프로토콜 코드를 쓰므로 계약 드리프트가 생기지 않는다. 시나리오 12종은 PROTOCOL.md 7.1. ROS 2 없이 `uv`만으로 실행된다.

fixture는 문서 예시·서버 테스트·앱 테스트가 함께 읽는 단일 파일이다. 앱 테스트는 `../contracts/phone-bridge/v1/fixtures`를 직접 읽는다(`EnvelopeFixturesTest`).


## 19. 테스트와 인수 기준

### 19.1 자동 테스트

- 모든 v1 fixture의 serialize/deserialize round trip
- 필수 필드 누락, null, 범위 밖 좌표, 잘못된 UUID, 16 KiB 초과 입력
- 같은 message id의 중복 처리
- heartbeat timeout과 reconnect backoff
- trip 상태 전이와 중복 탭 방지
- 프리셋 검증: 라벨 길이, `kind` enum, 반경 범위, 기본 출발지 유일성, 개수 상한
- `CATALOG_CONFLICT` 후 재동기화 흐름
- 위치 없음/오래됨/부정확함/fresh 성공/timeout
- 같은 SOS 재수신 시 알림은 하나, ACK는 매번 전송
- process가 아닌 Activity 재생성 시 service/session 유지
- Compose 핵심 화면의 semantics와 최소 touch target

### 19.2 실기기 인수 시나리오

| ID | 시나리오 | 합격 조건 |
|---|---|---|
| A-01 | mock bridge 자동 발견 | 10초 안에 발견하거나 수동 입력 경로 제공 |
| A-02 | 홈 타일 표시 | catalog와 같은 label·순서, 비활성 장소 구분 표시 |
| A-03 | 주행 시작 | 목적지 1탭 + 출발 1탭으로 전송, 1초 내 ACK, status로 active 반영 |
| A-04 | Wi-Fi 15초 끊기 | crash 없이 재연결하고 catalog/status 재동기화 |
| A-05 | 화면 끄고 30분 | ongoing 알림 유지, heartbeat와 periodic 위치 지속 |
| A-06 | SOS + 정상 캐시 | ACK와 cache 위치가 각각 500 ms 안에 server 도착 |
| A-07 | SOS + fresh fix | cache 후 30초 안에 fresh 또는 명시적 timeout 도착 |
| A-08 | 위치 권한 거절 | 연결 세션을 시작하지 않고 권한 이유와 재요청·설정 경로 안내, crash 없음 |
| A-09 | GPS 끔 | 연결 세션을 시작하지 않거나 실행 중 세션을 종료하고 시스템 설정 안내, crash 없음 |
| A-10 | 같은 SOS 3회 | SOS 화면/알림 하나, ACK는 재전송 |
| A-11 | SOS 종료 | 같은 id의 `sos.clear` 후 ongoing 표시와 알림 종료 |
| A-12 | 큰 글꼴/TalkBack | 200%에서 홈 타일과 출발·멈춤 버튼 접근 가능, 읽기 순서 정상 |
| A-13 | 실제 RPi5 | mock과 동일 fixture 의미로 end-to-end 통과 |
| A-14 | 검색으로 프리셋 등록 | 키워드 검색 → 결과 선택 → 저장까지 완료, 새 타일이 홈에 나타남 |
| A-15 | 지도 핀으로 프리셋 등록 | 지도 이동으로 좌표 지정 → 주소 표시 → 저장, 좌표 오차 육안 확인 |
| A-16 | 프리셋 수정·삭제 | 라벨/반경 수정 반영, 기본 출발지 삭제 차단, 사용 중 장소 삭제 차단 |
| A-17 | 기본 출발지 변경 | 새 기본 출발지 지정 시 기존 값 자동 해제, 홈의 `출발:` 줄 갱신 |
| A-18 | 지도 사용 불가 | 키 제거 또는 비행기 모드에서 좌표 직접 입력으로 저장 성공 |
| A-19 | 설정 진입 마찰 | 톱니 짧게 누르면 안내만, 1.5초 롱프레스에서 설정 진입 |
| A-20 | 주행 중 멈춤 | `trip.cancel` 전송 후 `멈추는 중입니다` 표시, 실제 로봇 정지와 `mission_state=cancelled` 확인 |
| A-21 | 로컬 네트워크 권한 거절 | API 37+에서 연결을 시작하지 않고 권한 이유와 재요청·설정 경로 안내, crash 없음 |

500 ms는 RPi5가 `sos.request` frame 전송을 완료한 monotonic 시각부터 해당 응답 frame을 수신한 시각까지 측정한다. Android UI 애니메이션 시간은 이 측정에 포함하지 않는다.

### 19.3 Definition of Done

Android 작업은 아래 열 가지 조건을 모두 만족할 때 완료로 본다.

- 12.4의 필수 화면과 상태가 구현돼 있다.
- 어르신이 홈에서 최대 2탭으로 출발할 수 있다.
- 프리셋을 검색·핀·직접 입력 세 경로로 등록할 수 있다.
- CI의 build, lint, unit test가 통과한다.
- mock bridge 기반 A-01~A-12, A-14~A-21이 기록돼 있다. A-20은 mock에서 상태 전이까지 확인한다.
- 실제 RPi5가 준비된 뒤 A-13과 A-20의 물리 정지 확인이 통과한다.
- 위치 권한을 거절하거나 지도를 못 쓰는 경우에도 crash하거나 무한 로딩하지 않는다.
- 실시간 좌표와 secret이 저장소·기본 로그에 없다.
- 새 개발자가 README만 보고 30분 안에 mock 환경을 실행할 수 있다.
- 모든 변경이 PR을 통해 병합됐다.

---

## 21. 미결정 항목 요약

| ID | 결정 내용 | 앱 개발 차단 | 현장 릴리스 차단 | 담당 |
|---|---|---:|---:|---|
| ~~`OPEN-A1`~~ | 지원 기기 — **S26 Ultra, minSdk 36으로 종결(2026-09-27)** | — | — | — |
| `OPEN-M1` | 지도 REST 키 보호(사용량 제한 또는 프록시) | X | O | 로봇/제품 |
| `OPEN-R1` | 공용망 사용 여부와 WSS/페어링 | X | O | 로봇 |
| `OPEN-R3` | SOS 무응답 정지는 로봇에서 구현, 부저·경광등 추가 여부 미결 | X | O | 로봇 |
| `OPEN-N1` | 도착(`arrived`) 판정 수단(로봇 GPS 없음) | X | trip 완결에 O | 제품·로봇 |
| `OPEN-P1` | 보호자 최종 연락 경로 → **대시보드 SOS 위치 표시(P2)로 대체 검토** | X | 제품 SOS 기능에는 O | 제품 |
| `OPEN-D1` | dark theme 필요 여부 | X | X | 앱 |
| `OPEN-D2` | 설정 진입 PIN 추가 여부 | X | X | 제품/앱 |

지도 키가 없는 환경에서는 좌표 직접 입력 경로와 테스트용 `PlaceSearchProvider`를 사용한다.


## 22. 참고 문서

- 프로토콜 정본: `contracts/phone-bridge/v1/PROTOCOL.md` (mobile-robot `software/contracts/phone-bridge/v1`)
- 로봇 구현 기준: https://github.com/next-year-robotics/mobile-robot
- 앱 저장소: https://github.com/next-year-robotics/senior-care-android
- 웹 대시보드: https://github.com/next-year-robotics/senior-care-dashboard
- Android 위치 권한: https://developer.android.com/develop/sensors-and-location/location/permissions
- Android 로컬 네트워크 권한: https://developer.android.com/privacy-and-security/local-network-permission
- Android foreground service 제한: https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start
- Android 14+ foreground service 유형: https://developer.android.com/about/versions/14/changes/fgs-types-required
- Android Doze/배터리 예외: https://developer.android.com/training/monitoring-device-state/doze-standby
- Compose 접근성 기본값: https://developer.android.com/develop/ui/compose/accessibility/api-defaults
- Material 3 in Compose: https://developer.android.com/develop/ui/compose/designsystems/material3
- Kakao Map Android SDK: https://apis.map.kakao.com/android_v2/
- Kakao Local REST API: https://developers.kakao.com/docs/latest/ko/local/dev-guide
- (대안) Naver Maps Android SDK: https://navermaps.github.io/android-map-sdk/

---

## 문서 수정사항 기록

| 버전 | 날짜 | 내용 |
|---|---|---|
| v0.4 | 2026-08-28 | 앱 초기 기술 명세 |
| v0.5 | 2026-09-27 | 프로토콜을 mobile-robot 계약으로 이관. SDK·지도·기기 기준 갱신 |
| v0.6 | 2026-10-30 | 최종 로봇 계약·구현 기준 반영, 평문 연결 정책 추가, 개발 절차와 세션 문서 참조 정리 |
