# 05. API 명세

> 상태: 확정 (2026-11-09). 구현 후에는 Swagger UI가 기준 문서가 된다.

## 1. 공통 규칙

| 항목 | 규칙 | 이유 |
| --- | --- | --- |
| Base URL | `/api/v1` | 호환되지 않는 변경이 생기면 `/v2`로 공존할 수 있다 |
| 형식 | JSON, 필드는 camelCase | |
| 시각 | ISO 8601 UTC, 예: `2026-11-09T12:30:00.000Z` | 시간대 혼동을 없앤다 |
| 인증 | `Authorization: Bearer {accessToken}` | |
| 목록 | 모니터는 전체 반환(최대 20개), 검사 기록과 장애는 커서 방식 | 계속 쌓이는 데이터는 페이지 번호가 깊어질수록 느려진다 |

**커서 방식**은 "이 항목 다음부터 주세요"라고 요청하는 페이지 처리 방식이다.

### 1.1 공통 응답 형식

성공

```json
{
  "success": true,
  "data": { "id": 1 }
}
```

목록

```json
{
  "success": true,
  "data": {
    "items": [],
    "nextCursor": "eyJ0IjoiMjAyNi0xMS0wOVQxMjowMDowMFoifQ"
  }
}
```

`nextCursor`가 `null`이면 마지막 페이지다.

실패

```json
{
  "success": false,
  "error": {
    "code": "VALIDATION_FAILED",
    "message": "입력값을 확인해 주세요.",
    "details": [
      { "field": "intervalMinutes", "reason": "1, 5, 10, 30, 60 중 하나여야 합니다." }
    ],
    "traceId": "4bf92f3577b34da6"
  }
}
```

`traceId`는 사용자가 문의할 때 서버 로그를 찾기 위한 값이다. 내부 예외 메시지와 스택 트레이스는 응답에 넣지 않는다.

### 1.2 에러 코드 규칙

`도메인_원인` 형식의 대문자로 쓴다. 프론트엔드는 메시지 문구가 아니라 `code`로 분기한다.

| HTTP | code | 상황 |
| --- | --- | --- |
| 400 | `VALIDATION_FAILED` | 입력값 형식 오류 |
| 400 | `INVALID_REQUEST_BODY` | JSON 형식이 잘못되어 요청 본문을 읽을 수 없음 |
| 404 | `RESOURCE_NOT_FOUND` | 존재하지 않는 주소 |
| 405 | `METHOD_NOT_ALLOWED` | 지원하지 않는 HTTP 메서드 |
| 401 | `AUTH_REQUIRED` | 토큰 없음 |
| 401 | `AUTH_TOKEN_EXPIRED` | Access Token 만료. 프론트엔드가 재발급 시도 |
| 401 | `AUTH_TOKEN_INVALID` | 위조되었거나 잘못된 토큰 |
| 401 | `AUTH_REFRESH_INVALID` | Refresh Token이 없거나 만료, 무효화됨. 재로그인 필요 |
| 403 | `DEMO_READ_ONLY` | 데모 계정의 변경 요청 |
| 404 | `MONITOR_NOT_FOUND` | 없는 모니터이거나 다른 사용자의 모니터 |
| 404 | `CHANNEL_NOT_FOUND` | 없는 채널이거나 다른 사용자의 채널 |
| 404 | `INCIDENT_NOT_FOUND` | 없는 장애이거나 다른 사용자의 장애 |
| 409 | `MONITOR_LIMIT_EXCEEDED` | 모니터 20개 초과 |
| 409 | `CHANNEL_LIMIT_EXCEEDED` | 알림 채널 3개 초과 |
| 409 | `MONITOR_ALREADY_PAUSED` | 이미 정지된 모니터 |
| 409 | `MONITOR_NOT_PAUSED` | 정지 상태가 아닌 모니터의 재개 요청 |
| 422 | `URL_BLOCKED` | SSRF 규칙에 걸린 URL |
| 422 | `WEBHOOK_URL_INVALID` | Discord Webhook 형식이 아님 |
| 422 | `HEADER_NOT_ALLOWED` | `Host` 등 지정할 수 없는 헤더 |
| 502 | `NOTIFICATION_SEND_FAILED` | 테스트 발송이 Discord에서 실패 |
| 500 | `INTERNAL_ERROR` | 처리하지 못한 서버 오류 |

왜 이렇게 했는가

- 다른 사용자의 리소스도 `404`로 응답한다. `403`이면 해당 ID가 존재한다는 사실이 드러난다.
- `URL_BLOCKED`의 메시지에는 어떤 대역에 걸렸는지 적지 않는다. 공격자가 내부망 구조를 탐색하는 데 쓰일 수 있다.

## 2. 엔드포인트 목록

