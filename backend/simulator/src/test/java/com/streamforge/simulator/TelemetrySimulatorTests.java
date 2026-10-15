package com.streamforge.simulator;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntFunction;

import static org.assertj.core.api.Assertions.assertThat;

class TelemetrySimulatorTests {

    private static final String API_KEY = "simulator-test-key-0123456789abcdef";
    private static final String CONFLICT = """
            {"type":"about:blank","title":"Conflict","status":409,"detail":"Event ID was already accepted with different content."}\
            """;

    @Test
    void validModeTreats202AsNewlyAccepted() throws Exception {
        Run run = run("valid", 2, call -> 202);
        assertThat(run.exitCode).isZero();
        assertThat(run.output).contains("status=202 outcome=newly-accepted");
        assertThat(run.output).doesNotContain("outcome=already-accepted");
        assertThat(run.bodies).allSatisfy(body -> assertThat(body).contains("\"measurements\":{"));
        assertThat(run.authorizations).containsOnly("Bearer " + API_KEY);
        assertSecretAbsent(run);
    }

    @Test
    void validModeTreats200AsAlreadyAccepted() throws Exception {
        Run run = run("valid", 2, call -> 200);
        assertThat(run.exitCode).isZero();
        assertThat(run.output).contains("status=200 outcome=already-accepted");
        assertThat(run.output).doesNotContain("outcome=newly-accepted");
        assertSecretAbsent(run);
    }

    @Test
    void repeatedValidModeWithTheSameSeedSucceedsWhenTheServerReturns200() throws Exception {
        List<String> bodies = new ArrayList<>();
        HttpServer server = server(bodies, new ArrayList<>(), call -> call < 2 ? 202 : 200);
        int port = server.getAddress().getPort();
        try {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            ByteArrayOutputStream errors = new ByteArrayOutputStream();
            TelemetrySimulator simulator = simulator(output, errors);
            String[] args = args(port, "valid", 2);
            assertThat(simulator.run(args)).isZero();
            assertThat(simulator.run(args)).isZero();
            assertThat(bodies).hasSize(4);
            assertThat(bodies.get(0)).isEqualTo(bodies.get(2));
            assertThat(bodies.get(1)).isEqualTo(bodies.get(3));
            assertThat(TelemetrySimulator.eventId(7L, 0)).isNotEqualTo(TelemetrySimulator.eventId(7L, 1));
            String text = output.toString(StandardCharsets.UTF_8);
            assertThat(text).contains("outcome=newly-accepted").contains("outcome=already-accepted");
            assertThat(text).doesNotContain(API_KEY);
            assertThat(errors.toString(StandardCharsets.UTF_8)).isEmpty();
        } finally {
            server.stop(0);
        }
    }

    @Test
    void duplicateModeAccepts202Then200() throws Exception {
        Run run = run("duplicate", 3, call -> call == 0 ? 202 : 200);
        assertThat(run.exitCode).isZero();
        assertThat(eventId(run.bodies.get(0))).isEqualTo(eventId(run.bodies.get(1))).isEqualTo(eventId(run.bodies.get(2)));
        assertThat(run.bodies.get(0)).isEqualTo(run.bodies.get(1)).isEqualTo(run.bodies.get(2));
        assertThat(run.output).contains("status=202 outcome=newly-accepted").contains("status=200 outcome=already-accepted");
        assertSecretAbsent(run);
    }

    @Test
    void duplicateModeAccepts200Then200() throws Exception {
        Run run = run("duplicate", 3, call -> 200);
        assertThat(run.exitCode).isZero();
        assertThat(run.bodies).hasSize(3);
        assertThat(run.bodies.get(0)).isEqualTo(run.bodies.get(2));
        assertThat(run.output).contains("status=200 outcome=already-accepted");
        assertThat(run.output).doesNotContain("status=202");
        assertSecretAbsent(run);
    }

    @Test
    void duplicateModeFailsWhen202FollowsAnAcceptedEvent() throws Exception {
        Run run = run("duplicate", 2, call -> call == 0 ? 200 : 202);
        assertThat(run.exitCode).isEqualTo(1);
        assertThat(run.errors).contains("Unexpected status 202 after the event was already accepted.");
        assertSecretAbsent(run);
    }

