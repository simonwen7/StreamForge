# `telemetry.reading` Event Contract V1

## Status

This is the initial contract direction approved in M1. It is documentation, not an implemented API or schema. Field validation, wire-level canonicalization, compatibility policy, and persistence details require M3 review.

## Example

The producer-supplied request is expected to contain:

```json
{
  "eventId": "2b4f9f6b-69b8-48b5-9c8d-974f89012ed1",
  "deviceId": "sensor-042",
  "eventType": "telemetry.reading",
  "schemaVersion": 1,
  "occurredAt": "2026-10-14T19:45:12.483Z",
  "temperatureC": 21.7,
  "humidityPct": 48.2,
  "batteryPct": 87.0
}
```

After acceptance and processing, server-controlled lifecycle metadata may be represented alongside the event:

```json
{
  "eventId": "2b4f9f6b-69b8-48b5-9c8d-974f89012ed1",
  "deviceId": "sensor-042",
  "eventType": "telemetry.reading",
  "schemaVersion": 1,
  "occurredAt": "2026-10-14T19:45:12.483Z",
  "temperatureC": 21.7,
  "humidityPct": 48.2,
  "batteryPct": 87.0,
  "receivedAt": "2026-10-14T19:45:13.021Z",
  "processedAt": "2026-10-14T19:45:13.286Z"
}
```

This second example is conceptual. M3 must decide whether lifecycle fields are included in the Kafka envelope, query representations, or separate status resources. Producers must not set `receivedAt` or `processedAt`.

## Field Semantics

| Field | Owner | Meaning |
| --- | --- | --- |
| `eventId` | Producer | Stable UUID identifying one logical event. It is reused for retries, never for another event. |
| `deviceId` | Producer | Stable device identifier within the future tenant/identity model. Format and length limits are open. |
| `eventType` | Producer | Must equal `telemetry.reading` for this contract. |
| `schemaVersion` | Producer | Integer contract version; V1 uses `1`. |
| `occurredAt` | Producer | UTC instant when the measurement occurred. |
| `temperatureC` | Producer | Temperature in degrees Celsius. Numeric bounds and precision are open. |
| `humidityPct` | Producer | Relative humidity percentage. Valid range is expected to be constrained in M3. |
| `batteryPct` | Producer | Reported battery percentage. Valid range and optionality are open. |
| `receivedAt` | Server | UTC instant assigned during durable API acceptance. |
| `processedAt` | Server | UTC instant assigned when the defined processing transaction succeeds. |

Timestamps use an unambiguous ISO 8601/RFC 3339 UTC representation on the wire. Storage precision and normalization rules remain open.

## Identity, Duplicates, and Conflicts

`eventId` is the idempotency identity across HTTP acceptance, the outbox, Kafka publication, and Worker processing.

- **New event ID:** after the event and outbox record commit atomically, return HTTP `202 Accepted`.
- **Identical retry:** do not create another accepted event, outbox entry, or business effect. The exact success response body/status for this case remains open in M3; it must communicate existing acceptance without claiming processing.
- **Conflicting reuse:** if the same event ID is supplied with different material producer-controlled content, reject it. The exact HTTP status, error body, audit record, and canonical equality algorithm remain open in M3.
- **Duplicate Kafka delivery:** the Worker identifies the event as already processed and performs no duplicate business mutation.

“Identical” cannot safely be based on raw JSON bytes because field order and numeric formatting may differ. M3 must define which fields are material and how they are normalized or hashed. Server-generated fields are not part of the producer-content comparison.

## Lifecycle States

The contract distinguishes:

1. **Received but not accepted:** validation or persistence has not completed; no durable acceptance claim is made.
2. **Accepted:** the event and outbox intent are committed in PostgreSQL. HTTP `202` indicates this state.
3. **Published:** Kafka has acknowledged a publication attempt and the outbox state reflects it; duplicate publications remain possible.
4. **Processed:** the Worker has committed its idempotency record and business/read-model effects. `processedAt` is generated under the final definition.
5. **Failed or blocked:** publication or processing requires retry or operator action.

The public API status vocabulary and terminal/non-terminal failure rules remain open. “Accepted” must never be presented as “processed.”

## Time Semantics

- **Event time (`occurredAt`)** represents when the device says the measurement happened. It can be late, out of order, skewed, or invalid.
- **Acceptance time (`receivedAt`)** represents when StreamForge durably accepted the event.
- **Processing time (`processedAt`)** represents completion of the defined server-side processing transaction.

Analytics that depend on real-world chronology should use event time with an explicit late-data policy. Operational latency uses server times. No ordering guarantee, lateness window, clock-skew tolerance, or correction policy is approved in M1.

## Versioning and Compatibility

V1 is identified by the pair `eventType = "telemetry.reading"` and `schemaVersion = 1`. Consumers must reject or quarantine unsupported versions explicitly. M3 must define:

- additive versus breaking changes;
- unknown-field handling;
- required and nullable fields;
- content type and API envelope;
- schema publication and validation mechanism;
- topic naming and whether versions share a topic;
- producer and consumer compatibility windows.

## M3 Review Checklist

The following are assumptions, not final implementation decisions:

- endpoint, authentication context, and response/error schemas;
- UUID versions accepted and text normalization;
- `deviceId` ownership, tenancy, allowed characters, and limits;
- numeric type, precision, range, nullability, and non-finite-number handling;
- timestamp precision, clock-skew checks, and future/late-event policy;
- canonical duplicate comparison and payload hash strategy;
- persistence constraints and event/status schema;
- Kafka topic, key, headers, partitioning, ordering, and retention;
- lifecycle status exposure and definition of successful processing;
- retry, dead-letter, replay, and conflict audit behavior;
- personally identifiable or sensitive data classification.

## Related Documents

- [Requirements](requirements.md)
- [Architecture](architecture.md)
- [ADR-08](decisions/ADR-08.md)