| 메서드 | 경로 | 인증 | 설명 |
| --- | --- | --- | --- |
| GET | `/oauth2/authorization/github` | 불필요 | GitHub 로그인 시작 |
| GET | `/login/oauth2/code/github` | 불필요 | GitHub 콜백 |
| POST | `/api/v1/auth/refresh` | 쿠키 | Access Token 재발급 |
| POST | `/api/v1/auth/logout` | 쿠키 | 현재 기기 로그아웃 |
| POST | `/api/v1/auth/logout-all` | 필요 | 모든 기기 로그아웃 |
| POST | `/api/v1/auth/demo` | 불필요 | 데모 로그인 |
| GET | `/api/v1/members/me` | 필요 | 내 정보 |
| DELETE | `/api/v1/members/me` | 필요 | 회원 탈퇴 |
| GET | `/api/v1/monitors` | 필요 | 모니터 목록 |
| POST | `/api/v1/monitors` | 필요 | 모니터 등록 |
| GET | `/api/v1/monitors/{id}` | 필요 | 모니터 상세 |
| PATCH | `/api/v1/monitors/{id}` | 필요 | 모니터 수정 |
| DELETE | `/api/v1/monitors/{id}` | 필요 | 모니터 삭제 |
| POST | `/api/v1/monitors/{id}/pause` | 필요 | 일시 정지 |
| POST | `/api/v1/monitors/{id}/resume` | 필요 | 재개 |
| GET | `/api/v1/monitors/{id}/checks` | 필요 | 검사 기록 |
| GET | `/api/v1/monitors/{id}/stats` | 필요 | 응답 시간과 가동률 |
| GET | `/api/v1/monitors/{id}/incidents` | 필요 | 모니터의 장애 이력 |
| GET | `/api/v1/incidents` | 필요 | 전체 장애 이력 |
| GET | `/api/v1/dashboard/summary` | 필요 | 대시보드 요약 |
| GET | `/api/v1/notification-channels` | 필요 | 알림 채널 목록 |
| POST | `/api/v1/notification-channels` | 필요 | 알림 채널 등록 |
| PATCH | `/api/v1/notification-channels/{id}` | 필요 | 알림 채널 수정 |
| DELETE | `/api/v1/notification-channels/{id}` | 필요 | 알림 채널 삭제 |
| POST | `/api/v1/notification-channels/{id}/test` | 필요 | 테스트 발송 |

데모 계정은 `GET`과 로그아웃만 사용할 수 있고, 그 외에는 `403 DEMO_READ_ONLY`를 받는다.

Swagger UI는 `/swagger-ui.html`, Actuator는 외부에 공개하지 않는다.

## 3. 인증 API

### 3.1 GitHub 로그인

브라우저를 `GET /oauth2/authorization/github`로 이동시킨다. 성공하면 `Set-Cookie`로 Refresh Token을 받고 `/auth/callback`으로 돌아온다. 실패하면 `/?loginError=true`로 돌아온다.

Refresh Token 쿠키 속성

| 속성 | 값 | 이유 |
| --- | --- | --- |
| HttpOnly | 켬 | JavaScript에서 읽을 수 없다 |
| Secure | 운영에서 켬 | HTTPS로만 전송한다 |
| SameSite | Lax | 다른 사이트에서 보낸 POST에 쿠키가 실리지 않는다 |
| Path | `/api/v1/auth` | 일반 API 요청에는 실리지 않는다 |
| Max-Age | 14일 | Refresh Token 수명과 같다 |

