package com.apimonitor.check.executor

import com.apimonitor.check.dto.CheckCommand

/**
 * 검사를 실행하는 곳. 디스패처는 이 인터페이스에만 의존한다.
 * 지금은 같은 프로세스에서 실행하고([WebClientCheckExecutor]), 18단계에서 Kafka로 Worker에 넘기는 구현체로 바꿀 수 있다.
 */
interface CheckExecutor {

    /** 지금 더 받을 수 있는 검사 수. 디스패처는 이만큼만 대상을 선점한다. */
    fun availableSlots(): Int

    /** 검사를 시작하고 바로 돌아온다. 결과는 실행이 끝난 뒤 결과 처리로 전달된다. */
    fun execute(command: CheckCommand)
}