    @Test
    void conflictModeAccepts202Then409() throws Exception {
        Run run = run("conflict", 3, call -> call == 0 ? 202 : 409);
        assertThat(run.exitCode).isZero();
        assertThat(eventId(run.bodies.get(0))).isEqualTo(eventId(run.bodies.get(1)));
        assertThat(run.bodies.get(0)).isNotEqualTo(run.bodies.get(1));
        assertThat(run.output).contains("status=202 outcome=newly-accepted").contains("status=409 outcome=conflict");
        assertSecretAbsent(run);
    }

    @Test
    void conflictModeAccepts200Then409() throws Exception {
        Run run = run("conflict", 2, call -> call == 0 ? 200 : 409);
        assertThat(run.exitCode).isZero();
        assertThat(run.output).contains("status=200 outcome=already-accepted").contains("status=409 outcome=conflict");
        assertSecretAbsent(run);
    }

    @Test
    void conflictModeFailsWhenTheConflictingSubmissionReturns200() throws Exception {
        Run run = run("conflict", 2, call -> 200);
        assertThat(run.exitCode).isEqualTo(1);
        assertThat(run.errors).contains("Unexpected status 200 for conflicting submission.");
        assertSecretAbsent(run);
    }

    @Test
    void conflictModeFailsWhenTheStoredBaselineDoesNotMatchTheSeed() throws Exception {
        Run run = run("conflict", 2, call -> 409);
        assertThat(run.exitCode).isEqualTo(1);
        assertThat(run.errors).contains("does not match this seed");
        assertThat(run.bodies).hasSize(1);
        assertSecretAbsent(run);
    }

    @Test
    void validModeFailsOnUnexpected409() throws Exception {
        Run run = run("valid", 1, call -> 409);
        assertThat(run.exitCode).isEqualTo(1);
        assertThat(run.errors).contains("Unexpected status 409 for mode valid.");
        assertSecretAbsent(run);
    }

    @Test
    void unrelated200IsNotIngestionAcceptance() throws Exception {
        Run wrongEvent = fixed("valid", 1, 200, acceptance(
                "00000000-0000-4000-8000-000000000099",
                "sensor-042"));
        assertThat(wrongEvent.exitCode).isEqualTo(1);
        assertThat(wrongEvent.errors).contains("Response is not an ingestion acceptance for the submitted event.");

        Run empty = fixed("valid", 1, 200, "{\"ok\":true}");
        assertThat(empty.exitCode).isEqualTo(1);
        assertThat(empty.errors).contains("Response is not an ingestion acceptance for the submitted event.");
        assertSecretAbsent(wrongEvent);
        assertSecretAbsent(empty);
    }

    @Test
    void authenticationAndServerErrorsFail() throws Exception {
        for (int status : List.of(201, 204, 401, 403, 500, 503)) {
            Run run = run("valid", 1, call -> status);
            assertThat(run.exitCode).as("status " + status).isEqualTo(1);
            assertThat(run.errors).contains("Unexpected status " + status + " for mode valid.");
            assertSecretAbsent(run);
        }
        Run duplicateConflict = run("duplicate", 1, call -> 409);
        assertThat(duplicateConflict.exitCode).isEqualTo(1);
        assertThat(duplicateConflict.errors).contains("Unexpected status 409 for mode duplicate.");
        assertSecretAbsent(duplicateConflict);
    }

