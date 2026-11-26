# 부하 테스트 (16-1단계)

목표 규모(모니터 1,000개, 1분 주기)를 버티는지 측정하고, 그 결과로 Redis와 Kafka의 필요 여부를 판단한다.

**부하 테스트**는 실제 사용량만큼, 또는 그보다 많은 요청을 보내 서버가 견디는지 보는 시험이다. 도구는 k6를 쓴다.

## 1. 측정 환경 (2026-11-25)

| 항목 | 값 |
| --- | --- |
| PC | Intel Core i5-10210U (4코어 8스레드), 메모리 16GB, Windows 11 |
| 앱 | `bootRun` (JVM 21, 힙 기본값), 프로필 `local,loadtest` |
| DB | MariaDB 11.8 (Docker Desktop), HikariCP 연결 풀 기본값 10개 |
| 검사 대상 | WireMock 3.13.2 컨테이너 (`127.0.0.1:18080`) |
| 부하 도구 | k6 2.4.0 컨테이너 |
| 관측 | Prometheus·Grafana (15단계 메트릭) |

같은 PC에서 앱, DB, 검사 대상, k6가 함께 돌기 때문에 절대값은 운영 서버와 다르다. 목표를 여유 있게 넘는지와 어디가 먼저 막히는지를 보는 용도다. 배포 후 같은 스크립트로 다시 잰다.

## 2. 방법

| 구성 | 내용 |
| --- | --- |
| 검사 대상 | `/ok/*` 50~300ms 무작위 지연 200, `/fail/*` 500, `/slow/*` 15초 지연(검사 Timeout 10초를 넘는다) |
| 모니터 | SQL로 회원 1명당 20개씩 넣는다. 1분 주기, Timeout 10초, 50개 중 1개는 `/fail` |
| SSRF 예외 | `loadtest`와 `local` 프로필이 함께 켜졌을 때만 `127.0.0.1`을 차단에서 뺀다 ([아키텍처 4.1](02-architecture.md)) |
| API 부하 | k6 가상 사용자가 회원 한 명씩 맡아 대시보드 요약 → 모니터 목록 → 상세·24시간 그래프·검사 기록(30%는 7일 그래프)을 1~3초 간격으로 반복한다 |
| 수집 | k6 결과(클라이언트 기준)와 Prometheus 메트릭(서버 기준), 각 시나리오 10분 |

**디스패치 지연**은 예정 시각보다 실제 실행이 얼마나 늦었는지다. **p95**는 100건 중 95번째로 느린 값이다.

| 시나리오 | 모니터 | 느린 모니터 | API 동시 사용자 |
| --- | --- | --- | --- |
| ① 기본 | 1,000 | 0% | 20 |
| ② 실패 많음 | 1,000 | 30% (Timeout) | 20 |
| ③ 한계 찾기 | 3,000 | 0% | 50 |

## 3. 결과

| 지표 | 목표 | ① 기본 | ② 실패 많음 | ③ 3,000개 |
| --- | --- | --- | --- | --- |
| 검사 처리량 (건/초) | N-P1 약 17 | **16.5** | **16.3** | 39.3 (필요 50) |
| 디스패치 지연 p95 | N-P3 5초 | 5.4초 (최대 5.55초) | **2.6초** | 17.1초 (최대 22.2초) |
| 실행 중인 검사 최대 / 평균 | N-P2 200 이하 | **86 / 2.5** | **107 / 47.9** | 193 / 10.4 |
| API p95 (k6) | N-P4 300ms | **45ms** | **23ms** | **55ms** |
| API 요청 수 / 실패율 | | 34/초, 0% | 34/초, 0% | 85/초, 0% |
| DB 연결 사용 최대 / 대기 최대 | | 7 / 0 | 10 / 0 | **10 / 10** |
| 앱 CPU 평균 | | 3% | 3% | 8% |
| 힙 사용 최대 | | 104MB | 105MB | 105MB |
| 결과 저장 실패, 주기 작업 실패 | 0 | 0 | 0 | 0 |

디스패치 지연 p95는 Prometheus 히스토그램의 구간으로 추정한 값이라 실제보다 조금 높게 나올 수 있다. 최댓값은 실제 측정값이다.

검사 원본 기록은 한 행에 약 83바이트(데이터+인덱스, MariaDB 추정 행 수 기준)였다. 목표 규모로 14일 보관하면 약 2천만 행, **약 1.7GB**다. ERD 문서의 예상(수 GB)과 맞는다.

## 4. 해석

1. **목표 규모(N-P1, N-P2, N-P4)는 여유 있게 처리한다.** 1,000개 모니터에서 처리량이 목표와 같고, API p95는 목표의 1/6 이하다. 30%가 Timeout이어도 실행 중인 검사는 한도의 절반 정도다(예상: 초당 약 5건 × 10초 = 약 50개).
2. **디스패치 지연(N-P3)은 5초 주기 구조 때문에 목표에 걸쳐 있다.** 디스패처는 앞 실행이 끝나고 5초 뒤에 다시 돈다(`fixedDelay`). 그래서 방금 예정 시각이 된 모니터는 최대 5초 + 처리 시간만큼 기다린다. ①의 최댓값 5.55초가 이 구조와 맞는다. 처리 능력이 모자라서 생긴 지연이 아니다.
3. **이 PC의 한계는 모니터 약 2,300개(초당 약 39건)이고, 먼저 막히는 곳은 DB 연결 풀이다.** ③에서 연결 10개가 모두 쓰이고 10개가 대기했다. CPU는 8%로 여유가 있다. 검사 결과 1건마다 상태 행 잠금, 원본 저장, 상태 갱신을 한 트랜잭션으로 처리하므로, 처리량은 연결 수와 트랜잭션 시간에 묶인다.

