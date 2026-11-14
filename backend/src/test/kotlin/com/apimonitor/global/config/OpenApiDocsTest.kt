package com.apimonitor.global.config

import com.apimonitor.TestcontainersConfiguration
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get

@Import(TestcontainersConfiguration::class)
@SpringBootTest
@AutoConfigureMockMvc
class OpenApiDocsTest(
    @Autowired private val mockMvc: MockMvc,
) {

    @Test
    fun `API 문서가 생성된다`() {
        mockMvc.get("/v3/api-docs").andExpect {
            status { isOk() }
            jsonPath("$.info.title") { value("API Monitor") }
        }
    }

    @Test
    fun `Swagger UI 화면에 접속할 수 있다`() {
        mockMvc.get("/swagger-ui/index.html").andExpect {
            status { isOk() }
        }
    }
}
