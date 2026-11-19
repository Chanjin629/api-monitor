package com.apimonitor.check.service

import com.apimonitor.check.dto.CheckResult
import com.apimonitor.check.repository.CheckTargetRepository
import org.springframework.stereotype.Service

/**
 * 검사 결과를 DB에 반영한다.
 * 지금은 모니터 상태의 마지막 검사 정보만 갱신한다. 원본 기록 저장과 장애·복구 판정은 12단계에서 이어 붙인다.
 */
@Service
class CheckResultProcessor(
    private val checkTargetRepository: CheckTargetRepository,
) {
    fun process(result: CheckResult) {
        // 검사 중에 모니터가 삭제되었다면 갱신할 행이 없고, 그대로 끝난다.
        checkTargetRepository.updateLastResult(result)
    }
}
