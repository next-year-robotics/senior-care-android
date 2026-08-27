# Senior Care Android 앱 · RPi5 브리지 기술 명세 v0.4

> 상태: 구현 착수용 확정 초안
>
> 작성 기준일: 2026-08-28
>
> 대상 독자: Android 앱 개발자(에이전트 포함), 로봇/ROS 2 담당자

## 관련 저장소

| 저장소 | 주소 | 이 문서와의 관계 |
|---|---|---|
| 로봇 본체 | https://github.com/next-year-robotics/mobile-robot | STM32 펌웨어, micro-ROS, RPi5 ROS 2 패키지, 폰 브리지 서버. 이 문서의 로봇 측 구현이 여기 들어간다. |
| Android 앱 | https://github.com/next-year-robotics/senior-care-android | 이 문서가 사는 저장소. 앱 구현 전체. |
| 웹 대시보드 | https://github.com/next-year-robotics/senior-care-dashboard | 보호자용 웹/PWA. 앱과 별도 제품이며 이 문서의 프로토콜을 공유하지 않는다. |

로봇 기준 리비전: `mobile-robot`의 `main` `5ae0b99` (`docs: 플랫폼 이식 완료`)

이 문서는 Android 개발자가 ROS 2나 ArUco 구현을 몰라도 앱을 독립적으로 완성할 수 있도록 앱과 RPi5 사이의 계약을 고정한다. 구현 코드는 포함하지 않으며 확정되지 않은 내용은 `[OPEN-*]`으로 표시한다.

---

## 1. 프로젝트 개요와 협업 규칙

### 1.1 제품 한 줄 정의

어르신이 로봇과 함께 미리 지정해 둔 장소로 이동할 때 쓰는 앱으로, 휴대폰이 로봇의 상태를 보여 주고 로봇이 요청하면 즉시 위치를 회신한다.

핵심 사용자는 어르신이므로 설정 화면은 복잡해도 되지만 매일 쓰는 메인 화면은 단순해야 한다. 이 원칙이 다른 UI 판단보다 우선한다.

### 1.2 협업 규칙 (앱 저장소 작업자 필독)

`senior-care-android` 저장소에서 작업할 때 지킬 규칙은 아래 10개다.

1. `main`에 직접 푸시하지 않는다. 모든 변경은 pull request를 거쳐 병합한다.
2. 작업은 항상 새 브랜치에서 한다. 브랜치 이름은 `feat/…`, `fix/…`, `docs/…`, `chore/…` 접두어를 사용한다.
3. 다른 사람의 리뷰 승인은 필수가 아니다. PR 작성자가 CI와 자체 점검 결과를 확인한 뒤 직접 병합할 수 있다. GitHub에서는 작성자가 자기 PR을 승인할 수 없으므로 required approvals는 0으로 둔다.
4. PR 본문에는 무엇을 왜 바꿨는지, 어떻게 확인했는지(테스트 명령·인수 시나리오 ID·기기)를 적는다.
5. CI(build, lint, unit test)가 통과하지 않은 PR은 병합하지 않는다.
6. force push로 `main` 이력을 다시 쓰지 않는다. 자기 작업 브랜치는 자유롭게 정리해도 된다.
7. 커밋 메시지는 한국어 또는 영어 한 줄 요약 + 필요 시 본문. `feat:`, `fix:`, `docs:`, `refactor:`, `test:`, `chore:` 접두어를 쓴다.
8. 비밀값을 커밋하지 않는다. 지도 API 키, 서명 키(`*.jks`), 핫스팟 비밀번호, 실제 사용자 좌표는 저장소에 들어가면 안 된다. 키는 `local.properties`나 CI secret으로 주입하고 `.gitignore`에 넣는다.
9. 이 문서의 프로토콜·메시지 스키마를 바꾸는 변경은 단독으로 병합하지 않는다. 먼저 이슈나 PR 초안으로 제안하고 로봇 담당자의 확인을 받는다. 스키마를 바꾸지 않는 앱 내부 구현은 자유롭게 진행한다.
10. 구현이 이 문서와 달라졌으면 같은 PR에서 이 문서(`docs/android-app-spec.md`)도 함께 고친다. 문서와 코드가 어긋난 상태로 병합하지 않는다.

`mobile-robot` 저장소의 ROS 2 패키지와 펌웨어는 앱 작업자가 수정하지 않는다.

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
- 실제 RPi5 없이 개발할 수 있는 mock bridge와 프로토콜 fixture를 앱 저장소에 둔다.
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

## 3. 현재 `mobile-robot/main` 기준선

현재 기준 리비전에 존재하는 로봇 인터페이스는 아래 표가 전부다.

| 방향 | ROS 2 이름 | 타입/주기 | 비고 |
|---|---|---|---|
| RPi5 → STM32 | `/cmd_vel` | `geometry_msgs/TwistStamped` | MCU 명령 워치독 적용 |
| STM32 → RPi5 | `/joint_states` | `sensor_msgs/JointState`, 50 Hz | `BEST_EFFORT`, `KEEP_LAST(1)` |
| STM32 → RPi5 | `/base/status` | `mr_msgs/BaseStatus`, 10 Hz | `BEST_EFFORT`, `KEEP_LAST(1)` |
| RPi5 내부 | `/odom` | `nav_msgs/Odometry` | `mr_base`가 생성 |
| RPi5 내부 | `odom → base_footprint` | TF | `mr_base`가 생성 |

ROS 2 배포판은 펌웨어의 micro-ROS 정적 라이브러리 빌드 설정에 맞춰 Jazzy를 기준으로 한다.

현재 저장소에 아직 없는 것: ArUco 패키지와 추적 상태 토픽, SOS 토픽/메시지, 폰 브리지 패키지, 목적지·주행 상태 패키지, 배터리 잔량의 상위 인터페이스.

따라서 앱은 위 기존 토픽을 직접 전제로 삼지 않는다. 앱에 필요한 상위 상태는 `mr_phone_bridge`가 조합해 전송하며 아직 제공할 수 없는 값은 `null` 또는 `unavailable`로 보낸다.

---

## 4. 담당 범위와 산출물

### 4.1 Android 개발자 담당

- Kotlin 기반 Android 앱 전체
- Jetpack Compose UI와 아래 화면 전부(어르신 홈, 설정, 지도 프리셋 편집 포함)
- WebSocket 연결, 재연결, heartbeat, 메시지 검증과 직렬화
- 포그라운드 서비스, 알림, 위치 권한, 위치 캐시, SOS용 새 측위
- 국내 지도 SDK 연동, 키워드 장소 검색, 핀 지정, 역지오코딩
- 프로토콜 단위 테스트와 상태 머신 테스트
- 실제 RPi5 없이 실행 가능한 mock bridge
- 디버그/릴리스 빌드, 기기 설치, 지도 키 주입, 권한별 동작을 담은 `README.md`
- 접근성 검사와 지정된 실기기 시험 결과

