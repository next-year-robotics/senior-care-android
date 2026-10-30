# Senior Care Android

어르신용 로봇 동행 앱. RPi5의 폰 브리지(`phone-bridge/1`, WebSocket)에 연결해 목적지 타일로 주행을 시작·정지하고, SOS 요청에 폰 위치를 회신한다.

- Kotlin·Jetpack Compose 기반 단일 앱 모듈
- 장소 프리셋 편집, Kakao 장소 검색·지도 핀·반경 지정, 좌표 직접 입력
- 위치 포그라운드 서비스, 연결·SOS 알림, SOS 우선 응답
- [앱 명세](docs/android-app-spec.md), [브리지 프로토콜](contracts/phone-bridge/v1/PROTOCOL.md)

프로토콜 정본과 mock bridge는 [mobile-robot](https://github.com/next-year-robotics/mobile-robot)에 있다. 앱은 ROS 2에 직접 접속하지 않는다.

## 빌드·설치

JDK 21과 Android SDK(platform 37.2, build-tools 37.0.0)가 필요하다. 지원 기기는 Galaxy S26 Ultra(Android 16, minSdk 36)다.

```bash
cp local.properties.example local.properties
# sdk.dir와 Kakao 네이티브 앱 키·REST API 키 설정
./gradlew assembleDebug testDebugUnitTest lintDebug
./gradlew installDebug
```

키가 없으면 더미 키로 빌드되며 지도·검색 대신 좌표를 직접 입력할 수 있다. `local.properties`와 서명 키는 커밋하지 않는다.

## 로봇·mock 연결

로컬 mock을 실행하려면 `uv`와 인접한 `mobile-robot` 저장소가 필요하다.

```bash
tools/mock-bridge/run.sh --scenario default6
```

홈 상단 톱니를 1.5초 길게 눌러 설정으로 들어간다. 로봇 연결에서 자동 탐색하거나 개발 PC의 `IP:8765`를 입력한다. USB 개발은 debug 앱에서 loopback을 사용할 수 있다.

```bash
adb reverse tcp:8765 tcp:8765
# 수동 연결: 127.0.0.1 / 8765
```

연결은 앱 화면의 사용자 동작으로 위치 포그라운드 서비스를 시작한다. 위치 권한과 기기의 위치 서비스가 필요하며, 로컬 네트워크 권한은 API 37 이상에서 요청한다. 알림을 거절해도 앱 내부 SOS와 ACK는 동작한다. 연결 종료는 설정 또는 연결 알림에서 한다.

평문 브리지는 개인 핫스팟 안에서 사용한다. release는 사설·링크로컬 IP만 허용하며, debug는 loopback도 허용한다. 지도·검색은 HTTPS를 사용한다.

## 장소 설정·SOS

설정의 장소 프리셋 관리에서 추가·수정·삭제한다. 지도에서 위치를 고른 뒤 편집 화면의 저장을 눌러야 로봇에 반영된다. 목록 충돌 시 최신 값을 불러와 다시 확인하며, 주행 중에는 저장할 수 없다.

SOS는 일반 명령보다 먼저 수신 확인과 캐시 위치를 보내고, 최대 30초 동안 새 위치를 확인한다. 화면의 위치 전달 상태는 브리지 ACK를 기준으로 표시한다. 사용자가 확인을 눌러도 로봇의 `sos.clear`가 올 때까지 배너와 알림을 유지한다.

## 검증·계약 동기화

```bash
./gradlew assembleDebug testDebugUnitTest lintDebug
python3 tools/mock-bridge/check.py
scripts/sync-contracts.sh ../mobile-robot/software/contracts
```

`check.py`는 독립 mock 12개와 JVM 회귀 시험을 실행한 뒤 자식 프로세스를 종료한다. 기본 포트는 18800~18811이며 `MOCK_MATRIX_BASE_PORT`로 변경할 수 있다. 실기기 시험은 별도로 진행한다.

```bash
tools/mock-bridge/run.sh --scenario sos-x3 --measure-latency
```

SOS 계측은 서버 송신 완료부터 응답 수신까지의 monotonic 시간을 출력한다. `elapsed_ms`는 최근 재송신, `since_first_ms`는 최초 요청 기준이다. 좌표와 메시지 본문은 출력하지 않는다.
