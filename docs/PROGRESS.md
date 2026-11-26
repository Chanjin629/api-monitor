# 진행 상황

현재 단계: 19 (테스트 보강)

## 로드맵

각 단계는 승인 후 다음 단계로 넘어간다. 기능 단계에는 해당 기능의 테스트 작성이 포함된다.

### 설계
- [x] 1. 요구사항 분석 → `docs/01-requirements.md`
- [x] 2. 시스템 아키텍처 설계 → `docs/02-architecture.md`
- [x] 3. ERD 설계 → `docs/03-erd.md`
- [x] 4. 디렉터리 구조 설계 → `docs/04-structure.md`
- [x] 5. API 명세 → `docs/05-api-spec.md`

### 기반
- [x] 6. 개발용 인프라(`infra/docker-compose.dev.yml`, MariaDB) + Flyway와 첫 스키마 + Backend 기본 구조 (공통 응답, 예외 처리, 설정 분리)
- [x] 7. Frontend 기본 구조 (라우팅, API 클라이언트, 레이아웃)
- [x] 8. GitHub Actions CI (Backend 빌드·테스트, Frontend 빌드·린트)

### 핵심 기능
- [x] 9. 회원 인증 (GitHub OAuth 로그인, 로그아웃, JWT, 데모 로그인)
- [x] 10. 모니터 등록 및 관리 + URL 검증(SSRF 방어)
- [x] 11. 주기적 API 검사 (디스패처, WebClient, 연결 시점 IP 검증)
- [x] 12. 검사 결과 저장 + 장애 감지 및 이력 + 파티션 관리와 원본 삭제
- [x] 13. 장애·복구 알림 (Discord Webhook)
- [x] 14. 대시보드 (요약, API별 상태, 응답 시간 그래프, 장애 이력) + 시간별 집계 + 데모 시드 데이터

### 관측성
- [x] 15. Actuator + Micrometer + Prometheus + Grafana
- [x] 16. Grafana Alloy + Loki
- [x] 16-1. 부하 테스트 (k6, Redis·Kafka 도입 판단 근거 측정)

### 확장
- [x] 17. Redis 도입 (요청 제한)

### 마무리
- [ ] 19. 테스트 보강 (통합 테스트, 장애 시나리오)
- [ ] 20. 전체 Docker Compose 구성 (앱 + 인프라 + 관측성 + Caddy)
- [ ] 21. GitHub Actions CD (배포)

### 배포 이후
- [ ] 18. Kafka 도입 (검사 작업을 Worker로 분리하는 학습·포트폴리오 목적)

## 결정 기록

중요한 설계 결정과 이유를 날짜와 함께 남긴다.

