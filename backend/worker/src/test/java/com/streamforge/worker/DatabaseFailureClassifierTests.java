package com.streamforge.worker;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;

import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DatabaseFailureClassifierTests {

    @Test
    void constraintViolationsArePoisonAndConnectionFailuresStayRetryable() {
        assertThatThrownBy(() -> DatabaseFailureClassifier.classify(integrity("23503"), false))
                .isInstanceOf(PoisonMessageException.class)
                .hasMessage("Persisted event cannot be recorded.");
        assertThatThrownBy(() -> DatabaseFailureClassifier.classify(integrity("23502"), false))
                .isInstanceOf(PoisonMessageException.class);
        assertThatThrownBy(() -> DatabaseFailureClassifier.classify(integrity("23514"), false))
                .isInstanceOf(PoisonMessageException.class);
        assertThatThrownBy(() -> DatabaseFailureClassifier.classify(integrity("23505"), false))
                .isInstanceOf(PoisonMessageException.class)
                .hasMessage("Processing receipt conflicted without an existing receipt.");
        DatabaseFailureClassifier.classify(integrity("23505"), true);
        DataAccessResourceFailureException down = new DataAccessResourceFailureException("down");
        assertThatThrownBy(() -> DatabaseFailureClassifier.classify(down, false)).isSameAs(down);
    }

    private static DataIntegrityViolationException integrity(String state) {
        return new DataIntegrityViolationException("constraint", new SQLException("constraint", state));
    }
}
