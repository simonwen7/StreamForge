import { defineConfig, devices } from "@playwright/test";

export default defineConfig({
  testDir: "e2e",
  testIgnore: ["**/showcase.spec.ts"],
  timeout: 240_000,
  expect: { timeout: 20_000 },
  fullyParallel: false,
  workers: 1,
  retries: process.env.CI ? 1 : 0,
  reporter: [["list"], ["junit", { outputFile: "test-results/junit.xml" }]],
  use: {
    baseURL: process.env.E2E_BASE_URL ?? "http://127.0.0.1:4173",
    trace: "off",
    video: "off",
    screenshot: "off",
  },
  projects: [{ name: "chromium", use: { ...devices["Desktop Chrome"] } }],
});
