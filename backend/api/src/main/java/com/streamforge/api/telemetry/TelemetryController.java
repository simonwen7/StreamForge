package com.streamforge.api.telemetry;

import com.streamforge.api.contract.ReadingRequest;
import com.streamforge.api.contract.ReadingValidator;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/telemetry/readings")
public class TelemetryController {

    private final ReadingValidator readingValidator;
    private final IngestionService ingestionService;

    public TelemetryController(ReadingValidator readingValidator, IngestionService ingestionService) {
        this.readingValidator = readingValidator;
        this.ingestionService = ingestionService;
    }

    @PostMapping
    public ResponseEntity<AcceptanceResponse> create(@Valid @RequestBody ReadingRequest request) {
        IngestionResult result = ingestionService.accept(readingValidator.validate(request));
        return ResponseEntity.status(result.httpStatus()).body(result.response());
    }

    @GetMapping("/{eventId}")
    public AcceptanceResponse get(@PathVariable UUID eventId) {
        return ingestionService.get(eventId);
    }
}