쿠키의 생성과 삭제는 `CookieUtil`이 맡는다. 자세한 내용은 [아키텍처 문서](./02-architecture.md#22-refresh-token-쿠키)에 있다.

### 3.2 POST /api/v1/auth/refresh

요청 Body는 없고 쿠키를 사용한다. 호출할 때마다 Refresh Token이 교체되어 새 쿠키가 내려온다.

```json
{
  "success": true,
  "data": {
    "accessToken": "eyJhbGciOiJIUzI1NiJ9...",
    "expiresIn": 900
  }
}
```

실패: `401 AUTH_REFRESH_INVALID`

### 3.3 POST /api/v1/auth/logout

현재 Refresh Token을 무효화하고 쿠키를 삭제한다. 쿠키가 없어도 성공으로 응답한다.

```json
{ "success": true, "data": null }
```

`POST /api/v1/auth/logout-all`은 해당 회원의 모든 Refresh Token을 무효화한다.

### 3.4 POST /api/v1/auth/demo

데모 계정의 Access Token을 발급한다. 수명은 60분이고 Refresh Token은 없다.

```json
{
  "success": true,
  "data": {
    "accessToken": "eyJhbGciOiJIUzI1NiJ9...",
    "expiresIn": 3600
  }
}
```

### 3.5 GET /api/v1/members/me

```json
{
  "success": true,
  "data": {
    "id": 1,
    "username": "octocat",
    "displayName": "Octo Cat",
    "avatarUrl": "https://avatars.githubusercontent.com/u/583231",
    "role": "USER",
    "monitorLimit": 20
  }
}
```

## 4. 모니터 API

### 4.1 POST /api/v1/monitors

요청

```json
{
  "name": "결제 API",
  "url": "https://api.example.com/health",
  "method": "GET",
  "intervalMinutes": 1,
  "timeoutSeconds": 10,
  "expectedStatusCodes": [200, 301],
  "headers": [
    { "name": "Authorization", "value": "Bearer secret-token" }
  ]
}
```

| 필드 | 필수 | 규칙 |
| --- | --- | --- |
| name | 예 | 1~100자 |
| url | 예 | 최대 2048자, SSRF 검증 |
| method | 아니요 | `GET`(기본), `HEAD` |
| intervalMinutes | 예 | 1, 5, 10, 30, 60 |
| timeoutSeconds | 아니요 | 1~30, 기본 10 |
| expectedStatusCodes | 아니요 | 100~599, 최대 5개. 없으면 2xx |
| headers | 아니요 | 최대 10개 |

응답 `201 Created`

```json
{
  "success": true,
  "data": {
    "id": 42,
    "name": "결제 API",
    "url": "https://api.example.com/health",
    "method": "GET",
    "intervalMinutes": 1,
    "timeoutSeconds": 10,
    "expectedStatusCodes": [200, 301],
    "headers": [
      { "name": "Authorization", "value": "********" }
    ],
    "state": {
      "status": "UNKNOWN",
      "consecutiveFailures": 0,
      "lastCheckedAt": null,
      "lastResult": null,
      "lastStatusCode": null,
      "lastResponseTimeMs": null,
      "nextCheckAt": "2026-11-09T12:30:17.000Z"
    },
    "createdAt": "2026-11-09T12:30:00.000Z",
    "updatedAt": "2026-11-09T12:30:00.000Z"
  }
}
```

실패: `400 VALIDATION_FAILED`, `409 MONITOR_LIMIT_EXCEEDED`, `422 URL_BLOCKED`, `422 HEADER_NOT_ALLOWED`

```json
{
  "success": false,
  "error": {
    "code": "URL_BLOCKED",
    "message": "검사할 수 없는 URL입니다.",
    "details": [],
    "traceId": "4bf92f3577b34da6"
  }
}
```

### 4.2 GET /api/v1/monitors

각 항목은 등록 응답과 같은 형식이며, 24시간 가동률이 추가된다.

```json
{
  "success": true,
  "data": {
    "items": [
      {
        "id": 42,
        "name": "결제 API",
        "url": "https://api.example.com/health",
        "method": "GET",
        "intervalMinutes": 1,
        "timeoutSeconds": 10,
        "expectedStatusCodes": [200, 301],
        "headers": [],
        "state": {
          "status": "UP",
          "consecutiveFailures": 0,
          "lastCheckedAt": "2026-11-09T12:45:00.000Z",
          "lastResult": "SUCCESS",
          "lastStatusCode": 200,
          "lastResponseTimeMs": 124,
          "nextCheckAt": "2026-11-09T12:46:00.000Z"
        },
        "uptime24h": 99.86,
        "createdAt": "2026-11-09T12:30:00.000Z",
        "updatedAt": "2026-11-09T12:30:00.000Z"
      }
    ],
    "nextCursor": null
  }
}
```

### 4.3 GET /api/v1/monitors/{id}

목록의 항목 하나와 같은 형식이다. 실패: `404 MONITOR_NOT_FOUND`

### 4.4 PATCH /api/v1/monitors/{id}

보낸 필드만 변경한다. `headers`는 보내면 전체를 교체하고, 보내지 않으면 유지한다. 가려진 값을 다시 저장하는 사고를 막기 위해 `value`가 `********`이면 `400 VALIDATION_FAILED`로 거부한다.

```json
{
  "intervalMinutes": 5,
  "timeoutSeconds": 15
}
```

응답은 상세 조회와 같다. URL을 바꾸면 SSRF 검증을 다시 하고, 연속 카운터를 초기화한다.

### 4.5 DELETE /api/v1/monitors/{id}

`200`과 `"data": null`을 응답한다. 모니터와 상태, 집계, 장애 이력을 삭제한다.

### 4.6 POST /api/v1/monitors/{id}/pause, /resume

응답은 상세 조회와 같다. 실패: `409 MONITOR_ALREADY_PAUSED`, `409 MONITOR_NOT_PAUSED`

### 4.7 GET /api/v1/monitors/{id}/checks

쿼리: `from`, `to`, `cursor`, `limit`(기본 50, 최대 200). 기본 조회 기간은 최근 24시간이다.

```json
{
  "success": true,
  "data": {
    "items": [
      {
        "checkedAt": "2026-11-09T12:45:00.000Z",
        "result": "SUCCESS",
        "statusCode": 200,
        "responseTimeMs": 124
      },
      {
        "checkedAt": "2026-11-09T12:44:00.000Z",
        "result": "TIMEOUT",
        "statusCode": null,
        "responseTimeMs": null
      }
    ],
    "nextCursor": "eyJ0IjoiMjAyNi0xMS0wOVQxMjo0NDowMFoifQ"
  }
}
```

### 4.8 GET /api/v1/monitors/{id}/stats

쿼리: `range` = `24h`, `7d`, `30d`

```json
{
  "success": true,
  "data": {
    "range": "24h",
    "bucketMinutes": 5,
    "uptimePercent": 99.86,
    "averageResponseTimeMs": 131,
    "points": [
      {
        "time": "2026-11-09T12:40:00.000Z",
        "averageResponseTimeMs": 128,
        "minResponseTimeMs": 110,
        "maxResponseTimeMs": 160,
        "totalCount": 5,
        "successCount": 5
      }
    ]
  }
}
```

검사가 없던 구간은 빈 값을 채우지 않는다. 그래프에서 끊어진 구간으로 보여 주기 위해서다.

## 5. 장애 API

### 5.1 GET /api/v1/incidents, /api/v1/monitors/{id}/incidents

쿼리: `status` = `OPEN`, `RESOLVED`(생략 시 전체), `cursor`, `limit`(기본 20, 최대 100)

```json
{
  "success": true,
  "data": {
    "items": [
      {
        "id": 7,
        "monitor": { "id": 42, "name": "결제 API" },
        "status": "RESOLVED",
        "startedAt": "2026-11-09T11:00:00.000Z",
        "detectedAt": "2026-11-09T11:01:02.000Z",
        "resolvedAt": "2026-11-09T11:20:00.000Z",
        "durationSeconds": 1200,
        "cause": "UNEXPECTED_STATUS",
        "lastStatusCode": 503
      }
    ],
    "nextCursor": null
  }
}
```

진행 중인 장애는 `status`가 `OPEN`이고 `resolvedAt`, `durationSeconds`가 `null`이다.

## 6. 대시보드 API

### 6.1 GET /api/v1/dashboard/summary

```json
{
  "success": true,
  "data": {
    "totalMonitors": 12,
    "upCount": 10,
    "downCount": 1,
    "pausedCount": 1,
    "unknownCount": 0,
    "blockedCount": 0,
    "uptime24h": 99.72,
    "recentIncidents": [
      {
        "id": 7,
        "monitor": { "id": 42, "name": "결제 API" },
        "status": "OPEN",
        "startedAt": "2026-11-09T12:00:00.000Z",
        "cause": "TIMEOUT"
      }
    ]
  }
}
```

## 7. 알림 채널 API

### 7.1 POST /api/v1/notification-channels

```json
{
  "type": "DISCORD",
  "name": "운영 알림",
  "webhookUrl": "https://discord.com/api/webhooks/123/abcd"
}
```

응답 `201 Created`. Webhook URL은 다시 내려 주지 않고, 끝 4자리만 표시한다.

```json
{
  "success": true,
  "data": {
    "id": 3,
    "type": "DISCORD",
    "name": "운영 알림",
    "webhookUrlMasked": "••••abcd",
    "enabled": true,
    "createdAt": "2026-11-09T12:30:00.000Z"
  }
}
```

실패: `409 CHANNEL_LIMIT_EXCEEDED`, `422 WEBHOOK_URL_INVALID`

### 7.2 PATCH /api/v1/notification-channels/{id}

`name`, `webhookUrl`, `enabled` 중 보낸 필드만 변경한다.

### 7.3 POST /api/v1/notification-channels/{id}/test

테스트 메시지를 즉시 보낸다. 실패: `502 NOTIFICATION_SEND_FAILED`

```json
{ "success": true, "data": { "delivered": true } }
```

## 8. 알림 메시지

장애

```text
🔴 장애 감지: 결제 API
URL: https://api.example.com/health
원인: HTTP 503
시작: 2026-11-09 20:00 (KST)
```

복구

```text
🟢 복구: 결제 API
장애 시간: 20분
복구: 2026-11-09 20:20 (KST)
```

알림 메시지에 쿼리 문자열은 넣지 않는다. URL에 API 키를 넣어 쓰는 사용자의 키가 Discord에 노출되지 않게 하기 위해서다.