- 2026-11-09: Backend 언어를 Kotlin(JVM 21)으로 정했다. 기존 프로젝트가 Java이므로, Spring 경험을 유지하면서 지원 가능한 공고의 폭을 넓히기 위해서다.
- 2026-11-09: Spring Boot 4.1을 사용한다. 3.x의 무료 지원이 종료되었고, 4.1이 지원 기간이 가장 길다.
- 2026-11-09: 로그인은 GitHub OAuth만 지원하고, 체험용 읽기 전용 데모 로그인을 둔다. 사용자가 개발자이고, 비밀번호 관리 부담을 없애 검사·장애 감지 기능에 집중하기 위해서다.
- 2026-11-09: Access Token은 SmartFridge처럼 `jjwt`와 직접 만든 Spring Security 필터로 구현한다. 사용해 본 방식이라 면접에서 설명할 수 있고, Gateway가 없는 모놀리식 구조에 맞기 때문이다. Refresh Token은 설계 문서대로 무작위 문자열을 해시해 MariaDB에 저장한다.
- 2026-11-09: Refresh Token 쿠키는 Synco의 `CookieUtil` 구조로 구현한다. 쿠키 처리를 한곳에 모으고 환경별 값을 설정으로 분리할 수 있기 때문이다. 쿠키 경로는 `/api/v1/auth`로 제한한다.
- 2026-11-09: 설계 결정 D1, D2, D4~D9를 추천안으로 확정했다. Access Token은 메모리 보관, 수명 15분, Refresh Token 14일, 알림은 Discord만, 재알림 없음, 원본 14일 보관, 판정 중 30초 재검사, Redirect는 따라가지 않는다. 이유는 `docs/01-requirements.md`에 있다.
- 2026-11-09: Kafka(18단계)는 3주 일정에서 제외하고 배포 후에 진행한다. 목표 규모에서는 없어도 처리할 수 있으므로, 검사 작업을 Worker로 분리하는 학습·포트폴리오 목적으로 도입한다.
- 2026-11-19: 고객 API 검사에서 응답 본문을 읽지 않도록 WebClient의 `exchangeToFlux`로 상태 코드만 받고 바로 구독을 취소한다. `exchangeToMono`는 남은 본문을 끝까지 읽어, 본문이 느린 API를 `TIMEOUT`으로 잘못 판정했다.
- 2026-11-19: 검사 요청은 연결을 재사용하지 않는다. 매번 DNS 조회와 IP 검증을 하고, 응답 시간을 같은 조건으로 재기 위해서다.
- 2026-11-15: Frontend 테스트 도구로 Vitest를 추가했다. 토큰 재발급처럼 틀리면 사용자가 계속 로그아웃되는 로직을 자동으로 검증하기 위해서다.
- 2026-11-15: jjwt의 JSON 모듈은 `jjwt-jackson`(Jackson 2)을 쓴다. Spring Boot 4.1이 Jackson 2 버전도 관리하므로 앱의 Jackson 3과 함께 둘 수 있다.
- 2026-11-14: 개발용 MariaDB의 기본 포트를 3307로 정했다. 다른 프로젝트의 MariaDB 컨테이너가 3306을 쓰고 있어, 두 DB를 함께 띄울 수 있게 하기 위해서다.
- 2026-11-26: Redis는 요청 제한에만 쓴다. 16-1단계 측정에서 대시보드 API p95가 45ms라 캐시가 필요 없었다. 공개 서비스의 로그인·데모·알림 테스트 발송 남용을 막고, 서버가 늘어도 횟수를 한곳에서 세기 위해서다. Redis 장애 시에는 요청을 통과시킨다(fail-open). 요청 제한 전용 라이브러리 없이 INCR·EXPIRE Lua 스크립트로 구현했다.
- 2026-11-26: 개발용 Redis 포트를 6380으로 정했다. 다른 프로젝트의 Redis 컨테이너가 6379를 쓴다.
- 2026-11-13: Backend 패키지는 기능 단위 아래에 `controller`, `dto`, `service`, `domain`, `repository`를 두는 구조로 바꿨다. 국내 실무에서 가장 흔하고 SmartFridge에서 써 본 방식이라 설명하기 쉽기 때문이다.

## 완료 내역

단계가 끝날 때마다 요약, 주요 변경 파일, 테스트 결과를 추가한다.

### 1~5단계: 설계 (2026-11-09)

- 요약: 요구사항, 아키텍처, ERD, 디렉터리 구조, API 명세를 작성하고 결정 항목 D1~D9를 확정했다.
- 주요 변경 파일: `docs/01-requirements.md`, `docs/02-architecture.md`, `docs/03-erd.md`, `docs/04-structure.md`, `docs/05-api-spec.md`, `CLAUDE.md`
- 테스트 결과: 코드 없음. Mermaid 다이어그램 렌더링 확인.
- 추가 문서: 화면 설계서 `docs/06-screens.md`, ERD Cloud 가져오기용 `docs/erd-cloud.sql`

### 6단계: 개발용 인프라 + Backend 기본 구조 (2026-11-10 ~ 2026-11-14)

- 요약: Spring Boot 4.1.2 프로젝트와 패키지 구조를 만들고, 개발용 MariaDB, 환경별 설정, Flyway 첫 스키마(테이블 9개, 파티션, 생성 컬럼), 공통 응답·예외 처리, Swagger를 구현했다.
- 주요 변경 파일: `infra/docker-compose.dev.yml`, `.env.example`, `backend/build.gradle.kts`, `backend/src/main/resources/application*.yml`, `backend/src/main/resources/db/migration/V1__init_schema.sql`, `backend/src/main/kotlin/com/apimonitor/global/**`, `README.md`
- 테스트 결과: `./gradlew build` 성공, 테스트 16개 통과(스키마 5, 예외 처리 8, Swagger 2, 컨텍스트 1). 로컬 실행에서 Flyway V1 적용, Swagger 접속, 404 공통 응답 확인.