## 5. Redis·Kafka 판단

| 대상 | 판단 | 근거 |
| --- | --- | --- |
| Redis 캐시 | **필요 없음** | 동시 사용자 50명에서도 API p95가 55ms다. 대시보드 조회가 DB 병목이 아니다 |
| Redis 요청 제한 | 17단계에서 최소 범위로 적용 | 설계 문서 6장의 기준(측정된 문제가 없으면 요청 제한부터)을 따른다. 공개 서비스라 데모·로그인 API 남용을 막을 장치가 필요하다 |
| Kafka | 목표 규모에서는 필요 없음. 18단계는 학습·포트폴리오 목적으로 진행 | 1,000개를 CPU 3%로 처리한다. 3,000개에서의 병목은 DB 연결이라 Kafka로 검사를 나눠도 저장 단계는 같은 DB를 쓴다 |

목표보다 큰 규모가 필요해지면 Kafka보다 먼저 DB 연결 풀 크기 조정이나 결과 저장 묶음 처리를 검토한다.

## 6. 다시 측정하기

```bash
# 1. 인프라와 가짜 대상 서버
docker compose -f infra/docker-compose.dev.yml --profile observability up -d
docker compose -f infra/docker-compose.dev.yml --profile loadtest up -d loadtest-target

# 2. 모니터 넣기 (출력된 member_id_from, member_id_to를 기억한다)
(echo "SET @monitors=1000, @slow_percent=0;"; cat infra/k6/seed-monitors.sql) \
  | docker exec -i api-monitor-mariadb sh -c 'mariadb -u"$MARIADB_USER" -p"$MARIADB_PASSWORD" "$MARIADB_DATABASE"'

# 3. 앱 실행 (loadtest는 local과 함께 켠다)
cd backend && ./gradlew bootRun --args='--spring.profiles.active=local,loadtest'

# 4. API 부하 (JWT_SECRET은 application-local.yml의 로컬 개발용 기본값. 운영 키는 쓰지 않는다)
docker compose -f infra/docker-compose.dev.yml --profile loadtest run --rm \
  -e MEMBER_ID_FROM=<from> -e MEMBER_ID_TO=<to> -e VUS=20 -e DURATION=10m -e JWT_SECRET=<로컬 키> \
  k6 run /scripts/api-load.js

# 5. 정리
docker exec -i api-monitor-mariadb sh -c 'mariadb -u"$MARIADB_USER" -p"$MARIADB_PASSWORD" "$MARIADB_DATABASE"' < infra/k6/cleanup.sql
docker compose -f infra/docker-compose.dev.yml --profile loadtest stop loadtest-target
```

서버 지표는 Grafana의 "API Monitor 서버" 대시보드에서 같은 10분 구간을 본다.

## 7. 17단계: 요청 제한 적용 후 (2026-11-26)

Redis 요청 제한([아키텍처 6.1](02-architecture.md))을 켠 상태로 ①과 같은 조건(모니터 1,000개, 동시 사용자 20)을 5분 측정했다. 모든 API 요청이 Redis를 한 번 더 거치므로, 그 비용이 목표(N-P4)에 영향을 주는지 본다.

| 지표 | 16-1단계 ① (제한 없음) | 17단계 (요청 제한) |
| --- | --- | --- |
| API p95 (k6) | 45ms | **42.65ms** (p99 57.65ms, 최대 110ms) |
| API p95 (서버 히스토그램) | | 36.6ms |
| API 요청 수 / 실패율 | 34/초, 0% | 33.9/초, 0% |
| 검사 처리량 | 16.5건/초 | 17.3건/초 |

차이는 측정 오차 범위 안이다. 회원 1명당 분당 약 100회라 일반 API 한도(300회)에 걸린 요청은 없었다.

| 확인 | 결과 |
| --- | --- |
| 데모 로그인 15번 연속 (`infra/k6/rate-limit.js`) | 10번까지 200, 11번째부터 429 `RATE_LIMITED`, `Retry-After` 33~34초 |
| Redis 컨테이너 정지 중 API 15번, 데모 로그인 12번 | 모두 200, 응답 약 11ms. 오류 메트릭 28 증가, 경고 로그 1줄, health는 UP |
| Redis 다시 시작 후 데모 로그인 12번 | 자동으로 다시 연결되어 10번 200, 2번 429 |

Redis가 끊긴 동안에는 명령을 기다리지 않고 바로 실패시키므로(`REJECT_COMMANDS`) 응답 대기 시간(200ms)만큼 느려지지 않았다.

처음 측정은 PC가 측정 도중 약 34분 절전 상태에 들어가 버렸다(HikariCP가 시계 건너뜀을 기록). 그 결과는 버리고 다시 쟀다. 절전에서 깨어난 직후 Redis 명령 몇 건이 응답 대기 시간을 넘겼지만, 요청은 그대로 통과되었다.

```bash
# 요청 제한 확인 (앱 실행 중)
docker compose -f infra/docker-compose.dev.yml --profile loadtest run --rm k6 run /scripts/rate-limit.js
```