Android 개발자가 하지 않는 것: `mobile-robot` 저장소의 ROS 2 패키지·펌웨어 수정, ROS 2 메시지 설계 변경, ArUco 노드 구현, 모터 명령·안전 정지 로직 구현.

### 4.2 로봇/ROS 2 담당자 담당

- `mr_phone_bridge` WebSocket 서버와 ROS 2 어댑터
- `mr_msgs`의 상위 인터페이스 메시지 추가
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

### 5.2 SDK 기준

- `applicationId`: `com.nextyearrobotics.seniorcare` (변경하려면 착수 전에 결정한다)
- `minSdk`: API 29(Android 10). `[OPEN-A1]` 실제 지급 기기가 이보다 낮으면 착수 전에 변경한다.
- `compileSdk`/`targetSdk`: 착수 시점의 최신 안정 SDK로 고정하고 PR에 버전을 기록한다.
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

프리셋 좌표를 지정하려면 국내 지도가 필요하다. Google Maps는 국내 장소 검색·도로 데이터 제약이 있으므로 사용하지 않는다.

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
- 두 경로 모두 같은 저장 흐름(8.4 `place.upsert`)을 쓴다.

---

## 7. 네트워크와 발견

### 7.1 기본 연결

| 항목 | 값 |
|---|---|
| 역할 | RPi5 = WebSocket server, Android = client |
| 기본 URL | `ws://mobile-robot.local:8765/bridge/v1` |
| 서비스 타입 | `_mr-phone-bridge._tcp` |
| mDNS TXT | `api=1`, `robot_id=<stable-id>` |
| 문자 인코딩 | UTF-8 |
| payload | JSON text frame만 허용 |
| 단일 메시지 상한 | 16 KiB |
| 동시 앱 | MVP에서는 1대 |

휴대폰이 네트워크 게이트웨이라는 사실과 WebSocket의 client/server 역할은 별개다.

### 7.2 발견 순서

1. 저장된 마지막 endpoint가 있으면 2초 동안 먼저 시도한다.
2. 실패하면 Android `NsdManager`로 `_mr-phone-bridge._tcp`를 최대 10초 탐색한다.
3. 한 대면 자동 선택한다. 여러 대면 `robot_id` 목록을 보여 준다.
4. mDNS가 실패하면 설정 화면에서 IPv4 주소와 포트를 직접 입력할 수 있게 한다.
5. 서브넷 전체 포트 스캔은 구현하지 않는다.

일부 Android 핫스팟/OEM 조합은 multicast를 제한하므로 mDNS만을 유일한 진입 경로로 두지 않는다. 수동 endpoint는 디버그용이면서 현장 폴백이다.

### 7.3 보안 단계

MVP의 `ws://`는 인터넷에 공개되지 않은 개인 핫스팟 안에서만 허용한다. RPi5 방화벽은 핫스팟 인터페이스의 bridge 포트만 열고 다른 인터페이스에는 노출하지 않는다.

다음 중 하나라도 충족하면 공개 시연 전 `wss://`와 기기 페어링 토큰을 적용해야 한다.

- 공용 Wi-Fi 사용
- 여러 로봇 또는 여러 앱이 같은 네트워크에 존재
- 실제 사용자 위치를 장기간 운용
- 외부 서버로 SOS 전달

`[OPEN-R1]` 현장 네트워크가 개인 핫스팟이 아닌 경우 인증 방식과 인증서 배포 방식을 확정해야 한다. 이 항목은 앱 UI 개발을 막지 않지만 현장 릴리스는 막는다.

---

## 8. WebSocket 프로토콜 `phone-bridge/1`

### 8.0 메시지 목록

| type | 방향 | 응답 | 용도 |
|---|---|---|---|
| `hello` | 앱 → RPi5 | `hello.ack` 또는 `error` | 세션 협상 |
| `hello.ack` | RPi5 → 앱 | 없음 | 세션 협상 성공 |
| `heartbeat` | 앱 → RPi5 | `heartbeat.ack` | 연결 생존 확인 |
| `heartbeat.ack` | RPi5 → 앱 | 없음 | heartbeat 응답 |
| `ack` | RPi5 → 앱 | 없음 | 요청 접수 성공 |
| `trip.start` | 앱 → RPi5 | `ack` 또는 `error` | 주행 시작 요청 |
| `trip.cancel` | 앱 → RPi5 | `ack` 또는 `error` | 주행 정지 요청 |
| `place.upsert` | 앱 → RPi5 | `ack` 또는 `error` | 프리셋 추가·수정 |
| `place.delete` | 앱 → RPi5 | `ack` 또는 `error` | 프리셋 삭제 |
| `location.update` | 앱 → RPi5 | `ack` 또는 `error` | periodic/SOS 위치 |
| `sos.ack` | 앱 → RPi5 | `ack` 또는 `error` | SOS 수신 확인 |
| `place.catalog` | RPi5 → 앱 | 없음 | 프리셋 전체 동기화 |
| `robot.status` | RPi5 → 앱 | 없음 | 앱용 로봇 상태 |
| `sos.request` | RPi5 → 앱 | `sos.ack` | SOS 처리 요청 |
| `sos.clear` | RPi5 → 앱 | 없음 | 활성 SOS 종료 통지 |
| `error` | 양방향 | 없음 | 요청 거절 또는 protocol 오류 |

서버는 `location.update`와 `sos.ack`에 일반 `ack`를 보낸다. 이 `ack`는 RPi5 브리지가 받았다는 뜻이지 SOS의 외부 전달 완료를 뜻하지 않는다.

### 8.1 공통 envelope

모든 메시지는 타입과 상관없이 같은 구조를 쓴다.

```json
{
  "version": 1,
  "type": "trip.start",
  "id": "e87864c5-3c72-42f8-9565-cf9219aa7cf2",
  "sent_at_ms": 1787792400000,
  "reply_to": null,
  "payload": {}
}
```

| 필드 | 타입 | 필수 | 규칙 |
|---|---|---:|---|
| `version` | integer | O | v1에서는 반드시 `1` |
| `type` | string | O | 위 표의 값만 발신 |
| `id` | string | O | canonical lowercase UUID v4 |
| `sent_at_ms` | integer | O | Unix epoch millisecond, 기록용 |
| `reply_to` | string/null | O | 응답이면 원 요청의 `id`, 아니면 `null` |
| `payload` | object | O | 타입별 스키마 |

