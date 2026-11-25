# API Monitor

[![CI](https://github.com/Chanjin629/api-monitor/actions/workflows/ci.yml/badge.svg)](https://github.com/Chanjin629/api-monitor/actions/workflows/ci.yml)

개발자가 자신의 API URL을 등록하면 주기적으로 호출해 상태와 응답 시간을 기록하고, 장애와 복구를 감지해 Discord로 알려 주는 서비스입니다.

> 현재 개발 중입니다. 진행 상황은 [docs/PROGRESS.md](docs/PROGRESS.md)에 있습니다.

## 기술 스택

| 영역 | 기술 |
| --- | --- |
| Backend | Kotlin, Spring Boot 4.1, Spring Data JPA, Flyway |
| Frontend | React, TypeScript, Vite |
| DB | MariaDB 11.8 |
| 인증 | GitHub OAuth, JWT (Access 15분), Refresh Token 교체·재사용 감지 |
| 테스트 | JUnit 5, MockK, Testcontainers, Vitest, k6 (부하 테스트) |
| 배포 | Docker Compose, GitHub Actions, Caddy |

## 설계 문서

| 문서 | 내용 |
| --- | --- |
| [요구사항](docs/01-requirements.md) | 기능·비기능 요구사항, MVP 범위, 결정 사항 |
| [아키텍처](docs/02-architecture.md) | 구성도, 검사·장애·알림 흐름 |
| [ERD](docs/03-erd.md) | 테이블 설계와 보관 전략 |
| [디렉터리 구조](docs/04-structure.md) | 패키지와 폴더 구성 |
| [API 명세](docs/05-api-spec.md) | 엔드포인트, 응답 형식, 에러 코드 |
| [화면 설계](docs/06-screens.md) | 화면 구성과 API 연결 |
| [부하 테스트](docs/07-load-test.md) | k6 측정 방법, 결과, Redis·Kafka 판단 |

## 로컬 실행

필요한 것: JDK 21, Node.js 24, Docker Desktop, [GitHub OAuth App](https://github.com/settings/developers)

1. GitHub OAuth App을 만들고 환경 변수 `GITHUB_CLIENT_ID`, `GITHUB_CLIENT_SECRET`에 저장합니다.
   - Homepage URL: `http://localhost:5173`
   - Redirect URI: `http://localhost:5173/login/oauth2/code/github`

```bash
# 2. 개발용 MariaDB 실행 (127.0.0.1:3307)
docker compose -f infra/docker-compose.dev.yml up -d

# 3. 서버 실행 (local 프로필, 테이블은 Flyway가 자동 생성)
cd backend && ./gradlew bootRun

# 4. 화면 실행 (/api, /oauth2 요청은 Vite 프록시가 서버로 전달)
cd frontend && npm install && npm run dev
```

- 화면: http://localhost:5173 (GitHub 로그인 또는 데모 체험)
- 데모 체험: GitHub 권한 승인 없이 읽기 전용으로 둘러볼 수 있습니다. 서버를 시작할 때와 매일 한 번, 현재 시각 기준으로 모니터 5개와 30일치 기록·장애를 다시 만듭니다. 데모 주소는 example 도메인이라 실제로 검사하지 않습니다.
- API 문서: http://localhost:8080/swagger-ui.html (데모 로그인으로 받은 accessToken을 Authorize에 입력)
- 환경 변수 목록은 [.env.example](.env.example)에 있습니다. 운영에서는 `JWT_SECRET`과 요청 헤더 암호화 키 `CRYPTO_SECRET`(Base64로 인코딩한 32바이트)을 반드시 지정합니다.

## 주요 API

| 기능 | API |
| --- | --- |
| 로그인 | `GET /oauth2/authorization/github`, `POST /api/v1/auth/demo`, `POST /api/v1/auth/refresh`, `POST /api/v1/auth/logout` |
| 회원 | `GET /api/v1/members/me`, `DELETE /api/v1/members/me` |
| 모니터 | `GET·POST /api/v1/monitors`, `GET·PATCH·DELETE /api/v1/monitors/{id}`, `POST /api/v1/monitors/{id}/pause`, `/resume` |
| 알림 채널 | `GET·POST /api/v1/notification-channels`, `PATCH·DELETE /api/v1/notification-channels/{id}`, `POST /api/v1/notification-channels/{id}/test` |
| 대시보드 | `GET /api/v1/dashboard/summary`, `GET /api/v1/monitors/{id}/stats?range=24h\|7d\|30d`, `GET /api/v1/monitors/{id}/checks` |
| 장애 이력 | `GET /api/v1/incidents`, `GET /api/v1/monitors/{id}/incidents` |

- 모니터 URL은 등록·수정할 때 SSRF 검증을 거칩니다. 내부망, 루프백, 클라우드 메타데이터 주소 등은 `422 URL_BLOCKED`로 거부합니다.
- 요청 헤더 값은 AES-GCM으로 암호화해 저장하고, 응답에서는 `********`로 가립니다.
- 장애가 시작되거나 복구되면 등록한 Discord Webhook으로 한 번씩 알립니다. Webhook 주소도 암호화해 저장하고 끝 4자리만 보여 줍니다.
- 자세한 요청·응답 형식은 [API 명세](docs/05-api-spec.md)와 Swagger UI를 참고하세요.

## 서버 메트릭과 로그 보기 (Prometheus, Grafana, Loki)

```bash
# 앱을 실행한 상태에서 Prometheus, Grafana, Loki, Alloy를 함께 띄운다
docker compose -f infra/docker-compose.dev.yml --profile observability up -d
```

- Grafana: http://localhost:3000 ("API Monitor 서버" 대시보드가 첫 화면으로 열립니다. 개발용이라 로그인 없이 보기 권한으로 열립니다)
- Prometheus: http://localhost:9090
- 앱의 Actuator는 관리 포트 8081에서만 열립니다 (`/actuator/health`, `/actuator/prometheus`). 8080으로는 접근할 수 없습니다.
- 우리 서버 자체의 메트릭만 수집합니다. 고객 API의 검사 이력은 서비스 화면에서 봅니다.
- 로그: local 프로필로 실행하면 앱이 `backend/logs/api-monitor.log`에 JSON 로그를 쓰고, Alloy가 읽어 Loki로 보냅니다. 대시보드 아래쪽 "로그" 행에서 최근 오류·경고를 봅니다.
- traceId로 찾기: API 오류 응답의 `error.traceId` 값을 대시보드 위쪽 `traceId` 칸에 넣으면 그 요청의 로그만 보입니다.
- Alloy 수집 상태: http://localhost:12345

## 테스트

```bash
cd backend && ./gradlew test
cd frontend && npm test
```

Backend 통합 테스트는 Testcontainers로 MariaDB 컨테이너를 띄우므로 Docker가 실행 중이어야 합니다.
