package com.apimonitor

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
class ApiMonitorApplication

fun main(args: Array<String>) {
	runApplication<ApiMonitorApplication>(*args)
}