- 타임아웃과 재시도 계산은 각 기기의 monotonic clock을 사용한다. `sent_at_ms`만으로 타임아웃을 계산하지 않는다.
- 알 수 없는 최상위 필드와 payload의 선택 필드는 무시한다.
- 필수 필드 누락, 잘못된 타입, 범위를 벗어난 숫자, `NaN`/`Infinity`는 `INVALID_MESSAGE`로 거절한다.
- 알 수 없는 `type`은 연결을 끊지 않고 `UNSUPPORTED_TYPE` 오류로 응답한다.
- 같은 `id`가 재수신되면 side effect를 반복하지 않고 10분 동안 보관한 기존 결과를 다시 응답한다.
- 메시지 상한을 넘기면 해당 frame을 거절하고 연결을 종료할 수 있다.
- v1에서 필드를 추가하는 변경은 선택 필드만 허용한다. 필수 필드 삭제·의미 변경은 `/bridge/v2`가 필요하다.

### 8.2 연결 handshake

WebSocket 연결 직후 앱이 3초 안에 `hello`를 보낸다.

#### 앱 → RPi5: `hello`

```json
{
  "version": 1,
  "type": "hello",
  "id": "c3c4a81d-f25b-487c-8f28-4ac47a9eaf70",
  "sent_at_ms": 1787792400000,
  "reply_to": null,
  "payload": {
    "app_instance_id": "9e889e5d-1c2d-4572-9513-9cd8283c917a",
    "app_version": "0.1.0",
    "platform": "android",
    "capabilities": ["location.cache", "location.fresh", "place.edit"]
  }
}
```

`app_instance_id`는 설치 시 생성해 DataStore에 저장한 임의 UUID다. IMEI, 전화번호, Android ID를 사용하지 않는다.

#### RPi5 → 앱: `hello.ack`

```json
{
  "version": 1,
  "type": "hello.ack",
  "id": "97fe12c8-5dd6-4de6-b638-a091db31b991",
  "sent_at_ms": 1787792400042,
  "reply_to": "c3c4a81d-f25b-487c-8f28-4ac47a9eaf70",
  "payload": {
    "session_id": "e2840b5b-f31f-4bba-a8ca-5b140c34cb1d",
    "robot_id": "mr-001",
    "server_version": "0.1.0",
    "heartbeat_interval_ms": 2000,
    "heartbeat_timeout_ms": 10000,
    "catalog_revision": "2026-08-27T12:00:00Z",
    "place_edit_enabled": true,
    "max_places": 12
  }
}
```

`place_edit_enabled=false`면 앱은 프리셋 편집 UI를 읽기 전용으로 만든다. 지원하지 않는 protocol version이면 서버는 `UNSUPPORTED_VERSION` 오류를 보내고 연결을 닫는다. handshake가 끝나기 전에는 `hello` 외의 메시지를 처리하지 않는다.

### 8.3 ACK와 오류

#### 성공: `ack`

```json
{
  "version": 1,
  "type": "ack",
  "id": "1bce692f-2f56-49bf-b02c-b6322c86d750",
  "sent_at_ms": 1787792400500,
  "reply_to": "e87864c5-3c72-42f8-9565-cf9219aa7cf2",
  "payload": {
    "status": "accepted",
    "trip_id": "bbef019a-dd3a-437e-8f5c-388913ae9eca"
  }
}
```

`accepted`는 검증을 통과해 RPi5 내부에 접수됐다는 뜻이지만 실제 로봇 상태 변경은 이후 `robot.status`가 기준이다. 서버는 command 수신 후 1초 안에 `ack` 또는 `error`를 보낸다.

`place.upsert`/`place.delete`의 `ack` payload는 `{"status":"accepted","place_id":"…","catalog_revision":"…"}`를 담는다.

#### 실패: `error`

```json
{
  "version": 1,
  "type": "error",
  "id": "f177b5a1-b7e5-4cba-89a2-014c66f553af",
  "sent_at_ms": 1787792400500,
  "reply_to": "e87864c5-3c72-42f8-9565-cf9219aa7cf2",
  "payload": {
    "code": "UNKNOWN_PLACE",
    "message": "Unknown destination place_id",
    "retryable": false
  }
}
```

오류 코드는 아래 11개로 고정한다.

| code | 의미 | 앱 동작 |
|---|---|---|
| `INVALID_MESSAGE` | 형식, 타입 또는 범위 오류 | 사용자 재시도 금지, 디버그 로그 |
| `UNSUPPORTED_TYPE` | 알 수 없는 `type` | 업데이트 필요 표시 |
| `UNSUPPORTED_VERSION` | protocol version 불일치 | 연결 중단, 업데이트 필요 표시 |
| `NOT_READY` | 로봇/브리지가 명령 받을 상태가 아님 | 상태 표시 후 재시도 허용 |
| `UNKNOWN_PLACE` | 카탈로그에 없는 장소 | 카탈로그 새로고침 |
| `INVALID_STATE` | 현재 상태에서 불가능한 명령 | 최신 `robot.status` 반영 |
| `CATALOG_CONFLICT` | `catalog_revision`이 최신이 아님 | 최신 catalog 반영 후 사용자에게 재확인 |
| `PLACE_LIMIT` | 프리셋 개수 상한 초과 | 삭제 후 추가하도록 안내 |
| `PLACE_IN_USE` | 진행 중 trip이 쓰는 장소 삭제 시도 | 주행 종료 후 삭제하도록 안내 |
| `PERMISSION_DENIED` | 페어링/인증 실패 | 연결 중단 |
| `INTERNAL_ERROR` | 서버 내부 오류 | `retryable`에 따름 |

내부 예외 stack trace, 파일 경로, 토큰은 `message`로 보내지 않는다.

### 8.4 heartbeat와 재연결

handshake 후 앱은 2초마다 `heartbeat`를 보내며 서버는 같은 요청에 `heartbeat.ack`로 응답한다.

```json
{
  "version": 1,
  "type": "heartbeat",
  "id": "1392a54d-c195-489f-88bb-a20022eef8b5",
  "sent_at_ms": 1787792402000,
  "reply_to": null,
  "payload": {"session_id": "e2840b5b-f31f-4bba-a8ca-5b140c34cb1d"}
}
```

`heartbeat.ack`는 공통 envelope를 사용하고 `reply_to`에 heartbeat의 `id`, payload에 현재 `session_id`를 넣는다.

