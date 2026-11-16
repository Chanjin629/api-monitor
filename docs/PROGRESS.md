# 진행 상황

현재 단계: 11 (주기적 API 검사)

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
- [ ] 11. 주기적 API 검사 (디스패처, WebClient, 연결 시점 IP 검증)
- [ ] 12. 검사 결과 저장 + 장애 감지 및 이력 + 파티션 관리와 원본 삭제
- [ ] 13. 장애·복구 알림 (Discord Webhook)
- [ ] 14. 대시보드 (요약, API별 상태, 응답 시간 그래프, 장애 이력) + 시간별 집계 + 데모 시드 데이터

### 관측성
- [ ] 15. Actuator + Micrometer + Prometheus + Grafana
- [ ] 16. Grafana Alloy + Loki
- [ ] 16-1. 부하 테스트 (k6, Redis·Kafka 도입 판단 근거 측정)

### 확장
- [ ] 17. Redis 도입 (도입 이유 설명 후)

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
- 2026-11-15: Frontend 테스트 도구로 Vitest를 추가했다. 토큰 재발급처럼 틀리면 사용자가 계속 로그아웃되는 로직을 자동으로 검증하기 위해서다.
- 2026-11-15: jjwt의 JSON 모듈은 `jjwt-jackson`(Jackson 2)을 쓴다. Spring Boot 4.1이 Jackson 2 버전도 관리하므로 앱의 Jackson 3과 함께 둘 수 있다.
- 2026-11-14: 개발용 MariaDB의 기본 포트를 3307로 정했다. 다른 프로젝트의 MariaDB 컨테이너가 3306을 쓰고 있어, 두 DB를 함께 띄울 수 있게 하기 위해서다.
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
