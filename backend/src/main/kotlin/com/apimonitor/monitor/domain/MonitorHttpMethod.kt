package com.apimonitor.monitor.domain

/** 검사 요청 방식. 상태 확인만 하므로 본문을 보내는 메서드는 지원하지 않는다. */
enum class MonitorHttpMethod {
    GET,
    HEAD,
}