- 서버는 유효한 앱 메시지가 10초 동안 없으면 세션을 끊고 ROS 쪽에 `connected=false`를 알린다.
- 앱은 heartbeat 응답이 10초 동안 없으면 소켓을 닫고 재연결한다.
- 재연결 간격은 `1, 2, 4, 8, 10, 10…초`에 ±20% jitter를 적용한다.
- 30초 이상 안정 연결되면 backoff를 초기화한다.
- 재연결 후 새 `session_id`를 받으며 place catalog와 robot status를 다시 동기화한다.
- 연결 끊김 중 command를 무기한 큐잉하지 않는다. 사용자의 `trip.start`/`trip.cancel`/`place.*`는 실패로 표시하고 재확인을 받는다.

---

## 9. 장소 프리셋과 주행

### 9.1 프리셋 모델

프리셋은 어르신이 홈 화면에서 고를 수 있는 **미리 지정해 둔 영역**이다. 집, 시장, 마트, 노인정, 교회, 절처럼 자주 가는 곳을 보호자가 미리 등록한다.

| 필드 | 타입 | 규칙 |
|---|---|---|
| `id` | string | 서버 생성. ASCII 소문자·숫자·`_`·`-`, 1~64자 |
| `kind` | string | `home`, `market`, `mart`, `senior_center`, `church`, `temple`, `hospital`, `pharmacy`, `other` |
| `label` | string | 화면 표시 이름. UTF-8 1~20자. 큰 글꼴에서 두 줄을 넘기지 않도록 짧게 |
| `lat` | number | `-90..90`, WGS84 |
| `lon` | number | `-180..180`, WGS84 |
| `radius_m` | number | `5..500`, 기본 30 |
| `enabled` | boolean | `false`면 홈 화면에서 선택 불가 |
| `sort_order` | integer | 홈 화면 타일 순서. 작은 값이 앞 |
| `is_default_origin` | boolean | 기본 출발지. 카탈로그가 비어 있지 않으면 전체에서 정확히 하나 |
| `address` | string/null | 등록 당시 역지오코딩 주소. 표시·확인용이며 판정에 쓰지 않는다 |

규칙:

- 프리셋 개수 상한은 `hello.ack`의 `max_places`(기본 12)다.
- `kind`가 같은 프리셋이 여러 개 있어도 된다(예: 마트 두 곳). `label`로 구분한다.
- 첫 프리셋을 추가할 때 기본 출발지가 지정되지 않았으면 서버가 그 프리셋을 기본 출발지로 지정한다.
- `is_default_origin=true`인 프리셋을 새로 지정하면 서버가 기존 기본 출발지를 자동으로 해제한다.
- 기본 출발지는 삭제할 수 없다. 다른 프리셋을 기본으로 지정한 뒤 삭제한다.
- 좌표의 단일 원본은 RPi5의 `places.yaml`이다. 앱은 목록을 하드코딩하지 않는다.

RPi5의 `places.yaml` 예시:

```yaml
version: 1
revision: "2026-08-27T12:00:00Z"
places:
  place_home:
    kind: home
    label: "집"
    lat: 36.1234
    lon: 128.5678
    radius_m: 20
    enabled: true
    sort_order: 10
    is_default_origin: true
    address: "경상북도 …"
  place_market:
    kind: market
    label: "시장"
    lat: 36.1251
    lon: 128.5702
    radius_m: 30
    enabled: true
    sort_order: 20
    is_default_origin: false
    address: "경상북도 …"
```

### 9.2 RPi5 → 앱: `place.catalog`

서버는 handshake 직후, 그리고 카탈로그가 바뀔 때마다 전체 목록을 보낸다. 부분 갱신은 없다.

```json
{
  "version": 1,
  "type": "place.catalog",
  "id": "da32d958-c86e-414a-b72f-edb9402948e8",
  "sent_at_ms": 1787792400100,
  "reply_to": null,
  "payload": {
    "revision": "2026-08-27T12:00:00Z",
    "max_places": 12,
    "default_origin_place_id": "place_home",
    "places": [
      {
        "id": "place_home",
        "kind": "home",
        "label": "집",
        "lat": 36.1234,
        "lon": 128.5678,
        "radius_m": 20,
        "enabled": true,
        "sort_order": 10,
        "is_default_origin": true,
        "address": "경상북도 …"
      },
      {
        "id": "place_market",
        "kind": "market",
        "label": "시장",
        "lat": 36.1251,
        "lon": 128.5702,
        "radius_m": 30,
        "enabled": true,
        "sort_order": 20,
        "is_default_origin": false,
        "address": null
      }
    ]
  }
}
```

- 좌표는 설정 화면의 지도 편집에 필요하므로 앱에 내려보낸다. 어르신 홈 화면에는 표시하지 않는다.
- 앱은 `enabled=false`인 장소를 홈 타일에 흐리게 표시하고 누르면 사용할 수 없는 이유를 알려 준다. 목록에서 감추지는 않는다(12.2 참조).
- 현재 선택이 새 catalog에서 사라지거나 비활성화되면 선택을 해제하고 안내한다.
- 마지막 정상 catalog를 로컬에 저장해 홈 화면을 즉시 그릴 수 있지만 연결 전에는 주행을 시작하지 않는다. 오프라인 표시가 필요하면 타일을 비활성 상태로 그린다.
- 빈 catalog에서는 `default_origin_place_id`를 `null`로 보낸다.

### 9.3 앱 → RPi5: `place.upsert`

프리셋을 추가하거나 수정할 때 보낸다. `place_id`가 `null`이면 추가, 값이 있으면 수정이다.

```json
{
  "version": 1,
  "type": "place.upsert",
  "id": "6d1f3f1e-5f6a-4a0e-9f6b-0a2f0a3f2b11",
  "sent_at_ms": 1787792500000,
  "reply_to": null,
  "payload": {
    "place_id": null,
    "catalog_revision": "2026-08-27T12:00:00Z",
    "kind": "senior_center",
    "label": "노인정",
    "lat": 36.1277,
    "lon": 128.5731,
    "radius_m": 25,
    "enabled": true,
    "sort_order": 40,
    "is_default_origin": false,
    "address": "경상북도 …",
    "source": "map_search"
  }
}
```

- `source`: `map_search`, `map_pin`, `manual_coords`, `current_location`. 어떤 경로로 지정했는지 기록용이다.
- `catalog_revision`이 서버의 최신 값과 다르면 서버는 `CATALOG_CONFLICT`를 반환한다. 앱은 최신 catalog를 받은 뒤 사용자에게 다시 확인받는다.
- 서버는 검증 후 `places.yaml`을 원자적으로 갱신하고 `ack`(새 `place_id`와 `catalog_revision` 포함)에 이어 새 `place.catalog`를 브로드캐스트한다.
- 주행 중(`mission_state`가 `starting`/`active`/`cancelling`)에는 현재 trip이 쓰는 장소의 좌표 수정을 거절한다(`INVALID_STATE`).

