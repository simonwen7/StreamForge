# Interview demo, about two and a half minutes

1. Open the public site. Say that StreamForge accepts telemetry durably, then publishes it. This page is a browser-local simulation, and the Java system is in the repository.
2. Click Send events. Point at accepted readings moving to a receipt a moment later. Analytics can count the reading before the receipt exists.
3. Pause the broker and send again. Pending publication grows. The accepted rows stay. Restore the broker and the pending count drains.
4. Replay a delivery. The accepted count does not increase. Say that Kafka delivery is at-least-once and the receipt blocks a second effect.
5. Open Analytics. The charts use accepted readings and UTC buckets.
6. Open Architecture and the GitHub links for `OutboxPublisher` and `ProcessingService`. Mention the Docker demo and the test workflows if there is time.

Optional: poison delivery and the dead-letter note, or a late reading that changes an earlier hour. Mention the lease token if the interviewer asks how two publishers stay apart.

Do not describe the page as a live Kafka cluster. Do not quote a throughput number.
