import { defineConfig, mergeConfig, type Plugin } from "vite";
import publicConfig from "./vite.config.ts";

const liveEntry: Plugin = {
  name: "streamforge-live-entry",
  transformIndexHtml: {
    order: "pre",
    handler(html) {
      return html
        .replace("/src/entries/showcase.tsx", "/src/entries/live.tsx")
        .replace("StreamForge — interactive pipeline", "StreamForge")
        .replace(
          "StreamForge is an interactive browser simulation of a transactional outbox, Kafka delivery, and idempotent worker. The Java implementation runs locally.",
          "StreamForge local operations console for devices and accepted telemetry.",
        );
    },
  },
};

/** Live operations console. This config is not used by `npm run build`. */
export default mergeConfig(
  publicConfig,
  defineConfig({
    plugins: [liveEntry],
    server: {
      proxy: {
        "/api": { target: "http://127.0.0.1:8080", changeOrigin: true },
        "/actuator": { target: "http://127.0.0.1:8080", changeOrigin: true },
      },
    },
  }),
);