### 9.4 앱 → RPi5: `place.delete`

```json
{
  "version": 1,
  "type": "place.delete",
  "id": "9e7c9a20-3c4b-4c1e-9a2d-71b0f9a2c4d8",
  "sent_at_ms": 1787792510000,
  "reply_to": null,
  "payload": {
    "place_id": "place_temple",
    "catalog_revision": "2026-08-27T12:05:00Z"
  }
}
```

- 기본 출발지는 삭제할 수 없다(`INVALID_STATE`).
- 진행 중인 trip의 출발지/목적지는 삭제할 수 없다(`PLACE_IN_USE`).
- 삭제 확인 다이얼로그는 장소 이름을 그대로 보여 준다. "정말 삭제할까요?"만 띄우지 않는다.

### 9.5 앱 → RPi5: `trip.start`

```json
{
  "version": 1,
  "type": "trip.start",
  "id": "e87864c5-3c72-42f8-9565-cf9219aa7cf2",
  "sent_at_ms": 1787792410000,
  "reply_to": null,
  "payload": {
    "from_place_id": "place_home",
    "to_place_id": "place_market",
    "catalog_revision": "2026-08-27T12:00:00Z"
  }
}
```

- 출발지와 목적지는 같을 수 없다.
- 둘 다 현재 catalog에 있고 `enabled=true`여야 한다.
- 홈 화면에서 출발지를 따로 고르지 않았으면 `default_origin_place_id`를 `from_place_id`로 넣는다.
- 서버는 접수 시 `trip_id`가 포함된 `ack`를 반환한다.
- 앱은 ACK 전까지 시작 버튼을 비활성화하고 중복 탭을 막는다.
- 사용자가 시작을 확인하기 전에는 선택만으로 RPi5에 명령을 보내지 않는다.
- ACK 후 5초 동안 `실행 취소` action을 크게 표시한다.

### 9.6 앱 → RPi5: `trip.cancel`

```json
{
  "version": 1,
  "type": "trip.cancel",
  "id": "1a0ad887-2680-465d-af4a-c1956efe2a58",
  "sent_at_ms": 1787792413000,
  "reply_to": null,
  "payload": {
    "trip_id": "bbef019a-dd3a-437e-8f5c-388913ae9eca",
    "reason": "user_request"
  }
}
```

`trip.cancel`은 사용자가 누르는 `멈춤`에 대응하며 로봇의 실제 주행을 안전하게 멈추는 명령이다. 취소 ACK는 정지 명령을 접수했다는 뜻이므로 앱은 `멈추는 중입니다`를 표시하고, 실제 정지 완료는 `robot.status.mission_state=cancelled`로 확인한다. 감속 방식과 모터 정지 절차는 로봇 측 mission/safety 계층이 책임진다.

### 9.7 RPi5 → 앱: `robot.status`

```json
{
  "version": 1,
  "type": "robot.status",
  "id": "025832dc-f9ed-48bd-8354-602d314d27ad",
  "sent_at_ms": 1787792414000,
  "reply_to": null,
  "payload": {
    "robot_id": "mr-001",
    "bridge_state": "ready",
    "mission_state": "active",
    "tracking_state": "unavailable",
    "trip_id": "bbef019a-dd3a-437e-8f5c-388913ae9eca",
    "from_place_id": "place_home",
    "to_place_id": "place_market",
    "battery_percent": null,
    "last_error": null
  }
}
```

| 필드 | 허용값 |
|---|---|
| `bridge_state` | `ready`, `degraded`, `error` |
| `mission_state` | `idle`, `starting`, `active`, `arrived`, `cancelling`, `cancelled`, `error`, `unknown` |
| `tracking_state` | `unavailable`, `searching`, `following`, `lost`, `error`, `unknown` |
| `battery_percent` | `0..100` 또는 `null` |

- 서버는 변경 즉시 보내고 변경이 없어도 2초마다 최신 상태를 보낸다.
- 앱은 마지막 수신 후 5초가 지나면 상태를 `stale`로 표시한다.
- 앱이 모르는 enum은 crash시키지 않고 `unknown`으로 표시한다.
- 현재 ArUco와 배터리 인터페이스가 없으므로 초기 구현은 각각 `unavailable`, `null`을 보낸다.

### 9.8 목적지 좌표의 역할

보호자는 위도·경도를 직접 입력하는 대신 지도 검색이나 핀 이동으로 목적지 좌표와 반경을 지정한다. 앱은 이 값을 검증해 `place.upsert`로 RPi5에 전달하는 데까지만 책임진다.

RPi5의 ROS 2 mission 계층은 `places.yaml`의 `lat`, `lon`, `radius_m`를 목적지 설정으로 사용하고 로봇 측 위치 추정과 주행 정책에 따라 도착 여부를 판단한다. 정확한 ROS 노드 구성과 판정 알고리즘은 Android 앱 계약 밖이며 로봇 담당자가 구현한다. 휴대폰의 실시간 GPS는 이 도착 판단이나 로봇 제어에 사용하지 않는다.

---

## 10. 위치와 SOS

### 10.1 위치 캐시

- 포그라운드 서비스가 실행 중일 때 30초 주기로 최신 위치를 갱신한다.
- 메모리에는 최신 정상 fix 1개만 둔다. 정확한 좌표는 디스크에 영구 저장하지 않는다.
- 활성 trip 동안 연결돼 있으면 60초마다 `reason=periodic` 위치를 RPi5로 보낸다.
- SOS가 오면 위치 API를 기다리지 않고 캐시 결과부터 보낸다.
- `age_ms`는 측정 시각부터 전송 시각까지를 monotonic time으로 계산한다.

프리셋 좌표(`places.yaml`)와 실시간 폰 위치는 서로 다른 데이터다. 프리셋 좌표는 저장되지만 실시간 위치는 저장하지 않는다.

### 10.2 앱 → RPi5: `location.update`

다음은 정상 fix 예시다.

```json
{
  "version": 1,
  "type": "location.update",
  "id": "ce249750-c80b-47e1-a2ed-29c50b4f1b31",
  "sent_at_ms": 1787792420000,
  "reply_to": null,
  "payload": {
    "reason": "sos",
    "sos_id": "835fcafe-60ac-4517-9f06-50bcd2486b6c",
    "has_fix": true,
    "lat": 36.1234,
    "lon": 128.5678,
    "horizontal_accuracy_m": 4.2,
    "captured_at_ms": 1787792419200,
    "age_ms": 800,
    "source": "cache",
    "failure_reason": null
  }
}
```

측위에 실패해도 필드를 생략하지 않는다.

