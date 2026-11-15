package com.apimonitor

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication

@SpringBootApplication
@ConfigurationPropertiesScan
class ApiMonitorApplication

fun main(args: Array<String>) {
	runApplication<ApiMonitorApplication>(*args)
}
