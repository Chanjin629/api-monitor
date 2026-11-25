-- 부하 테스트 데이터를 지운다.
-- check_results는 파티션 테이블이라 외래 키가 없어 먼저 지우고, 나머지는 회원을 지우면 함께 지워진다(ON DELETE CASCADE).

DELETE cr
FROM check_results cr
JOIN monitors mo ON mo.id = cr.monitor_id
JOIN members m ON m.id = mo.member_id
WHERE m.provider_user_id LIKE 'loadtest-%';

DELETE FROM members WHERE provider_user_id LIKE 'loadtest-%';

SELECT COUNT(*) AS remaining_loadtest_members FROM members WHERE provider_user_id LIKE 'loadtest-%';
