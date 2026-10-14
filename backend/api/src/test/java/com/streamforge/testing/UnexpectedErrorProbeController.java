package com.streamforge.testing;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Test-only endpoint that throws an exception containing sensitive-looking details
 * so slice tests can assert that public error bodies are sanitized.
 * Kept outside {@code com.streamforge.api} so the production context cannot scan it.
 */
@RestController
@RequestMapping("/internal/error-probe")
public class UnexpectedErrorProbeController {

    @GetMapping
    public void boom() {
        throw new IllegalStateException("jdbc:postgresql://secret-host/db password=super-secret");
    }
}
