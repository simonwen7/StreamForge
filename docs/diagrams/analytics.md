# Analytics and the hourly rebuild

```mermaid
flowchart TD
  events[telemetry_events] --> summary[Summary and series]
  events --> readings[Keyset readings]
  events --> rebuild[Explicit rebuild command]
  rebuild --> hours[device_hour_stats]
  receipts[processed_events] -.-> summary
```

The API does not read `device_hour_stats` or receipts. The rebuild is off unless `STREAMFORGE_ANALYTICS_REBUILD_ENABLED=true`. A late `occurredAt` changes the UTC bucket of the accepted row. Past windows are not finalized.
