package com.streamforge.worker;

import org.springframework.dao.DataAccessException;

import java.sql.SQLException;

final class DatabaseFailureClassifier {

    private DatabaseFailureClassifier() {
    }

    static void classify(DataAccessException exception, boolean receiptExists) {
        String state = sqlState(exception);
        if ("23505".equals(state)) {
            if (receiptExists) {
                return;
            }
            throw new PoisonMessageException("Processing receipt conflicted without an existing receipt.");
        }
        if ("23503".equals(state) || "23502".equals(state) || "23514".equals(state)) {
            throw new PoisonMessageException("Persisted event cannot be recorded.");
        }
        throw exception;
    }

    static String sqlState(Throwable exception) {
        Throwable current = exception;
        while (current != null) {
            if (current instanceof SQLException sql && sql.getSQLState() != null) {
                return sql.getSQLState();
            }
            current = current.getCause();
        }
        return null;
    }
}
