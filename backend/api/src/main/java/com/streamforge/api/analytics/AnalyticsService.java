package com.streamforge.api.analytics;

import com.streamforge.api.error.NotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Service
class AnalyticsService {

    private final AnalyticsRepository repository;
    private final Clock clock;

    AnalyticsService(AnalyticsRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public AnalyticsResponses.Summary summary(String deviceId, Instant from, Instant to) {
        requireDevice(deviceId);
        AnalyticsRepository.Aggregate aggregate = repository.aggregate(deviceId, from, to);
        return new AnalyticsResponses.Summary(
                deviceId,
                from,
                to,
                aggregate.readingCount(),
                aggregate.readingCount() == 0 ? null : aggregate.temperatureMin(),
                aggregate.readingCount() == 0 ? null : aggregate.temperatureMax(),
                aggregate.temperatureAvg(),
                aggregate.humidityAvg(),
                aggregate.batteryAvg(),
                repository.latestInWindow(deviceId, from, to).orElse(null),
                repository.history(deviceId),
                repository.publicationBacklog(deviceId),
                repository.processingBacklog(deviceId),
                to.isAfter(clock.instant()));
    }

    @Transactional(readOnly = true)
    public AnalyticsResponses.Series series(
            String deviceId,
            Instant from,
            Instant to,
            AnalyticsRequests.Granularity granularity) {
        requireDevice(deviceId);
        return new AnalyticsResponses.Series(
                deviceId,
                from,
                to,
                granularity == AnalyticsRequests.Granularity.HOUR ? "hour" : "day",
                to.isAfter(clock.instant()),
                repository.series(deviceId, from, to, granularity));
    }

    @Transactional(readOnly = true)
    public AnalyticsResponses.ReadingPage readings(
            String deviceId,
            Instant from,
            Instant to,
            ReadingCursor.Position cursor,
            int limit) {
        requireDevice(deviceId);
        List<AnalyticsResponses.Reading> fetched = repository.readings(deviceId, from, to, cursor, limit + 1);
        boolean more = fetched.size() > limit;
        List<AnalyticsResponses.Reading> page = more ? new ArrayList<>(fetched.subList(0, limit)) : fetched;
        String nextCursor = null;
        if (more && !page.isEmpty()) {
            AnalyticsResponses.Reading last = page.get(page.size() - 1);
            nextCursor = ReadingCursor.encode(last.occurredAt(), last.eventId());
        }
        return new AnalyticsResponses.ReadingPage(deviceId, from, to, page, nextCursor);
    }

    private void requireDevice(String deviceId) {
        if (!repository.deviceExists(deviceId)) {
            throw new NotFoundException("Device was not found.");
        }
    }
}
