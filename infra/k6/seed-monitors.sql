-- 부하 테스트용 회원과 모니터를 만든다. (docs/07-load-test.md)
-- 실행 전에 @monitors(모니터 수)와 @slow_percent(느린 모니터 비율, 0~100)를 정한다. 사용법은 docs/07-load-test.md 참고.
-- 회원 한 명당 모니터 20개(서비스 제한과 같다). 회원은 provider_user_id가 'loadtest-'로 시작한다.
-- 대상은 가짜 서버(127.0.0.1:18080)이고, loadtest 프로필에서만 SSRF 차단에서 빠진다.

SET @monitors = COALESCE(@monitors, 1000);
SET @slow_percent = COALESCE(@slow_percent, 0);
SET @now = UTC_TIMESTAMP(3);

INSERT INTO members (provider, provider_user_id, username, display_name, role, created_at, updated_at)
SELECT 'GITHUB', CONCAT('loadtest-', s.seq), CONCAT('loadtest-', s.seq), '부하 테스트', 'USER', @now, @now
FROM seq_1_to_1000 s
WHERE s.seq <= CEIL(@monitors / 20);

-- 모니터 100개 중 @slow_percent개는 느린 응답(Timeout), 50개 중 1개는 서버 오류(장애 판정), 나머지는 정상 응답
INSERT INTO monitors (member_id, name, url, http_method, interval_minutes, timeout_seconds, created_at, updated_at)
SELECT m.id,
       CONCAT('부하 테스트 ', s.seq),
       CONCAT('http://127.0.0.1:18080/',
              CASE
                  WHEN s.seq MOD 100 < @slow_percent THEN 'slow'
                  WHEN s.seq MOD 50 = 0 THEN 'fail'
                  ELSE 'ok'
              END,
              '/', s.seq),
       'GET', 1, 10, @now, @now
FROM seq_1_to_20000 s
JOIN members m ON m.provider = 'GITHUB' AND m.provider_user_id = CONCAT('loadtest-', CEIL(s.seq / 20))
WHERE s.seq <= @monitors;

-- 첫 검사 시각을 1분 안에 고르게 흩는다. 실제 등록도 0~30초 사이로 흩는다.
INSERT INTO monitor_states (monitor_id, status, next_check_at)
SELECT mo.id, 'UNKNOWN', @now + INTERVAL (mo.id MOD 60) SECOND
FROM monitors mo
JOIN members m ON m.id = mo.member_id
WHERE m.provider_user_id LIKE 'loadtest-%';

-- k6에 넘길 회원 ID 범위
SELECT MIN(id) AS member_id_from, MAX(id) AS member_id_to, COUNT(*) AS members,
       (SELECT COUNT(*) FROM monitors mo JOIN members m2 ON m2.id = mo.member_id
        WHERE m2.provider_user_id LIKE 'loadtest-%') AS monitors
FROM members
WHERE provider_user_id LIKE 'loadtest-%';
