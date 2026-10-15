package com.streamforge.api.analytics;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Set;

@RestController
class ReadingQueryController {

    private final AnalyticsService analyticsService;

    ReadingQueryController(AnalyticsService analyticsService) {
        this.analyticsService = analyticsService;
    }

    @GetMapping("/api/v1/devices/{deviceId:.+}/readings")
    AnalyticsResponses.ReadingPage readings(
            @PathVariable String deviceId,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(required = false) String limit,
            @RequestParam(required = false) String cursor,
            HttpServletRequest request) {
        AnalyticsRequests.rejectUnknown(request, Set.of("from", "to", "limit", "cursor"));
        AnalyticsRequests.requireDeviceId(deviceId);
        AnalyticsRequests.Interval interval = AnalyticsRequests.interval(from, to, AnalyticsRequests.READINGS_MAX);
        return analyticsService.readings(
                deviceId,
                interval.from(),
                interval.to(),
                ReadingCursor.decode(cursor),
                AnalyticsRequests.limit(limit));
    }
}
