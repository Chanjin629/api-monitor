# API Monitor

개발자가 자신의 API URL을 등록하면 주기적으로 호출해 상태와 응답 시간을 기록하고, 장애와 복구를 감지해 Discord로 알려 주는 서비스입니다.

> 현재 개발 중입니다. 진행 상황은 [docs/PROGRESS.md](docs/PROGRESS.md)에 있습니다.

## 기술 스택

| 영역 | 기술 |
| --- | --- |
| Backend | Kotlin, Spring Boot 4.1, Spring Data JPA, Flyway |
| Frontend | React, TypeScript, Vite |
| DB | MariaDB 11.8 |
| 테스트 | JUnit 5, MockK, Testcontainers |
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

## 로컬 실행

필요한 것: JDK 21, Docker Desktop

```bash
# 1. 개발용 MariaDB 실행 (127.0.0.1:3307)
docker compose -f infra/docker-compose.dev.yml up -d

# 2. 서버 실행 (local 프로필, 테이블은 Flyway가 자동 생성)
cd backend && ./gradlew bootRun
```

- API 문서: http://localhost:8080/swagger-ui.html
- DB 접속 정보와 포트를 바꾸려면 [.env.example](.env.example)의 환경 변수를 지정합니다.

## 테스트

```bash
cd backend && ./gradlew test
```

통합 테스트는 Testcontainers로 MariaDB 컨테이너를 띄우므로 Docker가 실행 중이어야 합니다.
