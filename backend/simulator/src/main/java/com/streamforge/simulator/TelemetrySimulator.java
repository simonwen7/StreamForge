package com.streamforge.simulator;

import java.io.PrintStream;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

/**
 * Bounded, manually executed HTTP client for the local ingestion API.
 * The ingestion credential is read from INGESTION_API_KEY and is never printed.
 */
public final class TelemetrySimulator {

    public static final int MAX_COUNT = 100;
    static final String KEY_VARIABLE = "INGESTION_API_KEY";

    private final HttpClient httpClient;
    private final Map<String, String> environment;
    private final PrintStream out;
    private final PrintStream err;

    public TelemetrySimulator(HttpClient httpClient, Map<String, String> environment, PrintStream out, PrintStream err) {
        this.httpClient = httpClient;
        this.environment = environment;
        this.out = out;
        this.err = err;
    }

    public static void main(String[] args) {
        int code = new TelemetrySimulator(HttpClient.newHttpClient(), System.getenv(), System.out, System.err).run(args);
        if (code != 0) {
            System.exit(code);
        }
    }

    public int run(String[] args) {
        if (args.length == 0 || has(args, "--help")) {
            out.println(usage());
            return 0;
        }
        try {
            Options options = Options.parse(args);
            String apiKey = environment.get(KEY_VARIABLE);
            if (apiKey == null || apiKey.isBlank()) {
                err.println(KEY_VARIABLE + " must be set in the environment.");
                return 2;
            }
            List<String> bodies = options.bodies();
            boolean duplicateAccepted = false;
            for (int index = 0; index < bodies.size(); index++) {
                if (index > 0 && options.intervalMillis > 0) {
                    Thread.sleep(options.intervalMillis);
                }
                String eventId = eventIdOf(bodies.get(index));
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(options.baseUrl + "/api/v1/telemetry/readings"))
                        .timeout(Duration.ofSeconds(10))
                        .header("Authorization", "Bearer " + apiKey)
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(bodies.get(index)))
                        .build();
                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                Decision decision = decide(
                        options.mode,
                        index,
                        response.statusCode(),
                        response.body(),
                        eventId,
                        options.deviceId,
                        duplicateAccepted);
                out.println("eventId=" + eventId + " status=" + response.statusCode() + " outcome=" + decision.outcome);
                if (!decision.success) {
                    err.println(decision.error);
                    return 1;
                }
                if ("duplicate".equals(options.mode)) {
                    duplicateAccepted = true;
                }
            }
            return 0;
        } catch (IllegalArgumentException ex) {
            err.println(ex.getMessage());
            return 2;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            err.println("Simulator was interrupted.");
            return 1;
        } catch (Exception ex) {
            err.println("Simulator request failed: " + ex.getClass().getSimpleName());
            return 1;
        }
    }

    static String usage() {
        return """
                Usage: TelemetrySimulator --base-url http://127.0.0.1:8080 --device-id sensor-042 --seed 1 --count 3 [--interval-ms 0] [--mode valid]
                Modes: valid, duplicate, conflict, invalid-flat, invalid-future
                The device must already exist. Read INGESTION_API_KEY from the environment. Count must be 1..100.
                The same seed repeats the same events. HTTP 202 is newly accepted and HTTP 200 is already accepted.
                valid and duplicate succeed on those acceptance responses. conflict requires HTTP 409 for the changed submission.
                """;
    }

    static UUID eventId(long seed, int index) {
        byte[] hash = sha256(("streamforge-simulator:" + seed + ":" + index).getBytes(StandardCharsets.UTF_8));
        hash[6] = (byte) ((hash[6] & 0x0f) | 0x40);
        hash[8] = (byte) ((hash[8] & 0x3f) | 0x80);
        ByteBuffer buffer = ByteBuffer.wrap(hash);
        return new UUID(buffer.getLong(), buffer.getLong());
    }

    static String eventBody(long seed, int index, String deviceId, String mode, boolean conflictVariant) {
        if ("invalid-flat".equals(mode)) {
            return "{"
                    + "\"eventId\":\"" + eventId(seed, index) + "\","
                    + "\"deviceId\":\"" + deviceId + "\","
                    + "\"eventType\":\"telemetry.reading\","
                    + "\"schemaVersion\":1,"
                    + "\"occurredAt\":\"2026-01-01T00:00:00Z\","
                    + "\"temperatureC\":21.7,"
                    + "\"humidityPct\":48.2,"
                    + "\"batteryPct\":87.0"
                    + "}";
        }
        Measurement measurement = measurement(seed, index, conflictVariant);
        String occurredAt = "invalid-future".equals(mode)
                ? "2099-01-01T00:00:00Z"
                : Instant.parse("2026-01-01T00:00:00Z").plusSeconds(index).toString();
        return "{"
                + "\"eventId\":\"" + eventId(seed,  "duplicate".equals(mode) || "conflict".equals(mode) ? 0 : index) + "\","
                + "\"deviceId\":\"" + deviceId + "\","
                + "\"eventType\":\"telemetry.reading\","
                + "\"schemaVersion\":1,"
                + "\"occurredAt\":\"" + occurredAt + "\","
                + "\"measurements\":{"
                + "\"temperatureC\":" + measurement.temperature.toPlainString() + ","
                + "\"humidityPct\":" + measurement.humidity.toPlainString() + ","
                + "\"batteryPct\":" + measurement.battery.toPlainString()
                + "}}";
    }

    private static String eventIdOf(String body) {
        int start = body.indexOf("\"eventId\":\"");
        if (start < 0) {
            return "unknown";
        }
        start += "\"eventId\":\"".length();
        int end = body.indexOf('"', start);
        return body.substring(start, end);
    }

    private static Measurement measurement(long seed, int index, boolean conflictVariant) {
        Random random = new Random(seed * 31L + index);
        int temperatureTenths = -400 + random.nextInt(1651);
        int humidityTenths = random.nextInt(1001);
        int batteryTenths = random.nextInt(1001);
        if (conflictVariant) {
            temperatureTenths = temperatureTenths >= 1250 ? temperatureTenths - 1 : temperatureTenths + 1;
        }
        return new Measurement(
                BigDecimal.valueOf(temperatureTenths, 1),
                BigDecimal.valueOf(humidityTenths, 1),
                BigDecimal.valueOf(batteryTenths, 1));
    }

    private static boolean has(String[] args, String flag) {
        for (String arg : args) {
            if (flag.equals(arg)) {
                return true;
            }
        }
        return false;
    }

    private static byte[] sha256(byte[] value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is not available.");
        }
    }

    private record Measurement(BigDecimal temperature, BigDecimal humidity, BigDecimal battery) {
    }

    private record Decision(boolean success, String outcome, String error) {
    }

    private static Decision decide(
            String mode,
            int index,
            int status,
            String body,
            String eventId,
            String deviceId,
            boolean duplicateAccepted) {
        return switch (mode) {
            case "valid" -> decideValid(status, body, eventId, deviceId);
            case "duplicate" -> decideDuplicate(status, body, eventId, deviceId, duplicateAccepted);
            case "conflict" -> decideConflict(index, status, body, eventId, deviceId);
            case "invalid-flat", "invalid-future" -> status == 400
                    ? new Decision(true, "rejected", null)
                    : unexpected(mode, status);
            default -> unexpected(mode, status);
        };
    }

    private static Decision decideValid(int status, String body, String eventId, String deviceId) {
        if (status == 202) {
            return acceptance(body, eventId, deviceId, "newly-accepted");
        }
        if (status == 200) {
            return acceptance(body, eventId, deviceId, "already-accepted");
        }
        return unexpected("valid", status);
    }

    private static Decision decideDuplicate(
            int status,
            String body,
            String eventId,
            String deviceId,
            boolean duplicateAccepted) {
        if (status == 202 && duplicateAccepted) {
            return new Decision(false, "failed", "Unexpected status 202 after the event was already accepted.");
        }
        if (status == 202) {
            return acceptance(body, eventId, deviceId, "newly-accepted");
        }
        if (status == 200) {
            return acceptance(body, eventId, deviceId, "already-accepted");
        }
        return unexpected("duplicate", status);
    }

    private static Decision decideConflict(int index, int status, String body, String eventId, String deviceId) {
        if (index == 0) {
            if (status == 202) {
                return acceptance(body, eventId, deviceId, "newly-accepted");
            }
            if (status == 200) {
                return acceptance(body, eventId, deviceId, "already-accepted");
            }
            if (status == 409) {
                return new Decision(
                        false,
                        "failed",
                        "Unexpected status 409 for the conflict baseline. The stored event does not match this seed.");
            }
            return unexpected("conflict", status);
        }
        if (status != 409) {
            return new Decision(false, "failed", "Unexpected status " + status + " for conflicting submission.");
        }
        if (!isConflictProblem(body)) {
            return new Decision(false, "failed", "Response is not a conflict for the submitted event.");
        }
        return new Decision(true, "conflict", null);
    }

    private static Decision acceptance(String body, String eventId, String deviceId, String outcome) {
        if (!isAcceptance(body, eventId, deviceId)) {
            return new Decision(false, "failed", "Response is not an ingestion acceptance for the submitted event.");
        }
        return new Decision(true, outcome, null);
    }

    private static Decision unexpected(String mode, int status) {
        return new Decision(false, "failed", "Unexpected status " + status + " for mode " + mode + ".");
    }

    static boolean isAcceptance(String body, String eventId, String deviceId) {
        return eventId.equals(jsonStringField(body, "eventId"))
                && deviceId.equals(jsonStringField(body, "deviceId"))
                && "ACCEPTED".equals(jsonStringField(body, "acceptance"))
                && "PENDING".equals(jsonStringField(body, "publication"))
                && "NOT_STARTED".equals(jsonStringField(body, "processing"))
                && !isBlank(jsonStringField(body, "receivedAt"));
    }

    static boolean isConflictProblem(String body) {
        if (body == null || "ACCEPTED".equals(jsonStringField(body, "acceptance"))) {
            return false;
        }
        if ("Conflict".equals(jsonStringField(body, "title"))) {
            return true;
        }
        String compact = body.replace(" ", "").replace("\n", "").replace("\r", "").replace("\t", "");
        return compact.contains("\"status\":409");
    }

    static String jsonStringField(String body, String field) {
        if (body == null || field == null) {
            return null;
        }
        String needle = "\"" + field + "\"";
        int from = 0;
        while (from < body.length()) {
            int keyAt = body.indexOf(needle, from);
            if (keyAt < 0) {
                return null;
            }
            int cursor = keyAt + needle.length();
            while (cursor < body.length() && Character.isWhitespace(body.charAt(cursor))) {
                cursor++;
            }
            if (cursor >= body.length() || body.charAt(cursor) != ':') {
                from = keyAt + 1;
                continue;
            }
            cursor++;
            while (cursor < body.length() && Character.isWhitespace(body.charAt(cursor))) {
                cursor++;
            }
            if (cursor >= body.length() || body.charAt(cursor) != '"') {
                return null;
            }
            int end = cursor + 1;
            while (end < body.length()) {
                if (body.charAt(end) == '\\') {
                    end += 2;
                    continue;
                }
                if (body.charAt(end) == '"') {
                    return body.substring(cursor + 1, end);
                }
                end++;
            }
            return null;
        }
        return null;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    static final class Options {
        final String baseUrl;
        final String deviceId;
        final long seed;
        final int count;
        final long intervalMillis;
        final String mode;

        private Options(String baseUrl, String deviceId, long seed, int count, long intervalMillis, String mode) {
            this.baseUrl = baseUrl;
            this.deviceId = deviceId;
            this.seed = seed;
            this.count = count;
            this.intervalMillis = intervalMillis;
            this.mode = mode;
        }

        static Options parse(String[] args) {
            Map<String, String> values = new LinkedHashMap<>();
            for (int i = 0; i < args.length; i++) {
                String arg = args[i];
                if (!arg.startsWith("--")) {
                    throw new IllegalArgumentException("Unexpected argument.");
                }
                if (i + 1 >= args.length) {
                    throw new IllegalArgumentException("Missing value for " + arg + ".");
                }
                values.put(arg, args[++i]);
            }
            String baseUrl = required(values, "--base-url");
            if (!baseUrl.startsWith("http://127.0.0.1") && !baseUrl.startsWith("http://localhost")) {
                throw new IllegalArgumentException("base-url must target loopback.");
            }
            String deviceId = required(values, "--device-id");
            if (!deviceId.matches("^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$")) {
                throw new IllegalArgumentException("device-id format is invalid.");
            }
            long seed = Long.parseLong(required(values, "--seed"));
            int count = Integer.parseInt(required(values, "--count"));
            if (count < 1 || count > MAX_COUNT) {
                throw new IllegalArgumentException("count must be between 1 and 100.");
            }
            long interval = values.containsKey("--interval-ms") ? Long.parseLong(values.get("--interval-ms")) : 0L;
            if (interval < 0 || interval > 60_000) {
                throw new IllegalArgumentException("interval-ms must be between 0 and 60000.");
            }
            String mode = values.getOrDefault("--mode", "valid");
            if (!List.of("valid", "duplicate", "conflict", "invalid-flat", "invalid-future").contains(mode)) {
                throw new IllegalArgumentException("mode is not supported.");
            }
            if (("invalid-flat".equals(mode) || "invalid-future".equals(mode)) && count != 1) {
                throw new IllegalArgumentException("invalid modes send exactly one event.");
            }
            return new Options(stripTrailingSlash(baseUrl), deviceId, seed, count, interval, mode);
        }

        List<String> bodies() {
            List<String> bodies = new ArrayList<>();
            for (int index = 0; index < count; index++) {
                boolean conflictVariant = "conflict".equals(mode) && index > 0;
                int sourceIndex = "duplicate".equals(mode) || "conflict".equals(mode) ? 0 : index;
                bodies.add(eventBody(seed, sourceIndex, deviceId, mode, conflictVariant));
            }
            return bodies;
        }

        private static String required(Map<String, String> values, String name) {
            String value = values.get(name);
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException(name + " is required.");
            }
            return value;
        }

        private static String stripTrailingSlash(String baseUrl) {
            return baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        }
    }
}