```json
{
  "version": 1,
  "type": "location.update",
  "id": "c1e35838-37e2-4789-88d5-e228120b09b2",
  "sent_at_ms": 1787792420000,
  "reply_to": null,
  "payload": {
    "reason": "sos",
    "sos_id": "835fcafe-60ac-4517-9f06-50bcd2486b6c",
    "has_fix": false,
    "lat": null,
    "lon": null,
    "horizontal_accuracy_m": null,
    "captured_at_ms": null,
    "age_ms": null,
    "source": "none",
    "failure_reason": "provider_disabled"
  }
}
```

| 필드 | 규칙 |
|---|---|
| `reason` | `periodic` 또는 `sos` |
| `sos_id` | SOS 응답이면 해당 UUID, periodic이면 `null` |
| `lat` | `-90..90` 또는 `null` |
| `lon` | `-180..180` 또는 `null` |
| `horizontal_accuracy_m` | 0 이상의 유한 수 또는 `null` |
| `source` | `cache`, `fresh`, `none` |
| `failure_reason` | `permission_denied`, `provider_disabled`, `timeout`, `unavailable`, `null` |

RPi5는 `age_ms > 300000` 또는 `horizontal_accuracy_m > 50`인 fix를 낮은 신뢰도로 표시하지만 폐기하지는 않는다.

### 10.3 RPi5 → 앱: `sos.request`

```json
{
  "version": 1,
  "type": "sos.request",
  "id": "4ccb97ee-a8bf-40de-a211-5254059694ad",
  "sent_at_ms": 1787792420000,
  "reply_to": null,
  "payload": {
    "sos_id": "835fcafe-60ac-4517-9f06-50bcd2486b6c",
    "reason": "tracking_lost",
    "severity": "critical",
    "trip_id": "bbef019a-dd3a-437e-8f5c-388913ae9eca"
  }
}
```

| 필드 | 허용값 |
|---|---|
| `reason` | `tracking_lost`, `stuck`, `low_battery`, `manual`, `unknown` |
| `severity` | `critical`, `warning` |
| `trip_id` | 활성 trip UUID 또는 `null` |

### 10.4 앱 → RPi5: `sos.ack`

```json
{
  "version": 1,
  "type": "sos.ack",
  "id": "584bb007-a09d-4389-9e90-511490b2d82c",
  "sent_at_ms": 1787792420100,
  "reply_to": "4ccb97ee-a8bf-40de-a211-5254059694ad",
  "payload": {
    "sos_id": "835fcafe-60ac-4517-9f06-50bcd2486b6c",
    "received": true
  }
}
```

### 10.5 SOS 순서와 시간 제한

```text
RPi5 bridge                                      Android app
     │  sos.request(sos_id)                           │
     ├───────────────────────────────────────────────►│
     │                                                ├─ UI/알림/진동 갱신
     │  sos.ack                          ≤ 500 ms     │
     │◄───────────────────────────────────────────────┤
     │  location.update(cache 또는 none) ≤ 500 ms     │
     │◄───────────────────────────────────────────────┤
     │                                                ├─ 새 고정밀 측위, 최대 30초
     │  location.update(fresh 또는 none)              │
     │◄───────────────────────────────────────────────┤
```

- 앱은 `sos.ack`와 캐시 위치 전송을 새 측위보다 우선한다.
- 캐시가 없어도 500 ms 안에 `source=none` 위치 메시지를 보낸다.
- 새 측위는 최대 30초 시도하고 성공 시 `source=fresh`를 한 번 보낸다.
- 30초 안에 실패하면 최종 `source=none`, `failure_reason=timeout`을 보낸다.
- SOS 처리 중에만 partial WakeLock을 잡을 수 있으며 35초 hard timeout과 `finally` 해제가 있어야 한다.
- 같은 `sos_id`를 다시 받으면 알림/진동을 중복 생성하지 않되 ACK와 현재 최선 위치는 다시 전송한다.
- RPi5는 ACK가 2초 안에 없으면 같은 `sos_id`로 최대 3회 재전송한다.
- ACK 3회 실패 시 앱 연결 실패 상태를 ROS에 발행하고 로컬 폴백을 실행한다.

### 10.6 RPi5 → 앱: `sos.clear`

SOS 원인이 해소되거나 로봇 측에서 사건을 종료하면 같은 `sos_id`로 종료를 통지한다.

```json
{
  "version": 1,
  "type": "sos.clear",
  "id": "18dcbcc2-ecc8-48b9-9312-727947f9cb52",
  "sent_at_ms": 1787792450000,
  "reply_to": null,
  "payload": {
    "sos_id": "835fcafe-60ac-4517-9f06-50bcd2486b6c",
    "resolution": "tracking_recovered"
  }
}
```

`resolution`은 `tracking_recovered`, `handled`, `cancelled`, `unknown` 중 하나다. 앱은 `sos.clear` 전까지 해당 SOS를 active로 유지한다. 사용자가 SOS 화면의 `확인`을 누르면 이전 화면으로 돌아갈 수 있지만 ongoing SOS 표시와 알림은 `sos.clear`까지 유지한다. 재연결 시 active SOS가 있으면 서버가 `sos.request`를 다시 보내 상태를 복원한다.

`[OPEN-R3]` ACK 3회 실패 때의 로컬 폴백을 확정해야 한다. 후보는 로봇 정지, 부저, 경광등이며 현장 릴리스 전에 반드시 결정한다.

MVP에서는 RPi5 브리지가 앱의 SOS ACK와 위치를 ROS 2 토픽으로 변환하고 로봇 측 노드가 이를 확인한다. 앱 개발자는 그 뒤의 ROS 처리 방식을 구현하거나 알 필요가 없으며, 브리지 ACK를 받으면 앱→RPi5 전달이 완료된 것으로 본다.

`[OPEN-P1]` 로봇 측에서 확인한 좌표를 실제 보호자에게 어떻게 전달할지는 별도 제품 요구사항이다. 자동 SMS/전화는 Android 권한과 배포 정책 검토가 필요하므로 Android MVP 인수 범위에 넣지 않는다.

---

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

## 14. 디자인 시스템과 사용할 스킬

### 14.1 작업 전에 로드할 스킬

UI를 설계하거나 Compose 코드를 쓰기 전에 다음 스킬을 로드해서 그 지침을 따른다. 세 개는 역할이 겹치지 않는다.