### 7단계: Frontend 기본 구조 (2026-11-14)

- 요약: Vite로 React·TypeScript 프로젝트를 만들고 Tailwind CSS, React Router, TanStack Query를 설정했다. 화면 8개와 404 화면의 경로, 공통 레이아웃, Access Token을 메모리에 두고 만료 시 한 번 재발급하는 API 클라이언트, Backend로 요청을 넘기는 Vite 프록시를 구현했다.
- 주요 변경 파일: `frontend/vite.config.ts`, `frontend/src/main.tsx`, `frontend/src/app/**`, `frontend/src/pages/**`, `frontend/src/shared/**`
- 테스트 결과: `npm run lint`, `npm run build` 통과. 개발 서버에서 화면 이동, 404 화면, 프록시를 거친 Backend 응답 확인. Frontend 테스트 도구는 아직 없다.

### 8단계: GitHub Actions CI (2026-11-14)

- 요약: `main`에 푸시하거나 수동으로 실행하면 Backend 빌드·테스트(Testcontainers 포함)와 Frontend 린트·빌드를 동시에 실행하는 Workflow를 추가했다. 실패하면 테스트 리포트를 7일간 보관하고, README에 CI 배지를 달았다.
- 주요 변경 파일: `.github/workflows/ci.yml`, `README.md`
- 테스트 결과: 첫 실행([#43582120289](https://github.com/Chanjin629/api-monitor/actions/runs/43582120289)) 성공. Backend 약 2분, Frontend 약 20초. 린트 경고 0건.

### 9단계: 회원 인증 (2026-11-15)

- 요약: GitHub OAuth 로그인(Spring Security OAuth2 Client), jjwt Access Token과 JwtTokenFilter, Refresh Token 해시 저장·교체·재사용 감지, CookieUtil, 로그아웃·모든 기기 로그아웃, 읽기 전용 데모 로그인, 회원 탈퇴를 구현했다. Frontend는 로그인·데모 버튼, 로그인 처리 화면, 로그인 보호, 프로필 메뉴, 데모 안내 배너를 연결하고 Vitest를 추가했다.
- 주요 변경 파일: `backend/src/main/kotlin/com/apimonitor/{auth,member,global/security}/**`, `V2__seed_demo_member.sql`, `application*.yml`, `frontend/src/{app,features/auth,shared/api,pages}/**`, `.github/workflows/ci.yml`
- 테스트 결과: Backend 40개 통과(JWT 4, 토큰 서비스 8, 인증 API 8, GitHub 로그인 4 추가). Frontend Vitest 9개 통과, 린트·빌드 통과. 개발 서버에서 로그인 보호, 데모 로그인·배너, GitHub 로그인 시작 주소(콜백 5173, state 포함) 확인. 실제 GitHub 계정으로 로그인 성공.
- 발견한 문제: Vite 프록시를 문자열로 지정하면 Host가 바뀌어 GitHub 콜백 주소가 8080으로 만들어졌다. `changeOrigin: false`로 고쳤다.

### 10단계: 모니터 등록 및 관리 + SSRF 방어 (2026-11-16)

- 요약: 모니터 API 7개(목록, 등록, 상세, 수정, 삭제, 일시 정지, 재개)와 소유자 검증(다른 회원의 모니터는 404), 회원 행 잠금으로 지키는 20개 제한, 첫 검사 시각 분산(0~30초)을 구현했다. SSRF 검증기는 스킴·사용자 정보·포트·모호한 숫자 IP를 거부하고, DNS로 조회한 모든 IP를 CLAUDE.md의 차단 대역과 대조한다. 요청 헤더는 AES-GCM으로 암호화해 저장하고 응답에서는 가린다. Frontend는 대시보드 목록, 등록·수정 폼(헤더 다시 입력 방식), 상세 화면(정지·재개·수정·삭제)을 연결했다.
- 주요 변경 파일: `backend/src/main/kotlin/com/apimonitor/{monitor,global/ssrf,global/crypto}/**`, `global/error/{BusinessException,ErrorCode,GlobalExceptionHandler}.kt`, `member/repository/MemberRepository.kt`, `application*.yml`, `.env.example`, `frontend/src/features/monitors/**`, `frontend/src/pages/{Dashboard,MonitorForm,MonitorDetail}Page.tsx`, `docs/05-api-spec.md`, `README.md`
- 테스트 결과: Backend 129개 통과(차단 대역 41, URL 검증 26, 암호화 4, 모니터 API 18 추가). Frontend Vitest 17개 통과(8개 추가), 린트·빌드 통과. GitHub 계정으로 화면에서 `127.0.0.1`·`169.254.169.254` 거부, 등록, 목록, 정지·재개, 수정(헤더 유지), 삭제를 확인했다. 서버 로그에 URL과 헤더 값이 남지 않는 것도 확인했다.
- 남은 일: 검사 원본 기록(`check_results`)은 FK가 없어 모니터를 삭제해도 남으며, 12단계의 14일 파티션 삭제로 정리된다. DNS 조회에 실패한 URL은 등록을 허용하고 11단계의 검사 시점 검증에 맡긴다.

### 11단계: 주기적 API 검사 (2026-11-19)

- 요약: 5초마다 검사 시각이 지난 모니터를 `FOR UPDATE SKIP LOCKED`로 선점하고 다음 검사 시각을 먼저 옮기는 디스패처, `CheckExecutor` 인터페이스 뒤의 WebClient·코루틴 실행기(동시 200개 제한), 연결 시점에 DNS 결과를 검증하고 검증한 IP로만 연결하는 `SafeAddressResolver`를 구현했다. 결과는 7종(SUCCESS, TIMEOUT, CONNECTION_ERROR, DNS_ERROR, TLS_ERROR, UNEXPECTED_STATUS, BLOCKED)으로 분류해 `monitor_states`의 마지막 검사 정보에 반영한다. Redirect는 따라가지 않고 응답 본문은 읽지 않는다. Frontend 상세 화면에 결과를 한글로 표시한다.
- 주요 변경 파일: `backend/build.gradle.kts`(webflux, coroutines, WireMock), `backend/src/main/kotlin/com/apimonitor/check/**`, `global/ssrf/{SafeAddressResolver,UrlSafetyValidator}.kt`, `application{,-test}.yml`, `frontend/src/features/monitors/monitorApi.ts`, `frontend/src/pages/MonitorDetailPage.tsx`, `docs/02-architecture.md`
- 테스트 결과: Backend 150개 통과(연결 시점 검증 5, WireMock 실행기 12, 디스패처 4 추가). Frontend Vitest 18개 통과, 린트·빌드 통과. 개발 서버에서 실제 사이트로 SUCCESS(api.github.com), TLS_ERROR(만료·자체 서명·도메인 불일치 인증서), DNS_ERROR(없는 도메인), BLOCKED(127.0.0.1로 연결되는 도메인)를 확인했다. 서버 두 대를 같은 DB로 띄워도 정상 동작했다.
- 발견한 문제: `exchangeToMono`가 본문을 끝까지 읽어 느린 본문을 TIMEOUT으로 판정했다(테스트로 발견, `exchangeToFlux`와 구독 취소로 수정). Reactor Netty가 인증서 오류마다 고객 도메인이 담긴 WARN 스택 트레이스를 남겨 해당 로거를 ERROR로 올렸다.
- 남은 일: 상태 전환(UNKNOWN→UP 등)과 30초 재검사, 원본 기록 저장은 12단계에서 한다. 그 전까지 상태 배지는 "확인 전"으로 남는다.

### 12단계: 검사 결과 저장 + 장애 감지 + 파티션 관리 (2026-11-20)

- 요약: 검사 결과 하나를 한 트랜잭션으로 처리한다. 상태 행을 잠그고, 원본 기록을 `INSERT IGNORE`로 저장한 뒤, `MonitorState`의 판정 규칙(실패 3회 장애, 장애 중 성공 2회 복구, 판정 중 30초 재검사, 장애 중 주기는 설정 주기와 5분 중 짧은 쪽, BLOCKED 제외)으로 상태를 바꾼다. 장애 시작·복구 때 `incidents`를 기록하고 활성 알림 채널마다 `notification_deliveries`에 예약을 저장한다. `PartitionManager`가 서버 시작 시와 매일 UTC 00:10에 3일치 날짜 파티션을 만들고 14일 지난 파티션을 지운다.
- 주요 변경 파일: `backend/src/main/kotlin/com/apimonitor/monitor/domain/MonitorState.kt`, `check/service/CheckResultProcessor.kt`, `check/repository/CheckResultRepository.kt`, `check/scheduler/PartitionManager.kt`, `incident/**`, `notification/repository/NotificationDeliveryRepository.kt`, `docs/02-architecture.md`, `docs/03-erd.md`
- 테스트 결과: Backend 168개 통과(판정 규칙 11, 결과 처리 6, 파티션 2 추가, 기존 결과 처리 테스트 1개 이동). 개발 서버에서 성공 모니터는 정상, 없는 도메인은 30초 간격 재검사 후 3번째 실패에 장애(시작 시각은 첫 실패), URL을 고치자 30초 뒤 두 번째 성공으로 복구(지속 95초)되는 것을 확인했다. 시작 시 날짜 파티션 생성도 확인했다.
- 발견한 문제: 파티션 테스트가 공유 DB의 파티션을 지워 다른 파티션 테스트의 가정이 깨졌다. 앞으로 만들어 둔 마지막 날짜를 확인하도록 테스트를 고쳤다.
- 남은 일: 알림 채널 등록 API가 없어 실제 예약은 13단계부터 생긴다. 장애 이력 조회 API와 화면, 시간별 집계와 365일 삭제는 14단계에서 한다.

### 13단계: 장애·복구 알림 (2026-11-20)

- 요약: 알림 채널 API 5개(목록, 등록, 수정, 삭제, 테스트 발송)를 만들었다. Webhook 주소는 `https://discord.com/api/webhooks/{id}/{token}` 형식만 받고 AES-GCM으로 암호화해 저장하며, 응답에는 끝 4자리만 보여 준다. 채널은 회원당 3개까지다. 5초 주기 발송기가 예약된 알림을 `SKIP LOCKED`로 가져와 Discord로 보내고, 실패하면 1분·5분·15분·1시간 간격으로 재시도한 뒤 5번째 실패면 `FAILED`로 끝낸다. 429는 Discord가 알려 준 대기 시간을 지키고, 404 등은 바로 `FAILED`다. 메시지는 한국 시간으로 쓰고 URL의 쿼리 문자열을 빼며, 멘션을 막는다. Frontend 설정 화면에 채널 관리와 계정(모든 기기 로그아웃, 사용자 이름 확인 후 탈퇴)을 만들었다.
- 주요 변경 파일: `backend/src/main/kotlin/com/apimonitor/notification/**`, `global/error/ErrorCode.kt`, `application{,-test}.yml`, `frontend/src/features/notifications/**`, `frontend/src/features/auth/authApi.ts`, `frontend/src/pages/SettingsPage.tsx`, `docs/05-api-spec.md`, `README.md`
- 테스트 결과: Backend 197개 통과(채널 API 12, 발송기 7, Discord 클라이언트 5, 메시지 5 추가). Frontend Vitest 21개 통과, 린트·빌드 통과. 실제 Discord로 테스트 메시지, 장애 알림, 복구 알림(장애 시간 95초)이 각 1회 도착하는 것을 확인했다. 서버 로그에 Webhook 주소와 URL 쿼리가 남지 않았다.
- 발견한 문제: 서버가 MVC로 동작해 `WebClient.Builder` 빈이 없어 Discord 클라이언트가 만들어지지 않았다. 검사 실행기처럼 WebClient를 직접 만들도록 고쳤다.
- 남은 일: Discord가 오래 장애이면 복구 알림이 장애 알림보다 먼저 갈 수 있다(문서에 기록, 순서 강제 안 함).

### 14단계: 대시보드 + 시간별 집계 + 데모 데이터 (2026-11-22, MVP 완성)

- 요약: 시간별 집계 작업(매시 5분과 서버 시작 시 직전·진행 중 시간을 다시 계산, 365일 지난 집계는 매일 삭제)을 만들었다. 조회 API 5개(대시보드 요약, 응답 시간 그래프 24h·7d·30d, 검사 기록, 전체·모니터별 장애 이력)를 커서 방식으로 만들고, 모니터 목록의 `uptime24h`를 채웠다. 데모 계정에 모니터 5개와 30일치 기록·장애를 서버 시작 시와 매일 현재 시각 기준으로 다시 만든다. Frontend는 대시보드 요약 카드·최근 장애·채널 미등록 안내, 상세 화면의 Recharts 응답 시간 그래프·장애 이력·최근 검사(더 보기), 장애 이력 화면(필터, 더 보기)을 만들었다.
- 주요 변경 파일: `backend/src/main/kotlin/com/apimonitor/{dashboard,incident}/**`, `check/scheduler/{HourlyStatsAggregator,PartitionManager}.kt`, `check/repository/{HourlyStatsRepository,CheckResultRepository}.kt`, `member/service/DemoDataSeeder.kt`, `global/response/Cursor.kt`, `monitor/service/MonitorService.kt`, `frontend/src/features/{charts,incidents}/**`, `frontend/src/shared/lib/format.ts`, `frontend/src/pages/{Dashboard,MonitorDetail,Incidents}Page.tsx`, `frontend/package.json`(recharts 3.11.0), `docs/05-api-spec.md`, `README.md`
- 테스트 결과: Backend 211개 통과(집계 3, 대시보드 API 6, 장애 API 3, 데모 데이터 2 추가). Frontend Vitest 28개 통과, 린트·빌드 통과. 데모 계정으로 대시보드 요약(전체 5, 정상 3, 장애 1, 가동률 99.41%), 상세 화면의 24시간·7일·30일 그래프, 장애 이력, 최근 검사 더 보기, 장애 이력 필터를 확인했다. 브라우저 콘솔 오류 없음.
- 발견한 문제: 최근 장애에 상태 코드가 없어 "HTTP 503" 대신 일반 문구가 보였다. 요약 응답에 `lastStatusCode`를 추가했다. 데모 장애를 시나리오 순서로 넣어 장애 목록의 최신순이 어긋났다. 시작 순서대로 저장하도록 고쳤다(두 수정은 테스트로 확인).
- 남은 일: Recharts를 넣어 Frontend 번들이 500KB를 넘었다는 빌드 경고가 나온다. 배포 단계에서 그래프가 있는 화면만 따로 불러오는 코드 분할을 검토한다.

### 15단계: Actuator + Micrometer + Prometheus + Grafana (2026-11-23)

- 요약: Actuator를 앱 포트와 다른 관리 포트(8081)에서만 열고 `health`, `info`, `prometheus`만 노출했다. 자동 메트릭(API 요청, JVM, DB 연결 풀)에 더해 디스패치 지연, 결과별 검사 수, 검사 시간, 실행 중인 검사 수, 결과 저장 실패, 알림 발송 결과, 주기 작업 실패 메트릭을 직접 기록했다. 모니터 ID와 URL은 태그에 넣지 않는다. API 응답 시간과 디스패치 지연은 p95 계산용 히스토그램으로 내보낸다. 개발용 Prometheus(v3.16.0)와 Grafana(13.3.1)를 `observability` 프로필로 추가하고, 데이터 소스와 "API Monitor 서버" 대시보드를 자동 등록했다.
- 주요 변경 파일: `backend/build.gradle.kts`, `application{,-test}.yml`, `global/security/SecurityConfig.kt`, `check/executor/WebClientCheckExecutor.kt`, `check/scheduler/{CheckDispatcher,PartitionManager,HourlyStatsAggregator}.kt`, `check/repository/CheckTargetRepository.kt`, `notification/scheduler/NotificationSender.kt`, `member/service/DemoDataSeeder.kt`, `infra/{prometheus,grafana}/**`, `infra/docker-compose.dev.yml`, `.env.example`, `docs/02-architecture.md`, `README.md`
- 테스트 결과: Backend 216개 통과(Actuator 3, 검사 메트릭 1, 디스패치 지연 1 추가, 발송기 테스트에 메트릭 확인 추가). 개발 서버에서 관리 포트만 열리는 것(8080은 404, 8081 `env`는 401), Prometheus 수집 대상 UP, Grafana 대시보드의 API 요청 수·p95(약 55ms), 검사 수, 디스패치 지연 p95(약 2.8초), JVM·DB 연결 그래프를 확인했다.
- 발견한 문제: 주기 작업 실패 메트릭의 `job` 태그가 Prometheus가 붙이는 수집 작업 이름 `job`과 겹쳐 `exported_job`으로 바뀌었다. 태그 이름을 `task`로 바꿨다(테스트로 확인, 이전 서버를 끄지 못해 화면 재확인은 못 함). 테스트에서는 Spring Boot가 메트릭 내보내기를 기본으로 꺼서 Prometheus 내보내기를 명시적으로 켰다.
- 남은 일: 운영용 Grafana 로그인과 Prometheus 수집 대상은 20단계(전체 Docker Compose)에서 정한다. 개발 중 관리 포트(8081)는 PC의 모든 네트워크 주소에서 열리므로 공용 네트워크에서는 방화벽을 켜 둔다.

### 16단계: Grafana Alloy + Loki (2026-11-24)

- 요약: 요청마다 `TraceIdFilter`가 traceId를 만들어 MDC에 넣고, 오류 응답(401·403 포함)과 그 요청의 모든 로그가 같은 traceId를 쓰게 했다. local 프로필에서 `backend/logs/api-monitor.log`에 JSON(`logstash` 형식) 로그를 쓰고, 개발용 Alloy(v1.22.0)가 읽어 Loki(3.7.10)로 보낸다. 라벨은 `app`, `level`만 두고 `traceId`, `logger`는 구조화 메타데이터로 넣었다. 보관은 7일이다. Grafana에 Loki 데이터 소스를 자동 등록하고, 대시보드에 "최근 오류·경고 로그"와 "traceId로 찾기" 패널을 추가했다.
- 주요 변경 파일: `global/error/{TraceIdFilter,TraceIds,GlobalExceptionHandler}.kt`, `global/security/ErrorResponseWriter.kt`, `application{,-local}.yml`, `infra/loki/loki-config.yml`, `infra/alloy/config.alloy`, `infra/grafana/provisioning/datasources/loki.yml`, `infra/grafana/dashboards/api-monitor.json`, `infra/docker-compose.dev.yml`, `docs/02-architecture.md`, `README.md`
- 테스트 결과: Backend 220개 통과(traceId 4 추가). Loki 설정 검사(`-verify-config`)와 Alloy 문법 검사(`fmt`) 통과. 개발 서버에서 404 응답의 traceId로 Loki를 검색해 그 요청의 로그 1줄만 나오는 것, 라벨이 `app`·`level`(Loki가 붙이는 `service_name` 포함)뿐인 것, 대시보드의 두 로그 패널을 확인했다. 로그 파일에 고객 URL, 헤더, Webhook 주소가 없었다.
- 발견한 문제: Alloy가 파일 이름 라벨을 자동으로 붙여 설정에서 뺐다. 익명 보기 권한으로는 Grafana Explore 화면을 쓸 수 없어, 권한을 넓히지 않고 대시보드에 traceId 입력칸과 검색 패널을 넣었다.
- 남은 일: 운영에서는 앱 컨테이너 로그를 수집하고 보관 기간을 정한다(20단계). Alloy 읽기 위치는 컨테이너 안에만 저장되므로, 컨테이너를 새로 만들면 로그 파일을 처음부터 다시 읽을 수 있다(개발용이라 그대로 둔다).

### 16-1단계: 부하 테스트 (2026-11-25)

- 요약: k6(2.4.0)로 대시보드 API에 부하를 주면서, 검사 대상인 가짜 서버(WireMock 3.13.2)를 향해 모니터 1,000~3,000개를 실제로 검사했다. 가짜 서버(`127.0.0.1`)를 검사할 수 있게 `loadtest`와 `local` 프로필이 함께 켜졌을 때만 이 IP 하나를 SSRF 차단에서 빼는 설정을 추가하고, 설계 문서에 먼저 적었다. 부하 테스트용 모니터는 SQL로 넣고 지운다. 결과와 판단은 `docs/07-load-test.md`에 정리했다.
- 주요 변경 파일: `global/ssrf/LoadTestAddressConfig.kt`, `application-loadtest.yml`, `infra/k6/{api-load.js,seed-monitors.sql,cleanup.sql,wiremock/mappings/targets.json}`, `infra/docker-compose.dev.yml`, `docs/{02-architecture,03-erd,04-structure,07-load-test}.md`, `README.md`
- 테스트 결과: Backend 223개 통과(부하 테스트 예외 3 추가). 측정: 모니터 1,000개에서 초당 16.5건 처리, API p95 45ms, 30%가 Timeout이어도 실행 중인 검사 최대 107개. 3,000개에서는 초당 39건에서 멈추고 디스패치 지연 p95가 17초로 늘었다. DB 연결 풀(10개)이 모두 차서 대기가 생긴 것이 원인이고 CPU는 8%였다. 검사 기록은 한 행 약 83바이트로, 14일치 약 1.7GB다.
- 판단: 대시보드 API가 목표보다 훨씬 빨라 Redis 캐시는 필요 없다. 17단계에서는 요청 제한부터 최소 범위로 적용한다. Kafka는 목표 규모에서 필요 없고 18단계에서 학습 목적으로 진행한다.
- 남은 일: 디스패치 지연 p95가 5초 주기 구조 때문에 목표(5초)에 걸쳐 있다(약 5.4초 추정, 최대 5.55초). 디스패처 주기를 2초 등으로 줄일지 결정이 필요하다. 이전 서버 프로세스를 끄지 못해 주기를 바꾼 재측정은 하지 않았다.

### 17단계: Redis 요청 제한 (2026-11-26)

- 요약: Redis 8.12로 IP·회원별 분당 요청 한도를 두었다. 데모 로그인·GitHub 로그인 시작은 IP당 10회, 토큰 재발급 30회, 알림 테스트 발송은 회원당 5회, 그 밖의 API는 회원당(데모·비로그인은 IP당) 300회다. 넘으면 `429 RATE_LIMITED`와 `Retry-After`로 응답한다. 1분 고정 창을 Lua 스크립트(INCR+EXPIRE)로 세고, Redis가 죽으면 요청을 통과시킨다. 연결이 끊긴 동안은 명령을 바로 실패시켜 API가 느려지지 않게 했다. `X-Forwarded-For`는 믿지 않는다.
- 주요 변경 파일: `global/ratelimit/{RateLimitRule,RedisRateLimiter,RateLimitFilter}.kt`, `SecurityConfig.kt`, `ErrorCode.kt`, `OpenApiConfig.kt`, `application{,-local,-test}.yml`, `build.gradle.kts`, `TestcontainersConfiguration.kt`, `infra/docker-compose.dev.yml`, `infra/grafana/dashboards/api-monitor.json`, `infra/k6/rate-limit.js`, `docs/{01-requirements,02-architecture,04-structure,05-api-spec,07-load-test}.md`, `README.md`, `.env.example`
- 테스트 결과: Backend 234개 통과(요청 제한 11 추가: Redis 횟수 세기 5, API 6). 측정: 요청 제한을 켠 상태에서 API p95 42.65ms(이전 45ms), 실패 0%. k6로 데모 로그인 11번째부터 429 확인. Redis를 멈춘 동안 API는 약 11ms로 계속 응답했고, 다시 켜자 자동으로 제한이 돌아왔다.
- 발견한 문제: 요청 제한 API 테스트가 1분 칸이 바뀌는 순간에 걸리면 횟수가 다시 세어져 가끔 실패했다. 테스트 시각을 분의 30초 지점에 고정했다. 첫 부하 측정은 PC 절전으로 무효가 되어 다시 쟀다.
- 남은 일: 운영에서 Caddy가 넘겨 주는 실제 IP를 읽는 설정, Redis 비밀번호는 20단계에서 한다. Redis가 끊긴 동안 Lettuce가 재연결 실패 경고를 계속 남긴다(재연결 간격이 최대 30초까지 늘어나 로그가 크게 쌓이지는 않는다).
