# `telemetry.reading` Event Contract V1

## Status

M1 approved the identity and lifecycle direction. On 2026-10-14 the owner approved a nested `measurements` object for the M3 implementation. The earlier flat example is not an accepted alternative. M3 implements this contract for HTTP acceptance and PostgreSQL storage. Kafka publication and Worker processing are not implemented.

## Request

```json
{
  "eventId": "2b4f9f6b-69b8-48b5-9c8d-974f89012ed1",
  "deviceId": "sensor-042",
  "eventType": "telemetry.reading",
  "schemaVersion": 1,
  "occurredAt": "2026-10-14T19:45:12.483Z",
  "measurements": {
    "temperatureC": 21.7,
    "humidityPct": 48.2,
    "batteryPct": 87.0
  }
}
```

`occurredAt` may use `Z` or a numeric offset such as `-04:00`. Both are normalized to UTC.

## Acceptance response

A new durable acceptance returns HTTP `202`. An identical retry returns HTTP `200` and the original `receivedAt`. Neither response means the event was published or processed.

```json
{
  "eventId": "2b4f9f6b-69b8-48b5-9c8d-974f89012ed1",
  "deviceId": "sensor-042",
  "acceptance": "ACCEPTED",
  "receivedAt": "2026-10-14T19:45:13.021Z",
  "publication": "PENDING",
  "processing": "NOT_STARTED"
}
```

`processedAt` is not returned. `publication` stays `PENDING` until a later milestone records publication. `processing` stays `NOT_STARTED` in M3.

## Field rules

| Field | Rule |
| --- | --- |
| `eventId` | UUID. The nil UUID is rejected. Stored as PostgreSQL `uuid`. |
| `deviceId` | `^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$` |
| `eventType` | Exactly `telemetry.reading` |
| `schemaVersion` | JSON integer `1`. Strings and fractional numbers are rejected. |
| `occurredAt` | RFC 3339 with an explicit offset. At most six fractional digits. Normalized to UTC microseconds. Rejected when more than five minutes ahead of the server clock. Older events are accepted. |
| `measurements.temperatureC` | Required decimal from -40 through 125. |
| `measurements.humidityPct` | Required decimal from 0 through 100. |
| `measurements.batteryPct` | Required decimal from 0 through 100. |

All three measurements are required. Explicit nulls, missing fields, non-numeric values, and non-finite values are rejected. Unknown top-level fields and unknown fields inside `measurements` are rejected. A flat payload that places measurements beside `eventId` is rejected.

Decimal scale is the number of digits after the decimal point. Scale must be from 0 through 3, so `87.0000` is rejected. Trailing zeros within that scale are insignificant: `87.0` and `87.000` are both valid and compare as the same canonical value `87`.

## Canonical identity

Duplicate detection does not compare raw JSON. The version 1 canonical encoding contains, in order:

1. a version byte;
2. the 16-byte UUID;
3. length-prefixed UTF-8 `deviceId` and `eventType`;
4. `schemaVersion` as a 32-bit integer;
5. `occurredAt` as UTC epoch microseconds;
6. length-prefixed canonical decimals for the three measurements.

Lengths are unsigned 16-bit big-endian values, so the encoding does not depend on a delimiter character. SHA-256 of those bytes is stored with the event. `receivedAt`, publication, processing, and database metadata are not part of the hash. Equivalent offsets and equivalent decimals produce the same hash.

## HTTP results

| Situation | Status |
| --- | --- |
| New event and outbox row committed | 202 |
| Same event ID and same canonical content | 200 |
| Same event ID and different canonical content | 409 |
| Unknown device | 404 |
| Invalid event | 400 |
| Missing or invalid credential | 401 |
| Valid credential for the wrong route | 403 |
| Database unavailable | 503 |
| Unexpected failure | 500 |

Error bodies use the existing Problem Details shape. They do not include credentials or SQL.

## Still deferred

Kafka envelope, topic key, schema distribution, late-event business policy, `processedAt`, and publication status transitions beyond the nullable `published_at` column.

## Related Documents

- [Architecture](architecture.md)
- [ADR-08](decisions/ADR-08.md)
- [M3 Milestone](milestones/M3.md)
