package com.apimonitor.monitor.domain

/**
 * 모니터 상태.
 * - UNKNOWN: 아직 판정 전 (등록 직후, 재개 직후)
 * - UP / DOWN: 정상 / 장애
 * - PAUSED: 사용자가 일시 정지
 * - BLOCKED: 검사 시점의 SSRF 검증에 걸림. 장애로 보지 않는다.
 */
enum class MonitorStatus {
    UNKNOWN,
    UP,
    DOWN,
    PAUSED,
    BLOCKED,
}
