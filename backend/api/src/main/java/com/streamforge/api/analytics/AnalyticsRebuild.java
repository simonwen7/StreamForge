package com.streamforge.api.analytics;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.UUID;
import javax.sql.DataSource;

/**
 * Manually invoked rebuild of derived hour statistics.
 * One session-level advisory lock covers the invocation. Each UTC day commits separately.
 */
@Service
public class AnalyticsRebuild {

    static final String LOCK_SQL = "SELECT pg_try_advisory_lock(hashtext('streamforge.analytics.rebuild'))";
    static final String UNLOCK_SQL = "SELECT pg_advisory_unlock(hashtext('streamforge.analytics.rebuild'))";

    private static final Logger log = LoggerFactory.getLogger(AnalyticsRebuild.class);

    private final DataSource dataSource;
    private final RebuildRepository repository;
    private final AnalyticsRepository analyticsRepository;
    private final TransactionTemplate transactionTemplate;
    private final RebuildChunkProbe probe;

    AnalyticsRebuild(
            DataSource dataSource,
            RebuildRepository repository,
            AnalyticsRepository analyticsRepository,
            PlatformTransactionManager transactionManager,
            ObjectProvider<RebuildChunkProbe> probes) {
        this.dataSource = dataSource;
        this.repository = repository;
        this.analyticsRepository = analyticsRepository;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.probe = probes.getIfAvailable(() -> dayStart -> { });
    }

    public RebuildResult start(String deviceId, Instant from, Instant to) {
        if (from == null || to == null || !from.isBefore(to)) {
            return RebuildResult.rejected(null, "INVALID_RANGE");
        }
        if (deviceId != null && !analyticsRepository.deviceExists(deviceId)) {
            return RebuildResult.rejected(null, "UNKNOWN_DEVICE");
        }
        Instant effectiveFrom = UtcDays.startOfDay(from);
        Instant effectiveTo = UtcDays.exclusiveEnd(to);
        if (!effectiveFrom.isBefore(effectiveTo)) {
            return RebuildResult.rejected(null, "INVALID_RANGE");
        }
        try (Connection lock = dataSource.getConnection()) {
            if (!tryLock(lock)) {
                log.info("Rebuild lock is already held.");
                return RebuildResult.lockNotAcquired();
            }
            try {
                UUID runId = UUID.randomUUID();
                repository.insertRun(runId, deviceId, from, to, effectiveFrom, effectiveTo);
                log.info(
                        "Rebuild started. runId={} deviceScope={} effectiveFrom={} effectiveTo={}",
                        runId,
                        deviceId == null ? "all" : deviceId,
                        effectiveFrom,
                        effectiveTo);
                return execute(runId);
            } finally {
                unlock(lock);
            }
        } catch (SQLException ex) {
            log.warn("Rebuild lock session failed. reason={}", ex.getClass().getSimpleName());
            return RebuildResult.rejected(null, "LOCK_SESSION");
        }
    }

    public RebuildResult resume(UUID runId) {
        if (runId == null) {
            return RebuildResult.rejected(null, "UNKNOWN_RUN");
        }
        try (Connection lock = dataSource.getConnection()) {
            if (!tryLock(lock)) {
                log.info("Rebuild lock is already held. runId={}", runId);
                return RebuildResult.lockNotAcquired();
            }
            try {
                RebuildRepository.RebuildRun run = repository.find(runId).orElse(null);
                if (run == null) {
                    return RebuildResult.rejected(runId, "UNKNOWN_RUN");
                }
                if ("completed".equals(run.status())) {
                    log.info("Rebuild already completed. runId={}", runId);
                    return RebuildResult.alreadyCompleted(run);
                }
                if (!"running".equals(run.status()) && !"failed".equals(run.status())) {
                    return RebuildResult.rejected(runId, "INVALID_RUN");
                }
                if (!run.nextChunkStart().isBefore(run.effectiveTo())) {
                    log.warn("Rebuild checkpoint is inconsistent. runId={} status={}", runId, run.status());
                    return RebuildResult.rejected(runId, "INCONSISTENT_CHECKPOINT");
                }
                int resumed = repository.markResumed(runId);
                if (resumed != 1) {
                    return RebuildResult.rejected(runId, "INVALID_RUN");
                }
                log.info(
                        "Rebuild resumed. runId={} nextChunkStart={} chunksCommitted={}",
                        runId,
                        run.nextChunkStart(),
                        run.chunksCommitted());
                return execute(runId);
            } finally {
                unlock(lock);
            }
        } catch (SQLException ex) {
            log.warn("Rebuild lock session failed. reason={}", ex.getClass().getSimpleName());
            return RebuildResult.rejected(runId, "LOCK_SESSION");
        }
    }

    private RebuildResult execute(UUID runId) {
        RebuildRepository.RebuildRun run = repository.find(runId)
                .orElseThrow(() -> new IllegalStateException("Rebuild run disappeared."));
        Instant cursor = run.nextChunkStart();
        try {
            while (cursor.isBefore(run.effectiveTo())) {
                Instant dayStart = cursor;
                Instant dayEnd = UtcDays.nextDay(dayStart);
                int rows = transactionTemplate.execute(status -> {
                    int inserted = repository.replaceDay(run.deviceId(), dayStart, dayEnd);
                    int advanced = repository.advance(runId, dayStart, dayEnd);
                    if (advanced != 1) {
                        throw new IllegalStateException("Rebuild checkpoint was not advanced.");
                    }
                    probe.beforeCommit(dayStart);
                    return inserted;
                });
                log.info("Rebuild chunk committed. runId={} dayStart={} rows={}", runId, dayStart, rows);
                cursor = dayEnd;
            }
        } catch (RuntimeException ex) {
            log.warn(
                    "Rebuild chunk failed. runId={} dayStart={} reason={}",
                    runId,
                    cursor,
                    ex.getClass().getSimpleName());
            transactionTemplate.executeWithoutResult(status -> repository.markFailed(runId, "CHUNK_FAILED"));
            RebuildRepository.RebuildRun failed = repository.find(runId).orElse(run);
            return RebuildResult.failed(failed, "CHUNK_FAILED");
        }
        RebuildRepository.RebuildRun completed = repository.find(runId).orElse(run);
        log.info(
                "Rebuild completed. runId={} chunksCommitted={}",
                runId,
                completed.chunksCommitted());
        return RebuildResult.completed(completed);
    }

    private static boolean tryLock(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(LOCK_SQL);
                ResultSet resultSet = statement.executeQuery()) {
            return resultSet.next() && resultSet.getBoolean(1);
        }
    }

    private static void unlock(Connection connection) {
        try (PreparedStatement statement = connection.prepareStatement(UNLOCK_SQL);
                ResultSet resultSet = statement.executeQuery()) {
            resultSet.next();
        } catch (SQLException ex) {
            log.warn("Rebuild unlock failed. reason={}", ex.getClass().getSimpleName());
        }
    }
}
