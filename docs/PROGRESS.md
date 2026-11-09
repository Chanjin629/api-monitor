# 진행 상황

현재 단계: 6 (개발용 인프라 + Backend 기본 구조)

## 로드맵

각 단계는 승인 후 다음 단계로 넘어간다. 기능 단계에는 해당 기능의 테스트 작성이 포함된다.

### 설계
- [x] 1. 요구사항 분석 → `docs/01-requirements.md`
- [x] 2. 시스템 아키텍처 설계 → `docs/02-architecture.md`
- [x] 3. ERD 설계 → `docs/03-erd.md`
- [x] 4. 디렉터리 구조 설계 → `docs/04-structure.md`
- [x] 5. API 명세 → `docs/05-api-spec.md`

### 기반
- [ ] 6. 개발용 인프라(`infra/docker-compose.dev.yml`, MariaDB) + Flyway와 첫 스키마 + Backend 기본 구조 (공통 응답, 예외 처리, 설정 분리)
- [ ] 7. Frontend 기본 구조 (라우팅, API 클라이언트, 레이아웃)
- [ ] 8. GitHub Actions CI (Backend 빌드·테스트, Frontend 빌드·린트)

### 핵심 기능
- [ ] 9. 회원 인증 (GitHub OAuth 로그인, 로그아웃, JWT, 데모 로그인)
- [ ] 10. 모니터 등록 및 관리 + URL 검증(SSRF 방어)
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
- [ ] 18. Kafka 도입 (도입 이유 설명 후)

### 마무리
- [ ] 19. 테스트 보강 (통합 테스트, 장애 시나리오)
- [ ] 20. 전체 Docker Compose 구성 (앱 + 인프라 + 관측성 + Caddy)
- [ ] 21. GitHub Actions CD (배포)

## 결정 기록

중요한 설계 결정과 이유를 날짜와 함께 남긴다.

- 2026-11-09: Backend 언어를 Kotlin(JVM 21)으로 정했다. 기존 프로젝트가 Java이므로, Spring 경험을 유지하면서 지원 가능한 공고의 폭을 넓히기 위해서다.
- 2026-11-09: Spring Boot 4.1을 사용한다. 3.x의 무료 지원이 종료되었고, 4.1이 지원 기간이 가장 길다.
- 2026-11-09: 로그인은 GitHub OAuth만 지원하고, 체험용 읽기 전용 데모 로그인을 둔다. 사용자가 개발자이고, 비밀번호 관리 부담을 없애 검사·장애 감지 기능에 집중하기 위해서다.
- 2026-11-09: Access Token은 SmartFridge처럼 `jjwt`와 직접 만든 Spring Security 필터로 구현한다. 사용해 본 방식이라 면접에서 설명할 수 있고, Gateway가 없는 모놀리식 구조에 맞기 때문이다. Refresh Token은 설계 문서대로 무작위 문자열을 해시해 MariaDB에 저장한다.
- 2026-11-09: Refresh Token 쿠키는 Synco의 `CookieUtil` 구조로 구현한다. 쿠키 처리를 한곳에 모으고 환경별 값을 설정으로 분리할 수 있기 때문이다. 쿠키 경로는 `/api/v1/auth`로 제한한다.
- 2026-11-09: 설계 결정 D1, D2, D4~D9를 추천안으로 확정했다. Access Token은 메모리 보관, 수명 15분, Refresh Token 14일, 알림은 Discord만, 재알림 없음, 원본 14일 보관, 판정 중 30초 재검사, Redirect는 따라가지 않는다. 이유는 `docs/01-requirements.md`에 있다.

## 완료 내역

단계가 끝날 때마다 요약, 주요 변경 파일, 테스트 결과를 추가한다.

### 1~5단계: 설계 (2026-11-09)

- 요약: 요구사항, 아키텍처, ERD, 디렉터리 구조, API 명세를 작성하고 결정 항목 D1~D9를 확정했다.
- 주요 변경 파일: `docs/01-requirements.md`, `docs/02-architecture.md`, `docs/03-erd.md`, `docs/04-structure.md`, `docs/05-api-spec.md`, `CLAUDE.md`
- 테스트 결과: 코드 없음. Mermaid 다이어그램 렌더링 확인.
