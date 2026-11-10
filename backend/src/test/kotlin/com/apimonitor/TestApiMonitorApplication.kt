package com.apimonitor

import org.springframework.boot.fromApplication
import org.springframework.boot.with


fun main(args: Array<String>) {
	fromApplication<ApiMonitorApplication>().with(TestcontainersConfiguration::class).run(*args)
}
