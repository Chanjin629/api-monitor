# API 모니터링 SaaS

개발자가 자신의 API URL을 등록하면 주기적으로 호출해 상태와 응답 시간을 기록하고, 장애와 복구를 감지해 알려주는 서비스.
목표는 포트폴리오용으로 실제 서비스 수준의 완성도를 갖춘 프로젝트를 만드는 것이다.

## 진행 상황

- 현재 단계와 완료 내역은 `docs/PROGRESS.md`를 기준으로 한다. 작업을 시작할 때 먼저 읽는다.
- 설계 문서는 `docs/`에 있다. 구현은 이 문서와 일치해야 하고, 설계를 바꿔야 하면 코드보다 문서를 먼저 고친다.

## 기술 스택 (임의 변경 금지)

| 영역 | 기술 |
| --- | --- |
| Backend | Kotlin (JVM 21), Spring Boot 4.1, Gradle (Kotlin DSL, `kotlin-spring`·`kotlin-jpa` 플러그인), Spring Security, Spring Security OAuth2 Client, jjwt, Spring Data JPA, Spring Validation, WebClient, kotlinx-coroutines, Spring Scheduler |
| Frontend | React, TypeScript, Vite, React Router, TanStack Query, Recharts, Tailwind CSS |
| DB / Cache | MariaDB, Flyway, Redis (17단계부터) |
| 비동기 | Kafka (배포 후 18단계에서 Worker 분리 학습·포트폴리오 목적으로 도입) |
| 관측성 | Actuator, Micrometer, Prometheus, Grafana, Grafana Alloy, Loki |
| 테스트 | JUnit 5, MockK, Testcontainers, WireMock, k6 (부하 테스트) |
| 배포 | Docker, Docker Compose, GitHub Actions, Caddy (HTTPS·리버스 프록시) |
| 문서 | springdoc-openapi (Swagger UI) |

- 라이브러리 버전은 프로젝트 생성 시 최신 안정 버전으로 정하고 이후 임의로 올리지 않는다.
- 위 목록에 없는 라이브러리가 필요하면 추가 전에 이유를 설명하고 승인을 받는다.
- Promtail은 사용하지 않는다. 로그 수집은 Grafana Alloy로 한다.

## 저장소 구조

```
backend/    Spring Boot 애플리케이션
frontend/   React 애플리케이션
infra/      docker-compose, Caddy, Prometheus, Grafana, Alloy, Loki 설정
docs/       설계 문서, PROGRESS.md
```

## 자주 쓰는 명령

```bash
# 개발용 인프라 (MariaDB 등)
docker compose -f infra/docker-compose.dev.yml up -d

# Backend
cd backend && ./gradlew build        # 컴파일 + 테스트
cd backend && ./gradlew test
cd backend && ./gradlew bootRun

# Frontend
cd frontend && npm run dev
cd frontend && npm run build && npm run lint
```

Testcontainers 테스트는 Docker가 실행 중이어야 한다.

## 아키텍처 원칙

- 모놀리식으로 시작한다. MSA로 나누지 않는다.
- 패키지는 기능(도메인) 단위로 나눈다: `auth`, `member`, `monitor`, `check`, `incident`, `notification`, `dashboard`, `global`.
- 검사 로직(`check`)은 회원·모니터 관리 로직과 분리한다. 검사 실행은 인터페이스 뒤에 두어, 나중에 별도 Worker 프로세스나 Kafka 컨슈머로 옮겨도 호출부가 바뀌지 않게 한다.
- 스케줄링은 모니터마다 `@Scheduled`를 만들지 않는다. 짧은 주기의 디스패처 하나가 `next_check_at`이 지난 모니터를 조회해 검사를 실행한다.
- 고객 API 검사 이력은 MariaDB에 저장한다. Prometheus에는 우리 SaaS 서버 자체의 메트릭만 넣는다. 둘을 섞지 않는다.
- Redis와 Kafka는 도입 단계 전에는 쓰지 않는다. 도입할 때는 어떤 문제를 해결하는지 먼저 설명한다.
- JPA 엔티티는 `data class`로 만들지 않는다.
- 코루틴은 검사 실행부(`check`)에만 쓴다. 코루틴 안에서 JPA 트랜잭션을 직접 다루지 않고, DB 저장은 별도 계층으로 분리한다.

## 도메인 규칙 (2026-11-09 확정)

