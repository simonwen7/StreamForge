import { defineConfig, devices } from "@playwright/test";

const baseURL = "http://127.0.0.1:4174";

export default defineConfig({
  testDir: "e2e",
  testMatch: "**/showcase.spec.ts",
  timeout: 60_000,
  expect: { timeout: 10_000 },
  fullyParallel: false,
  workers: 1,
  retries: process.env.CI ? 1 : 0,
  reporter: [["list"], ["junit", { outputFile: "test-results/showcase-junit.xml" }]],
  use: {
    baseURL,
    trace: "off",
    video: "off",
    screenshot: "off",
  },
  webServer: {
    command: "npm run build && npm run preview",
    url: baseURL,
    reuseExistingServer: false,
    timeout: 180_000,
  },
  projects: [
    { name: "desktop", use: { ...devices["Desktop Chrome"], viewport: { width: 1280, height: 800 } } },
    { name: "tablet", use: { ...devices["Desktop Chrome"], viewport: { width: 768, height: 1024 } } },
    { name: "mobile", use: { ...devices["Pixel 7"], viewport: { width: 390, height: 844 } } },
  ],
});
