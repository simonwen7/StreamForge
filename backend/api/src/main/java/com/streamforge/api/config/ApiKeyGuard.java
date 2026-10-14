package com.streamforge.api.config;

/**
 * Startup rules for the two M3 API keys. Messages name the variable and never include the value.
 */
public final class ApiKeyGuard {

    public static final int MINIMUM_LENGTH = 32;
    public static final String ADMIN_PROPERTY = "streamforge.security.device-admin-api-key";
    public static final String INGESTION_PROPERTY = "streamforge.security.ingestion-api-key";
    public static final String ADMIN_INVALID = "DEVICE_ADMIN_API_KEY must be set to a non-empty value of at least 32 characters.";
    public static final String INGESTION_INVALID = "INGESTION_API_KEY must be set to a non-empty value of at least 32 characters.";
    public static final String KEYS_MUST_DIFFER = "DEVICE_ADMIN_API_KEY and INGESTION_API_KEY must be different.";

    private ApiKeyGuard() {
    }

    public static void require(String adminKey, String ingestionKey) {
        requireOne(adminKey, ADMIN_INVALID, "${DEVICE_ADMIN_API_KEY}");
        requireOne(ingestionKey, INGESTION_INVALID, "${INGESTION_API_KEY}");
        if (adminKey.equals(ingestionKey)) {
            throw new IllegalStateException(KEYS_MUST_DIFFER);
        }
    }

    private static void requireOne(String value, String message, String unresolvedPlaceholder) {
        if (value == null || value.isBlank() || value.length() < MINIMUM_LENGTH || unresolvedPlaceholder.equals(value)) {
            throw new IllegalStateException(message);
        }
    }
}
