-- 데모 로그인에 쓰는 읽기 전용 계정. 샘플 데이터는 14단계에서 추가한다.
INSERT INTO members (provider, provider_user_id, username, display_name, role, created_at, updated_at)
VALUES ('DEMO', 'demo', 'demo', '데모 계정', 'DEMO', UTC_TIMESTAMP(3), UTC_TIMESTAMP(3));
