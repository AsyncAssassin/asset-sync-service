package com.example.assetsync.unit

import com.example.assetsync.api.error.ApiExceptionHandler
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.not
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

/**
 * Standalone MockMvc (no Spring context) for the paths that need a deliberately failing controller.
 * The controller is an `inner` class on purpose: a static nested `@RestController` under the
 * application package would be picked up by component scanning in every `@SpringBootTest`.
 */
class ApiExceptionHandlerTests {

    @RestController
    inner class FailingController {
        @GetMapping("/boom")
        fun boom(): String = throw IllegalStateException("secret internal detail")
    }

    private val mockMvc = MockMvcBuilders
        .standaloneSetup(FailingController())
        .setControllerAdvice(ApiExceptionHandler())
        .build()

    @Test
    fun `unexpected exception maps to a generic internal error problem detail`() {
        mockMvc.perform(get("/boom"))
            .andExpect(status().isInternalServerError)
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
            .andExpect(jsonPath("$.type").value("https://asset-sync-service/errors/internal-error"))
            .andExpect(jsonPath("$.status").value(500))
            .andExpect(jsonPath("$.detail").value("The request could not be processed."))
            .andExpect(jsonPath("$.instance").value("/boom"))
            .andExpect(content().string(not(containsString("secret internal detail"))))
    }

    @Test
    fun `unmapped path maps to not found problem detail`() {
        mockMvc.perform(get("/missing"))
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.type").value("https://asset-sync-service/errors/not-found"))
            .andExpect(jsonPath("$.instance").value("/missing"))
    }
}