    @Test
    void invalidModesAndBoundsFailClosed() throws Exception {
        List<String> bodies = new ArrayList<>();
        HttpServer server = server(bodies, new ArrayList<>(), call -> 400);
        try {
            TelemetrySimulator simulator = simulator(new ByteArrayOutputStream(), new ByteArrayOutputStream());
            assertThat(simulator.run(args(server.getAddress().getPort(), "invalid-flat", 1))).isZero();
            assertThat(bodies.get(0)).doesNotContain("\"measurements\"");
            assertThat(simulator.run(args(server.getAddress().getPort(), "invalid-future", 1))).isZero();
            assertThat(bodies.get(1)).contains("2099-01-01T00:00:00Z");
        } finally {
            server.stop(0);
        }

        ByteArrayOutputStream errors = new ByteArrayOutputStream();
        TelemetrySimulator simulator = simulator(new ByteArrayOutputStream(), errors);
        assertThat(simulator.run(new String[]{
                "--base-url", "http://127.0.0.1:9",
                "--device-id", "sensor-042",
                "--seed", "1",
                "--count", "101"
        })).isEqualTo(2);
        assertThat(simulator.run(new String[]{
                "--base-url", "http://example.com",
                "--device-id", "sensor-042",
                "--seed", "1",
                "--count", "1"
        })).isEqualTo(2);
        TelemetrySimulator missingKey = new TelemetrySimulator(
                java.net.http.HttpClient.newHttpClient(),
                Map.of(),
                new PrintStream(new ByteArrayOutputStream()),
                new PrintStream(errors));
        assertThat(missingKey.run(args(9, "valid", 1))).isEqualTo(2);
        assertThat(errors.toString(StandardCharsets.UTF_8)).doesNotContain(API_KEY);
        assertThat(errors.toString(StandardCharsets.UTF_8)).doesNotContain("Bearer");
    }

    @Test
    void sameSeedProducesTheSameEvent() {
        String first = TelemetrySimulator.eventBody(7L, 0, "sensor-042", "valid", false);
        String second = TelemetrySimulator.eventBody(7L, 0, "sensor-042", "valid", false);
        assertThat(first).isEqualTo(second);
        assertThat(first).contains("2026-01-01T00:00:00Z");
        assertThat(TelemetrySimulator.eventId(7L, 0)).isEqualTo(TelemetrySimulator.eventId(7L, 0));
        assertThat(TelemetrySimulator.eventId(7L, 0)).isNotEqualTo(TelemetrySimulator.eventId(7L, 1));
    }

    @Test
    void liveClockStaysInsideThePreviousSixHours() {
        Instant anchor = Instant.parse("2026-10-15T15:04:05.900Z");
        Instant end = Instant.parse("2026-10-15T15:04:05Z");
        assertThat(TelemetrySimulator.liveOccurredAt(anchor, 0, 1)).isEqualTo(end);
        for (int index = 0; index < 12; index++) {
            Instant occurred = TelemetrySimulator.liveOccurredAt(anchor, index, 12);
            assertThat(occurred).isBeforeOrEqualTo(end);
            assertThat(occurred).isAfterOrEqualTo(end.minus(TelemetrySimulator.LIVE_LOOKBACK));
            assertThat(occurred).isEqualTo(occurred.truncatedTo(java.time.temporal.ChronoUnit.SECONDS));
        }
        assertThat(TelemetrySimulator.liveOccurredAt(anchor, 11, 12)).isEqualTo(end);
        assertThat(TelemetrySimulator.liveOccurredAt(anchor, 0, 12)).isEqualTo(end.minus(Duration.ofHours(6)));
    }

    @Test
    void sameSeedAtDifferentAnchorsKeepsTheEventIdAndChangesOccurredAt() {
        Instant firstAnchor = Instant.parse("2026-10-15T15:00:00Z");
        Instant secondAnchor = Instant.parse("2026-10-15T16:00:00Z");
        String first = TelemetrySimulator.eventBody(
                7L, 0, "sensor-042", "valid", false, TelemetrySimulator.liveOccurredAt(firstAnchor, 3, 12));
        String second = TelemetrySimulator.eventBody(
                7L, 0, "sensor-042", "valid", false, TelemetrySimulator.liveOccurredAt(secondAnchor, 3, 12));
        assertThat(eventId(first)).isEqualTo(eventId(second));
        assertThat(first).isNotEqualTo(second);
        assertThat(first).contains("\"temperatureC\":");
        assertThat(first.substring(first.indexOf("\"measurements\"")))
                .isEqualTo(second.substring(second.indexOf("\"measurements\"")));
        assertThat(TelemetrySimulator.eventId(11L, 0)).isNotEqualTo(TelemetrySimulator.eventId(12L, 0));
    }

