package com.streamforge.testing;

import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Test-only stand-in for a database outage. Not component-scanned by the production application.
 */
@RestController
@RequestMapping("/internal/database-probe")
public class DatabaseFailureProbeController {

    @GetMapping
    public void fail() {
        throw new CannotGetJdbcConnectionException(
                "jdbc:postgresql://database/streamforge SELECT password=super-secret");
    }
}