- 검사 주기: 1, 5, 10, 30, 60분 중 선택. 최소 1분.
- Timeout: 기본 10초, 최소 1초, 최대 30초.
- 장애 판정: 연속 3회 실패 시 장애 시작. 연속 2회 성공 시 복구. 한 번의 실패로 알림을 보내지 않는다.
- 판정이 끝나기 전(연속 실패 1~2회, 장애 중 연속 성공 1회)에는 30초 뒤 재검사한다. 장애 중 검사 주기는 설정 주기와 5분 중 짧은 쪽으로 한다.
- SSRF로 차단된 검사는 `BLOCKED`로 분리하고, 연속 실패와 가동률 계산에 넣지 않으며 장애 알림을 보내지 않는다.
- 장애 시작과 복구 때 각 1번만 알린다. 장기 장애 재알림은 하지 않는다.
- 실패 기준: Connection Error, Timeout, DNS 오류, TLS 오류, 기대 상태 코드가 아닌 응답. 기대 상태 코드는 기본 2xx이고 사용자가 5개까지 지정할 수 있다.
- 응답 Body는 저장하지 않고 읽지 않은 채 버린다. 응답 시간은 요청 시작부터 응답 헤더 수신까지로 잰다.
- 사용자당 모니터 최대 개수 제한을 둔다 (기본 20개).
- 검사 원본 기록은 14일 보관한다. 대시보드용 시간별 집계는 365일 보관한다.

## 보안 규칙 (필수)

사용자가 임의의 URL을 등록하므로 SSRF 방어는 모든 검사 요청에 적용한다.

- 허용 스킴은 `http`, `https`만.
- URL 문자열 검사로 끝내지 않는다. DNS로 조회한 모든 IP를 검증하고, 검증한 IP로만 실제 연결한다 (DNS 리바인딩 방지).
- 차단 대상: loopback, 사설 대역(10/8, 172.16/12, 192.168/16), link-local(169.254/16, 클라우드 메타데이터 포함), 100.64/10, 0.0.0.0/8, 192.0.0.0/24, 198.18.0.0/15, 240.0.0.0/4, 255.255.255.255, 멀티캐스트, IPv6의 ::1, fc00::/7, fe80::/10, 64:ff9b::/96, 2002::/16, IPv4-mapped IPv6, IPv4-compatible IPv6.
- Redirect는 따라가지 않는다. 3xx 응답은 기대 상태 코드로 판정한다.
- 등록 시점과 검사 시점 모두 검증한다.
- 모든 모니터 조회·수정·삭제는 소유자 검증을 거친다. 다른 사용자의 리소스 접근은 404로 응답한다.
- 로그인은 GitHub OAuth만 지원하고 비밀번호는 저장하지 않는다. GitHub에서 받은 토큰은 사용자 확인에만 쓰고 저장하지 않는다.
- 데모 계정은 읽기 전용으로 제한한다. 개발 환경 전용 로그인은 운영 환경에서 비활성화한다.
- 사용자가 입력한 요청 헤더(인증 토큰 등)는 로그에 남기지 않는다.

## 작업 방식

- 한 번에 한 단계만 진행한다. 단계 순서는 `docs/PROGRESS.md`의 로드맵을 따른다.
- 코드를 쓰기 전에 구현 계획(바꿀 파일, 이유)을 먼저 보여주고 승인을 받는다.
- 기존 코드를 수정하기 전에는 관련 파일을 먼저 읽는다.
- 기능을 구현할 때 그 기능의 테스트도 같이 작성한다. 테스트를 마지막 단계로 미루지 않는다.
- 코드 변경 후에는 빌드와 테스트를 실행하고 결과를 확인한다. 실패하면 원인을 분석해 고친 뒤 다시 실행한다.
- 중요한 설계 결정은 이유를 설명한다. 사용자는 초보 개발자이므로 용어는 처음 나올 때 쉽게 풀어 쓴다.
- 운영에서 생길 수 있는 예외(타임아웃, DNS 실패, DB 장애, 중복 실행)를 고려한다.
- 필요 이상으로 복잡한 구조(불필요한 추상화, 미리 만드는 확장 포인트)를 만들지 않는다.
- API가 바뀌면 Swagger 어노테이션과 README를 같이 갱신한다.

## 단계 완료 시

각 단계가 끝나면 아래를 보고하고, 다음 단계로 넘어가기 전에 승인을 기다린다.

1. 구현 내용 요약
2. 변경된 파일 목록
3. 빌드·테스트 실행 결과
4. 남은 문제나 결정이 필요한 사항
5. 다음 단계 작업 계획

그리고 `docs/PROGRESS.md`를 갱신하고, 승인 후 `phase-NN: 요약` 형식으로 커밋한다.