    @Test
    void clockNowUsesOneAnchorAndRejectsOtherModes() throws Exception {
        Instant anchor = Instant.parse("2026-10-15T15:00:00Z");
        Clock clock = Clock.fixed(anchor, ZoneOffset.UTC);
        List<String> bodies = new ArrayList<>();
        HttpServer server = server(bodies, new ArrayList<>(), call -> 202);
        try {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            ByteArrayOutputStream errors = new ByteArrayOutputStream();
            TelemetrySimulator simulator = new TelemetrySimulator(
                    java.net.http.HttpClient.newHttpClient(),
                    Map.of(TelemetrySimulator.KEY_VARIABLE, API_KEY),
                    new PrintStream(output, true, StandardCharsets.UTF_8),
                    new PrintStream(errors, true, StandardCharsets.UTF_8),
                    clock);
            String[] args = new String[]{
                    "--base-url", "http://127.0.0.1:" + server.getAddress().getPort(),
                    "--device-id", "sensor-042",
                    "--seed", "7",
                    "--count", "4",
                    "--mode", "valid",
                    "--clock", "now"
            };
            assertThat(simulator.run(args)).isZero();
            assertThat(bodies).hasSize(4);
            Instant earliest = anchor.minus(Duration.ofHours(6));
            for (String body : bodies) {
                Instant occurred = Instant.parse(field(body, "occurredAt"));
                assertThat(occurred).isBeforeOrEqualTo(anchor);
                assertThat(occurred).isAfterOrEqualTo(earliest);
                assertThat(body).contains("\"measurements\":{");
            }
            assertThat(output.toString(StandardCharsets.UTF_8)).doesNotContain(API_KEY);
        } finally {
            server.stop(0);
        }

        ByteArrayOutputStream errors = new ByteArrayOutputStream();
        TelemetrySimulator simulator = simulator(new ByteArrayOutputStream(), errors);
        assertThat(simulator.run(new String[]{
                "--base-url", "http://127.0.0.1:9",
                "--device-id", "sensor-042",
                "--seed", "7",
                "--count", "2",
                "--mode", "duplicate",
                "--clock", "now"
        })).isEqualTo(2);
        assertThat(errors.toString(StandardCharsets.UTF_8)).contains("only supported with mode valid");
    }