| 스킬 | 담당 | 설치 |
|---|---|---|
| `mobile-android-design` | 화면 구조, 내비게이션, adaptive layout, 접근성, state hoisting 등 Android UX 전반 | `npx skills add https://github.com/wshobson/agents --skill mobile-android-design` |
| `material-3` | Material 3 / M3 Expressive 디자인 언어, 색·타이포·spacing·motion 토큰, 컴포넌트 선택, MD3 기준 UI 감사 | `npx --yes skills add hamen/material-3-skill --skill material-3 -y` |
| Modern Jetpack Compose | Compose 구현 품질. 낡은 패턴(`collectAsState` 남용, 잘못된 recomposition, Scaffold padding 누락, Material 2 API, 구 Accompanist) 교정 | `npx skills add https://github.com/anhvt52/jetpack-compose-skills` |

사용 지침:

1. 새 화면을 설계할 때 `mobile-android-design`으로 구조와 내비게이션, 접근성을 먼저 잡는다.
2. 색·타입·간격·모션·컴포넌트를 고를 때 `material-3`을 근거로 삼는다. 14.2의 브랜드 토큰을 M3 color role에 매핑하는 작업도 여기서 한다.
3. Compose 코드를 작성·리뷰할 때 Modern Jetpack Compose 스킬의 규칙을 적용한다.
4. 기존 화면을 손볼 때는 `material-3`의 감사 기능으로 어긋난 부분을 먼저 확인한다.

M3 Expressive를 쓰되 어르신 사용성이 우선이다. 표현적인 shape·컴포넌트는 채택하고, 크고 빠른 모션, 낮은 대비의 장식, 밀도 높은 레이아웃은 사용 층에서 쓰지 않는다. 둘이 충돌하면 이 문서의 크기·대비·단순성 규칙이 이긴다.

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

## 15. RPi5 ↔ ROS 2 내부 계약

이 절은 로봇 담당자용이며 Android 개발자는 구현할 필요가 없다. 앱이 어떤 값을 받게 되는지 이해하는 용도로 읽으면 된다. 토픽과 메시지는 ArUco 노드가 생기기 전에 연결 지점을 고정하기 위한 제안이다.

### 15.1 패키지

- 새 패키지: `software/ros_pkgs/mr_phone_bridge`
- 메시지 확장: `software/ros_pkgs/mr_msgs`
- node name: `mr_phone_bridge`
- WebSocket 처리와 ROS executor가 서로를 blocking하지 않게 분리한다.
- 잘못된 앱 입력은 ROS에 발행하기 전에 검증한다.

### 15.2 제안 토픽

| 방향 | 토픽 | 제안 타입 | QoS | 의미 |
|---|---|---|---|---|
| bridge → ROS | `/phone/trip_command` | `mr_msgs/TripCommand` | Reliable, KeepLast 10 | start/cancel 요청 |
| bridge → ROS | `/phone/location` | `mr_msgs/PhoneLocation` | Reliable, KeepLast 10 | periodic/SOS 위치 |
| bridge → ROS | `/phone/link_status` | `mr_msgs/PhoneLinkStatus` | Reliable, TransientLocal, KeepLast 1 | 앱 연결 상태 |
| bridge → ROS | `/robot/place_catalog` | `mr_msgs/PlaceCatalog` | Reliable, TransientLocal, KeepLast 1 | 현재 프리셋 전체와 revision |
| ROS → bridge | `/robot/app_status` | `mr_msgs/RobotAppStatus` | Reliable, TransientLocal, KeepLast 1 | 앱용 상위 상태 |
| ROS → bridge | `/safety/sos_request` | `mr_msgs/SosRequest` | Reliable, KeepLast 10 | ArUco/안전 계층의 SOS |
| ROS → bridge | `/safety/sos_clear` | `mr_msgs/SosClear` | Reliable, KeepLast 10 | 활성 SOS 종료 |
| bridge → ROS | `/safety/sos_ack` | `mr_msgs/SosAck` | Reliable, KeepLast 10 | 앱 ACK/위치 결과 |

`/phone/trip_command`의 cancel 요청은 로봇의 실제 주행을 안전하게 멈춰야 한다. WebSocket ACK와 ROS 명령 접수는 정지 완료가 아니며, mission/safety 계층이 정지를 완료한 뒤 `/robot/app_status`의 mission 상태를 `cancelled`로 갱신한다.

새 메시지는 최소한 아래 정보를 보존해야 한다.

- `TripCommand`: request id, trip id, start/cancel enum, from/to place id, ROS stamp
- `PhoneLocation`: message id, optional sos id, has-fix, lat/lon, accuracy, age, source, failure reason
- `PhoneLinkStatus`: connected, app instance id, session id, last seen stamp
- `PlaceCatalog`: revision, place 배열(id, kind, label, lat, lon, radius_m, enabled, sort_order, is_default_origin)
- `RobotAppStatus`: mission/tracking 상태, trip id, from/to, optional battery, last error
- `SosRequest`: sos id, reason, severity, trip id, ROS stamp
- `SosClear`: sos id, resolution, ROS stamp
- `SosAck`: sos id, app received 여부, 위치 상태, ROS stamp

정확한 `.msg` 필드와 enum 숫자는 구현 PR에서 확정하되 WebSocket v1 의미를 바꾸면 안 된다.

### 15.3 프리셋 저장 규칙

- `places.yaml`의 소유자는 브리지다. 임시 파일에 쓰고 `rename`으로 교체하는 원자적 갱신을 사용한다.
- 갱신 성공 후에만 새 `revision`을 발급하고 `place.catalog` 브로드캐스트와 `/robot/place_catalog` 발행을 수행한다.
- 갱신 실패는 `INTERNAL_ERROR`로 회신하고 기존 파일을 유지한다.
- 최근 N개 이전 버전을 백업해 롤백할 수 있게 한다.
- 앱이 보낸 값은 좌표 범위, 라벨 길이, `kind` enum, 개수 상한, 기본 출발지 유일성까지 서버에서 다시 검증한다. 앱 검증을 신뢰하지 않는다.

### 15.4 현재 베이스 스택과의 관계

- `/base/status`를 읽을 경우 현재 계약대로 `BEST_EFFORT`, `KEEP_LAST(1)` 구독을 사용한다.
- `/base/status`에는 배터리 필드가 없으므로 `battery_percent=null`이 정상이다.
- 브리지는 `/joint_states`, `/odom`, TF를 앱으로 그대로 전달하지 않는다.
- 브리지는 `/cmd_vel`을 구독하거나 발행하지 않는다.
- 앱 heartbeat를 MCU 명령 워치독의 feed 조건으로 사용하지 않는다.

### 15.5 향후 ArUco 연결

ArUco 노드는 앱이나 WebSocket을 알 필요가 없다.

