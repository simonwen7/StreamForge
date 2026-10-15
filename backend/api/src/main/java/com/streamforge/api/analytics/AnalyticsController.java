package com.streamforge.api.analytics;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Set;

@RestController
@RequestMapping("/api/v1/analytics/devices/{deviceId:.+}")
class AnalyticsController {

    private final AnalyticsService analyticsService;

    AnalyticsController(AnalyticsService analyticsService) {
        this.analyticsService = analyticsService;
    }

    @GetMapping("/summary")
    AnalyticsResponses.Summary summary(
            @PathVariable String deviceId,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            HttpServletRequest request) {
        AnalyticsRequests.rejectUnknown(request, Set.of("from", "to"));
        AnalyticsRequests.requireDeviceId(deviceId);
        AnalyticsRequests.Interval interval = AnalyticsRequests.interval(from, to, AnalyticsRequests.SUMMARY_MAX);
        return analyticsService.summary(deviceId, interval.from(), interval.to());
    }

    @GetMapping("/series")
    AnalyticsResponses.Series series(
            @PathVariable String deviceId,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(required = false) String granularity,
            HttpServletRequest request) {
        AnalyticsRequests.rejectUnknown(request, Set.of("from", "to", "granularity"));
        AnalyticsRequests.requireDeviceId(deviceId);
        AnalyticsRequests.Granularity unit = AnalyticsRequests.granularity(granularity);
        AnalyticsRequests.Interval interval = AnalyticsRequests.interval(from, to, unit.maximum());
        return analyticsService.series(deviceId, interval.from(), interval.to(), unit);
    }
}
