// 요청 제한이 동작하는지 확인한다. (docs/07-load-test.md 7절)
// 데모 로그인을 한 IP에서 연달아 15번 보내면 10번까지는 200, 그다음은 429와 Retry-After가 와야 한다.
// 1분 칸이 바뀌는 순간에 실행하면 횟수가 다시 세어지므로, 결과가 다르면 한 번 더 실행한다.
//
// 환경 변수
//   BASE_URL  기본 http://host.docker.internal:8080
//   REQUESTS  보낼 요청 수 (기본 15)
import http from 'k6/http';
import { check } from 'k6';
import { Counter } from 'k6/metrics';

const BASE_URL = __ENV.BASE_URL || 'http://host.docker.internal:8080';
const REQUESTS = Number(__ENV.REQUESTS || 15);
const LIMIT = 10;

const allowed = new Counter('demo_login_allowed');
const rejected = new Counter('demo_login_rejected');

export const options = {
  vus: 1,
  iterations: 1,
  thresholds: {
    demo_login_allowed: [`count==${LIMIT}`],
    demo_login_rejected: [`count==${REQUESTS - LIMIT}`],
  },
};

export default function () {
  for (let i = 1; i <= REQUESTS; i++) {
    const res = http.post(`${BASE_URL}/api/v1/auth/demo`, null, {
      tags: { name: 'POST /auth/demo' },
      // 429는 기대한 응답이므로 실패 비율에 넣지 않는다.
      responseCallback: http.expectedStatuses(200, 429),
    });
    if (res.status === 200) {
      allowed.add(1);
    } else if (res.status === 429) {
      rejected.add(1);
      check(res, {
        'code is RATE_LIMITED': (r) => r.json('error.code') === 'RATE_LIMITED',
        'Retry-After is 1~60': (r) => {
          const seconds = Number(r.headers['Retry-After']);
          return seconds >= 1 && seconds <= 60;
        },
      });
    }
    console.log(`#${i} status=${res.status} retryAfter=${res.headers['Retry-After'] || '-'} ${Math.round(res.timings.duration)}ms`);
  }
}
