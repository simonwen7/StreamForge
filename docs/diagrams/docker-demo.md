# Disposable Docker demo

```mermaid
flowchart LR
  browser[Browser on 127.0.0.1] --> web[nginx web]
  web --> api[API]
  sim[Simulator] --> api
  api --> pg[PostgreSQL]
  api --> kafka[Kafka]
  kafka --> worker[Worker]
  worker --> pg
```

`infra/compose.demo.yaml` publishes the web and API on loopback only. PostgreSQL and Kafka publish no host ports. The web image is the live build, with `/devices` routes and an in-memory device-admin key.
