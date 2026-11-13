# 04. 디렉터리 구조

> 상태: 확정 (2026-11-09). 구현하면서 파일은 늘어날 수 있지만, 패키지 경계는 이 문서를 따른다.

## 1. 저장소 전체

```text
api-monitor/
├── .github/workflows/      CI, CD 정의
├── backend/                Spring Boot 애플리케이션
├── frontend/               React 애플리케이션
├── infra/                  실행 환경 설정
│   ├── docker-compose.dev.yml   개발용 MariaDB
│   ├── docker-compose.yml       운영용 전체 구성
│   ├── caddy/                   HTTPS, 정적 파일, API 전달
│   ├── prometheus/              메트릭 수집 대상
│   ├── grafana/                 데이터 소스, 대시보드
│   ├── alloy/                   로그 수집 설정
│   └── loki/                    로그 저장 설정
├── docs/                   설계 문서, PROGRESS.md
├── .env.example            필요한 환경 변수 목록. 실제 값은 넣지 않음
├── .gitignore
├── CLAUDE.md
└── README.md
```

왜 이렇게 했는가: 프론트엔드와 백엔드를 한 저장소에 두면 API 변경과 화면 변경을 하나의 커밋으로 묶을 수 있고, 포트폴리오를 보는 사람이 전체를 한곳에서 볼 수 있다.

## 2. Backend

```text
backend/src/main/kotlin/com/apimonitor/
├── ApiMonitorApplication.kt
├── auth/
│   ├── controller/         AuthController
│   ├── dto/                요청·응답 DTO
│   ├── service/            토큰 발급·재발급·로그아웃, Refresh Token 쿠키(CookieUtil)
│   ├── oauth/              GitHub 로그인 성공 처리, 회원 연결
│   ├── domain/             RefreshToken 엔티티
│   └── repository/         RefreshTokenRepository
├── member/
│   ├── controller/         내 정보 조회, 탈퇴
│   ├── dto/
│   ├── service/            회원 생성·조회
│   ├── domain/             Member 엔티티, Role enum
│   └── repository/
├── monitor/
│   ├── controller/         MonitorController
│   ├── dto/
│   ├── service/            등록·수정·삭제·정지·재개, 소유자 검증
│   ├── domain/             Monitor, MonitorState 엔티티, MonitorStatus enum, 상태 전이 규칙
│   └── repository/
├── check/
│   ├── scheduler/          CheckDispatcher(검사 대상 선점), 파티션 관리, 시간별 집계 작업
│   ├── executor/           CheckExecutor 인터페이스, WebClient 구현체
│   ├── service/            CheckResultProcessor, 다음 검사 시각 계산
│   ├── dto/                CheckCommand, CheckResult
│   └── repository/         check_results, monitor_hourly_stats 저장 (JdbcTemplate)
├── incident/
│   ├── controller/         장애 이력 조회
│   ├── dto/
│   ├── service/            장애 판정, 시작·복구 처리
│   ├── domain/             Incident 엔티티, 복구 처리 규칙
│   └── repository/
├── notification/
│   ├── controller/         알림 채널 관리, 테스트 발송
│   ├── dto/
│   ├── service/            알림 예약, 재시도 계산
│   ├── scheduler/          발송기 (5초 주기)
│   ├── discord/            Discord 메시지 변환과 전송
│   ├── domain/             NotificationChannel, NotificationDelivery
│   └── repository/
├── dashboard/
│   ├── controller/         요약, 통계 API
│   ├── dto/
│   └── service/            조회 전용 쿼리
└── global/
    ├── config/             Jackson, WebClient, 스케줄러 설정
    ├── security/           SecurityConfig, JwtTokenProvider, JwtTokenFilter, 401·403 핸들러
    ├── ssrf/               UrlSafetyValidator, SafeAddressResolver, IP 대역 판별
    ├── crypto/             AES-GCM 암호화
    ├── error/              ErrorCode, 예외, 전역 예외 처리
    └── response/           공통 응답 형식
```

| 패키지 | 맡는 역할 |
| --- | --- |
| `auth` | GitHub 로그인 이후의 토큰 수명 주기 |
| `member` | 회원 정보 |
| `monitor` | 사용자의 모니터 설정과 소유권 |
| `check` | 언제, 어떻게 검사하고 결과를 어떻게 저장하는가 |
| `incident` | 연속 실패와 성공을 장애와 복구로 판정하고 이력을 관리 |
| `notification` | 알림 채널과 안정적인 발송 |
| `dashboard` | 화면에 필요한 읽기 전용 조회 |
| `global` | 도메인에 속하지 않는 공통 기능 |

