package com.streamforge.api.error;

import com.streamforge.testing.DatabaseFailureProbeController;
import com.streamforge.testing.UnexpectedErrorProbeController;
import com.streamforge.testing.ValidationProbeController;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(
        controllers = {
                ValidationProbeController.class,
                UnexpectedErrorProbeController.class,
                DatabaseFailureProbeController.class
        },
        excludeAutoConfiguration = UserDetailsServiceAutoConfiguration.class,
        properties = "spring.datasource.password=testcontainers-managed")
@AutoConfigureMockMvc(addFilters = false)
@Import({
        GlobalExceptionHandler.class,
        ValidationProbeController.class,
        UnexpectedErrorProbeController.class,
        DatabaseFailureProbeController.class
})
class ErrorHandlingTests {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void validProbeRequestSucceeds() throws Exception {
        mockMvc.perform(post("/internal/validation-probe")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"sensor\",\"count\":3}"))
                .andExpect(status().isNoContent());
    }

    @Test
    void invalidProbeRequestReturnsProblemDetail() throws Exception {
        mockMvc.perform(post("/internal/validation-probe")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"\",\"count\":0}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.title").value("Validation Failed"))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.detail").value("Request validation failed."))
                .andExpect(jsonPath("$.type").value("about:blank"))
                .andExpect(jsonPath("$.errors.length()").value(2))
                .andExpect(jsonPath("$.errors[?(@.field=='name')].message", hasItem("name must not be blank")))
                .andExpect(jsonPath("$.errors[?(@.field=='count')].message", hasItem("count must be at least 1")))
                .andExpect(content().string(not(containsString("Exception"))))
                .andExpect(content().string(not(containsString("stackTrace"))));
    }

    @Test
    void malformedJsonReturnsSafeProblemDetail() throws Exception {
        mockMvc.perform(post("/internal/validation-probe")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{not-json"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.title").value("Malformed Request"))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.detail").value("Request body is malformed or unreadable."))
                .andExpect(jsonPath("$.type").value("about:blank"))
                .andExpect(content().string(not(containsString("JsonParseException"))))
                .andExpect(content().string(not(containsString("stackTrace"))));
    }

    @Test
    void unexpectedErrorHidesInternalDetails() throws Exception {
        mockMvc.perform(get("/internal/error-probe"))
                .andExpect(status().isInternalServerError())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.title").value("Internal Server Error"))
                .andExpect(jsonPath("$.status").value(500))
                .andExpect(jsonPath("$.detail").value("An unexpected error occurred."))
                .andExpect(jsonPath("$.type").value("about:blank"))
                .andExpect(content().string(not(containsString("super-secret"))))
                .andExpect(content().string(not(containsString("jdbc:postgresql"))))
                .andExpect(content().string(not(containsString("IllegalStateException"))));
    }

    @Test
    void databaseFailureDoesNotClaimAcceptanceOrExposeSql() throws Exception {
        mockMvc.perform(get("/internal/database-probe"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.title").value("Service Unavailable"))
                .andExpect(jsonPath("$.status").value(503))
                .andExpect(jsonPath("$.detail").value("The service is temporarily unavailable."))
                .andExpect(jsonPath("$.acceptance").doesNotExist())
                .andExpect(content().string(not(containsString("super-secret"))))
                .andExpect(content().string(not(containsString("jdbc:postgresql"))))
                .andExpect(content().string(not(containsString("SELECT"))))
                .andExpect(content().string(not(containsString("ACCEPTED"))));
    }
}
