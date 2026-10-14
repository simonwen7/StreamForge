package com.streamforge.testing;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Test-only HTTP surface for validation and malformed JSON.
 * This package is outside {@code com.streamforge.api}, so the production
 * application context does not component-scan it, and it is not in the API jar.
 */
@RestController
@RequestMapping("/internal/validation-probe")
public class ValidationProbeController {

    public record ProbeRequest(
            @NotBlank(message = "name must not be blank") String name,
            @Min(value = 1, message = "count must be at least 1")
            @Max(value = 100, message = "count must be at most 100") int count) {
    }

    @PostMapping
    public ResponseEntity<Void> probe(@Valid @RequestBody ProbeRequest request) {
        return ResponseEntity.noContent().build();
    }
}