1. 추적 상태가 바뀌면 상위 상태 노드 또는 ArUco 노드가 `/robot/app_status.tracking_state`를 갱신한다.
2. 사용자 추적 상실 등 SOS 조건이 확정되면 `/safety/sos_request`를 한 번 발행한다.
3. 재시도, 앱 ACK, GPS 회신은 `mr_phone_bridge`가 담당한다.
4. 추적 회복 등 종료 조건이 확정되면 같은 id로 `/safety/sos_clear`를 발행한다.
5. ArUco 노드가 결과를 알아야 하면 `/safety/sos_ack`를 구독한다.

이 구조에서는 ArUco 구현이 늦어져도 로봇 담당자가 CLI test publisher로 SOS를 발행해 앱 통합 시험을 할 수 있다.

---

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
- 프리셋 좌표는 사용자가 명시적으로 등록한 값이므로 RPi5의 `places.yaml`과 앱의 catalog 캐시에 저장한다. 다만 로그와 진단 파일에는 좌표를 넣지 않고 `place_id`와 `label`만 남긴다.
- 지도 검색어와 검색 결과를 저장하거나 로그에 남기지 않는다.
- 분석 SDK, 광고 SDK, 원격 crash upload는 사전 승인 없이 추가하지 않는다.
- 진단 내보내기는 사용자의 명시적 action으로 수행하고 좌표를 제외한다.
- 앱 로그에는 `message id`, `type`, 송수신 시각, 결과 code를 남겨 양쪽 로그를 맞출 수 있게 한다.
- RPi5는 protocol 오류를 rate limit해 잘못된 client가 로그를 고갈시키지 못하게 한다.

---

## 18. 앱 저장소 필수 산출물

```text
senior-care-android/
  README.md
  LICENSE
  app/
  docs/
    android-app-spec.md          # 이 문서
    protocol/
      fixtures/
        hello.json
        hello-ack.json
        place-catalog.json
        place-upsert.json
        place-upsert-ack.json
        place-delete.json
        trip-start.json
        trip-start-ack.json
        robot-status.json
        sos-request.json
        sos-clear.json
        location-cache.json
        location-none.json
        error.json
    test-report.md
  tools/
    mock-bridge/
  .github/
    workflows/
      android.yml
```

`README.md`에는 최소한 JDK/Android SDK 버전, 지도 키를 `local.properties`에 넣는 방법, debug build, 실기기 설치, mock 실행, endpoint 변경, 권한 초기화, 테스트 명령을 적는다.

mock bridge는 ROS 2를 설치하지 않은 개발 PC에서 실행돼야 하며 다음 scenario를 선택할 수 있어야 한다.

- 정상 handshake/catalog/status
- 프리셋 6개(집·시장·마트·노인정·교회·절)로 시작하는 기본 카탈로그
- `place.upsert`/`place.delete` 정상 처리와 새 catalog 브로드캐스트
- `CATALOG_CONFLICT`, `PLACE_LIMIT`, `PLACE_IN_USE` 오류 재현
- 빈 카탈로그(프리셋 0개)
- `trip.start` 정상 ACK와 active 전환
- ACK 2초 지연 및 오류 응답
- 주기적 강제 연결 종료
- `tracking_lost` SOS 발행
- 같은 `sos_id` 3회 재발행
- active SOS의 `sos.clear` 종료
- 잘못된 JSON과 알 수 없는 enum

fixture는 문서 예시와 자동 테스트가 함께 읽는 단일 파일로 유지해 문서와 코드가 어긋나는 것을 줄인다.

---

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

## 20. 개발 순서

### 단계 0 — 계약 고정

- 이 문서 검토
- `[OPEN-A1]` 지원 기기 확인
- 지원 target SDK에 맞는 로컬 네트워크 권한 흐름과 핫스팟 환경의 mDNS 동작 확인
- 지도 API 키 발급과 전달
- WebSocket fixture 14개 확정

### 단계 1 — 서로 독립 개발

Android 개발자:

1. 앱 skeleton, theme, 어르신 홈과 domain 상태 머신
2. protocol codec와 fixture test
3. mock bridge와 정상 연결, catalog 표시
4. trip flow(홈 → 확인 → 주행 중)
5. 설정 층과 프리셋 관리, 지도 검색·핀 지정
6. ForegroundService와 위치 캐시
7. SOS와 실패 모드

로봇 담당자:

1. `mr_msgs` 상위 메시지 결정
2. `mr_phone_bridge`의 handshake/catalog/heartbeat
3. `places.yaml` 검증과 원자적 갱신, upsert/delete 처리
4. trip/location ROS 변환
5. CLI test publisher로 SOS flow
6. launch/config/systemd와 방화벽

### 단계 2 — ArUco 없이 통합

- 앱과 실제 RPi5 handshake
- 앱에서 등록한 프리셋이 `places.yaml`에 반영되는지 확인
- trip command와 상태 반영
- CLI에서 `/safety/sos_request` 발행
- ACK, cache, fresh 위치 확인
- 화면 꺼짐과 네트워크 끊김 시험

### 단계 3 — ArUco 연결

- tracking 상태 → `/robot/app_status`
- 추적 상실 조건 → `/safety/sos_request`
- 중복 SOS 억제와 재무장 조건 검증

### 단계 4 — 현장 릴리스

- `[OPEN-R1]` 네트워크 보안
- `[OPEN-R3]` 로컬 폴백
- `[OPEN-M1]` 지도 키 보호 방식
- `[OPEN-P1]` 최종 SOS 전달 경로
- 어르신 대상 사용성 시험과 지원 기기별 장시간·배터리·Doze 시험

---

## 21. 미결정 항목 요약

| ID | 결정 내용 | 앱 개발 차단 | 현장 릴리스 차단 | 담당 |
|---|---|---:|---:|---|
| `OPEN-A1` | 실제 지원 Android 최저 버전/기기 | O | O | 공동 |
| `OPEN-M1` | 지도 REST 키 보호(사용량 제한 또는 프록시) | X | O | 로봇/제품 담당 |
| `OPEN-R1` | 공용망 사용 여부와 WSS/페어링 | X | O | 로봇 담당 |
| `OPEN-R3` | SOS ACK 3회 실패 시 로컬 폴백 | X | O | 로봇 담당 |
| `OPEN-P1` | 보호자 최종 연락 경로 | X | 제품 SOS 기능에는 O | 제품 담당 |
| `OPEN-D1` | dark theme 필요 여부 | X | X | 디자인/앱 담당 |
| `OPEN-D2` | 설정 진입 PIN 추가 여부 | X | X | 제품/앱 담당 |

착수 전에 필요한 것은 `OPEN-A1`과 지도 키 전달뿐이고 나머지는 이 문서의 mock 계약으로 병렬 개발할 수 있다.

---

## 22. 참고 문서

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