| 하위 폴더 | 맡는 역할 |
| --- | --- |
| `controller` | HTTP 요청을 받고 응답을 돌려줌 |
| `dto` | 요청·응답과 계층 사이에 주고받는 데이터 |
| `service` | 업무 흐름과 트랜잭션 |
| `domain` | 엔티티, 상태 enum, 엔티티가 지키는 규칙 |
| `repository` | DB 조회와 저장 |
| `scheduler` | 주기적으로 실행되는 작업 |
| 그 외 (`oauth`, `executor`, `discord`) | 외부 서비스 연결이나 검사 실행처럼 성격이 다른 코드 |

왜 이렇게 했는가

- **기능 단위로 나누고, 그 안을 계층으로 나눈다.** 국내 Spring 프로젝트에서 가장 흔한 방식이고 SmartFridge에서 써 본 구조라 설명하기 쉽다. 수정할 때 한 기능의 파일이 한곳에 모여 있어 찾기 쉽다.
- **필요한 폴더만 둔다.** Controller가 없는 `check`는 `scheduler`와 `executor`를 두고, 조회만 하는 `dashboard`에는 `domain`과 `repository`를 두지 않는다.
- **SSRF 코드는 `global/ssrf`에 둔다.** `monitor`의 등록 검증과 `check`의 연결 시점 검증이 같은 규칙을 공유해야 한다. 한쪽만 고쳐지는 사고를 막는다.
- **의존 방향을 지킨다.** `check`는 `monitor`의 저장소를 직접 호출하지 않고 `CheckCommand`만 받는다. 디스패처의 조회도 필요한 컬럼만 값 객체로 읽는다.

테스트는 같은 패키지 구조로 `src/test/kotlin`에 둔다. Flyway 마이그레이션은 `src/main/resources/db/migration`에 둔다.

## 3. Frontend

```text
frontend/src/
├── main.tsx
├── app/
│   ├── router.tsx          라우트 정의, 로그인 필요 경로 보호
│   ├── providers.tsx       TanStack Query 등 전역 설정
│   └── layout/             공통 레이아웃, 내비게이션
├── pages/
│   ├── LandingPage.tsx     서비스 소개, 로그인·데모 버튼
│   ├── AuthCallbackPage.tsx  로그인 후 토큰 받기
│   ├── DashboardPage.tsx   전체 요약
│   ├── MonitorDetailPage.tsx  그래프, 최근 검사, 장애 이력
│   ├── MonitorFormPage.tsx    등록, 수정
│   ├── IncidentsPage.tsx   전체 장애 이력
│   └── SettingsPage.tsx    알림 채널, 계정 관리
├── features/
│   ├── auth/               로그인 상태, 토큰 재발급 API
│   ├── monitors/           모니터 API, 목록, 폼 컴포넌트
│   ├── charts/             Recharts 응답 시간 그래프
│   ├── incidents/          장애 API, 표
│   └── notifications/      알림 채널 API, 폼
└── shared/
    ├── api/                fetch 클라이언트, 401 시 자동 재발급
    ├── ui/                 버튼, 입력창, 상태 뱃지 등 공통 컴포넌트
    └── lib/                날짜, 숫자 포맷
```

| 폴더 | 맡는 역할 |
| --- | --- |
| `app` | 앱 전체에 한 번만 설정하는 코드 |
| `pages` | URL 하나에 대응하는 화면 조립 |
| `features` | 기능별 API 호출과 컴포넌트 |
| `shared` | 특정 기능에 속하지 않는 공통 코드 |

왜 이렇게 했는가

- **Access Token은 메모리에 둔다.** `shared/api`가 보관하며 페이지를 새로고침하면 `POST /api/v1/auth/refresh`로 다시 받는다.
- **서버 데이터는 TanStack Query로 관리한다.** 주기적으로 다시 가져오기와 캐시를 직접 구현하지 않아도 된다. 대시보드는 30초마다 갱신한다.
- 개발 환경에서는 Vite 프록시로 `/api`를 백엔드에 전달해 운영과 같은 도메인 구조를 유지한다.