    @Test
    void serviceHostIsOptInAndStillRejectsOtherHosts() {
        TelemetrySimulator.requireBaseUrl("http://127.0.0.1:8080", false);
        TelemetrySimulator.requireBaseUrl("http://localhost:8080", false);
        TelemetrySimulator.requireBaseUrl("http://api:8080", true);
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> TelemetrySimulator.requireBaseUrl("http://api:8080", false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("loopback");
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> TelemetrySimulator.requireBaseUrl("http://evil.example", true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("loopback");
        ByteArrayOutputStream errors = new ByteArrayOutputStream();
        TelemetrySimulator simulator = simulator(new ByteArrayOutputStream(), errors);
        assertThat(simulator.run(new String[]{
                "--base-url", "http://api:8080",
                "--device-id", "sensor-042",
                "--seed", "1",
                "--count", "1"
        })).isEqualTo(2);
        assertThat(errors.toString(StandardCharsets.UTF_8)).contains("loopback").doesNotContain(API_KEY);
    }

    @Test
    void embeddedCredentialsAreRejectedWithoutBroadeningTheHostAllowlist() {
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> TelemetrySimulator.requireBaseUrl("http://user:pass@api:8080", true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("base-url must target loopback.");
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> TelemetrySimulator.requireBaseUrl("http://user:pass@127.0.0.1:8080", false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("base-url must target loopback.");
        TelemetrySimulator.requireBaseUrl("http://api:8080", true);
        TelemetrySimulator.requireBaseUrl("http://127.0.0.1:8080", false);

        ByteArrayOutputStream errors = new ByteArrayOutputStream();
        TelemetrySimulator simulator = simulator(new ByteArrayOutputStream(), errors);
        assertThat(simulator.run(new String[]{
                "--base-url", "http://user:pass@api:8080",
                "--allow-service-host", "true",
                "--device-id", "sensor-042",
                "--seed", "1",
                "--count", "1",
                "--mode", "valid"
        })).isEqualTo(2);
        assertThat(errors.toString(StandardCharsets.UTF_8))
                .contains("loopback")
                .doesNotContain("user:pass")
                .doesNotContain(API_KEY);
    }

    private static void assertSecretAbsent(Run run) {
        assertThat(run.output).doesNotContain(API_KEY);
        assertThat(run.errors).doesNotContain(API_KEY);
    }

    private static Run run(String mode, int count, IntFunction<Integer> statusForCall) throws Exception {
        List<String> bodies = new ArrayList<>();
        List<String> authorizations = new ArrayList<>();
        HttpServer server = server(bodies, authorizations, statusForCall);
        try {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            ByteArrayOutputStream errors = new ByteArrayOutputStream();
            int exitCode = simulator(output, errors).run(args(server.getAddress().getPort(), mode, count));
            return new Run(
                    exitCode,
                    output.toString(StandardCharsets.UTF_8),
                    errors.toString(StandardCharsets.UTF_8),
                    List.copyOf(bodies),
                    List.copyOf(authorizations));
        } finally {
            server.stop(0);
        }
    }

    private static Run fixed(String mode, int count, int status, String responseBody) throws Exception {
        List<String> bodies = new ArrayList<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/telemetry/readings", exchange -> {
            bodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] response = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            ByteArrayOutputStream errors = new ByteArrayOutputStream();
            int exitCode = simulator(output, errors).run(args(server.getAddress().getPort(), mode, count));
            return new Run(
                    exitCode,
                    output.toString(StandardCharsets.UTF_8),
                    errors.toString(StandardCharsets.UTF_8),
                    List.copyOf(bodies),
                    List.of());
        } finally {
            server.stop(0);
        }
    }

    private static TelemetrySimulator simulator(ByteArrayOutputStream output, ByteArrayOutputStream errors) {
        return new TelemetrySimulator(
                java.net.http.HttpClient.newHttpClient(),
                Map.of(TelemetrySimulator.KEY_VARIABLE, API_KEY),
                new PrintStream(output, true, StandardCharsets.UTF_8),
                new PrintStream(errors, true, StandardCharsets.UTF_8));
    }

    private static String[] args(int port, String mode, int count) {
        return new String[]{
                "--base-url", "http://127.0.0.1:" + port,
                "--device-id", "sensor-042",
                "--seed", "7",
                "--count", Integer.toString(count),
                "--interval-ms", "0",
                "--mode", mode
        };
    }

    private static HttpServer server(
            List<String> bodies,
            List<String> authorizations,
            IntFunction<Integer> statusForCall) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicInteger calls = new AtomicInteger();
        server.createContext("/api/v1/telemetry/readings", exchange -> {
            String requestBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            synchronized (bodies) {
                bodies.add(requestBody);
                authorizations.add(exchange.getRequestHeaders().getFirst("Authorization"));
            }
            int status = statusForCall.apply(calls.getAndIncrement());
            byte[] response = responseFor(status, requestBody).getBytes(StandardCharsets.UTF_8);
            if (status == 204) {
                exchange.sendResponseHeaders(status, -1);
            } else {
                exchange.sendResponseHeaders(status, response.length);
                exchange.getResponseBody().write(response);
            }
            exchange.close();
        });
        server.start();
        return server;
    }

    private static String responseFor(int status, String requestBody) {
        return switch (status) {
            case 200, 202 -> acceptance(eventId(requestBody), field(requestBody, "deviceId"));
            case 409 -> CONFLICT;
            case 400 -> "{\"title\":\"Validation Failed\",\"status\":400}";
            default -> "{}";
        };
    }

    private static String acceptance(String eventId, String deviceId) {
        return "{"
                + "\"eventId\":\"" + eventId + "\","
                + "\"deviceId\":\"" + deviceId + "\","
                + "\"acceptance\":\"ACCEPTED\","
                + "\"receivedAt\":\"2026-01-01T00:00:01.000000Z\","
                + "\"publication\":\"PENDING\","
                + "\"processing\":\"NOT_STARTED\""
                + "}";
    }

    private static String eventId(String body) {
        int start = body.indexOf("\"eventId\":\"") + "\"eventId\":\"".length();
        return body.substring(start, body.indexOf('"', start));
    }

    private static String field(String body, String name) {
        String needle = "\"" + name + "\":\"";
        int start = body.indexOf(needle) + needle.length();
        return body.substring(start, body.indexOf('"', start));
    }

    private record Run(int exitCode, String output, String errors, List<String> bodies, List<String> authorizations) {
    }
}
