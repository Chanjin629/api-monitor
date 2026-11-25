// 대시보드를 보는 사용자를 흉내 내 API에 부하를 준다. (docs/07-load-test.md)
// 검사 부하는 서버가 스스로 만들고, 이 스크립트는 그동안 API 응답 시간(N-P4)을 잰다.
//
// 환경 변수
//   MEMBER_ID_FROM, MEMBER_ID_TO  seed-monitors.sql이 출력한 부하 테스트 회원 ID 범위
//   VUS       동시 사용자 수 (기본 20)
//   DURATION  측정 시간 (기본 10m)
//   BASE_URL  기본 http://host.docker.internal:8080
//   JWT_SECRET  로컬 개발용 JWT 키 (application-local.yml의 기본값). 운영 키는 절대 넣지 않는다.
import http from 'k6/http';
import { check, sleep } from 'k6';
import crypto from 'k6/crypto';
import encoding from 'k6/encoding';

const BASE_URL = __ENV.BASE_URL || 'http://host.docker.internal:8080';
const MEMBER_FROM = Number(__ENV.MEMBER_ID_FROM);
const MEMBER_TO = Number(__ENV.MEMBER_ID_TO);
const JWT_SECRET = __ENV.JWT_SECRET;

export const options = {
  scenarios: {
    dashboard_users: {
      executor: 'constant-vus',
      vus: Number(__ENV.VUS || 20),
      duration: __ENV.DURATION || '10m',
    },
  },
  thresholds: {
    // N-P4: 일반 API 응답 시간 p95 300ms 이내
    http_req_duration: ['p(95)<300'],
    http_req_failed: ['rate<0.01'],
  },
  summaryTrendStats: ['avg', 'med', 'p(95)', 'p(99)', 'max'],
};

export function setup() {
  if (!MEMBER_FROM || !MEMBER_TO || !JWT_SECRET) {
    throw new Error('MEMBER_ID_FROM, MEMBER_ID_TO, JWT_SECRET를 지정해야 합니다.');
  }
}

// 서버의 JwtTokenProvider와 같은 형식(HS256, sub=회원 ID, role=USER, iss=api-monitor)으로 토큰을 만든다.
function accessToken(memberId) {
  const now = Math.floor(Date.now() / 1000);
  const header = encoding.b64encode(JSON.stringify({ alg: 'HS256', typ: 'JWT' }), 'rawurl');
  const payload = encoding.b64encode(
    JSON.stringify({ sub: String(memberId), role: 'USER', iss: 'api-monitor', iat: now, exp: now + 3600 }),
    'rawurl',
  );
  const key = encoding.b64decode(JWT_SECRET, 'std');
  const signature = crypto.hmac('sha256', key, `${header}.${payload}`, 'base64rawurl');
  return `${header}.${payload}.${signature}`;
}

let params;

function get(path, name) {
  const res = http.get(`${BASE_URL}${path}`, Object.assign({ tags: { name } }, params));
  check(res, { [`${name} 200`]: (r) => r.status === 200 });
  return res;
}

export default function () {
  // 가상 사용자마다 한 회원으로 로그인한 것처럼 동작한다.
  if (!params) {
    const memberId = MEMBER_FROM + ((__VU - 1) % (MEMBER_TO - MEMBER_FROM + 1));
    params = { headers: { Authorization: `Bearer ${accessToken(memberId)}` } };
  }

  get('/api/v1/dashboard/summary', 'dashboard_summary');
  const list = get('/api/v1/monitors', 'monitor_list');
  const items = list.status === 200 ? list.json('data.items') : [];
  sleep(1);

  if (items.length > 0) {
    const id = items[Math.floor(Math.random() * items.length)].id;
    get(`/api/v1/monitors/${id}`, 'monitor_detail');
    get(`/api/v1/monitors/${id}/stats?range=24h`, 'stats_24h');
    get(`/api/v1/monitors/${id}/checks?limit=50`, 'checks');
    // 상세 화면에서 기간을 바꿔 보는 사용자
    if (Math.random() < 0.3) get(`/api/v1/monitors/${id}/stats?range=7d`, 'stats_7d');
  }
  sleep(1 + Math.random() * 2);
}
