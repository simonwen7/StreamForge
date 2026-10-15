import { execFile } from "node:child_process";
import { promisify } from "node:util";
import { expect, test, type Page } from "@playwright/test";

const exec = promisify(execFile);

function required(name: string): string {
  const value = process.env[name];
  if (!value) {
    throw new Error(`${name} is required.`);
  }
  return value;
}

function composeProject(): string {
  const project = process.env.DEMO_COMPOSE_PROJECT ?? "streamforge-m7demo";
  if (project === "streamforge") {
    throw new Error("Refusing the preserved Compose project.");
  }
  return project;
}

function composeArgs(extra: string[]): string[] {
  const file = process.env.DEMO_COMPOSE_FILE ?? "infra/compose.demo.yaml";
  const envFile = process.env.DEMO_ENV_FILE ?? "infra/.env.demo";
  return ["compose", "-p", composeProject(), "-f", file, "--env-file", envFile, ...extra];
}

async function compose(extra: string[]) {
  await exec("docker", composeArgs(extra), {
    cwd: process.env.DEMO_REPO_ROOT ?? "..",
    timeout: 180_000,
  });
}

async function simulate(args: string[]) {
  await compose(["--profile", "demo", "run", "--rm", "--no-deps", "simulator", ...args]);
}

async function receiptCount(deviceId: string): Promise<number> {
  if (!/^e2e\d+$/.test(deviceId)) {
    throw new Error("Unexpected device id.");
  }
  const { stdout } = await exec(
    "docker",
    composeArgs([
      "exec", "-T", "postgres",
      "psql", "-U", "streamforge", "-d", "streamforge", "-tAc",
      "SELECT COUNT(*) FROM streamforge.processed_events WHERE event_id IN (SELECT event_id FROM streamforge.telemetry_events WHERE device_id = '" + deviceId + "')",
    ]),
    { cwd: process.env.DEMO_REPO_ROOT ?? "..", timeout: 20_000 },
  );
  return Number(stdout.trim());
}

function localDateTime(iso: string): string {
  const date = new Date(iso);
  const pad = (value: number) => String(value).padStart(2, "0");
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}T${pad(date.getHours())}:${pad(date.getMinutes())}`;
}

async function signIn(page: Page, key: string) {
  await page.goto("/");
  await page.getByLabel("Device admin key").fill(key);
  await page.getByRole("button", { name: "Continue" }).click();
}

test.beforeAll(() => {
  const base = required("E2E_BASE_URL");
  required("E2E_ADMIN_KEY");
  required("E2E_INGESTION_KEY");
  if (base.includes(":8080") || base.includes(":5432") || base.includes(":9094") || base.includes(":5173")) {
    throw new Error("Refusing a preserved development URL.");
  }
});

test.describe.configure({ mode: "serial" });

test("mobile navigation keeps the key form and primary controls reachable", async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await page.goto("/");
  await expect(page.getByRole("heading", { name: "Local demonstration access" })).toBeVisible();
  await expect(page.getByLabel("Device admin key")).toHaveAttribute("type", "password");
  await signIn(page, required("E2E_ADMIN_KEY"));
  await expect(page.getByRole("heading", { name: "Devices" })).toBeVisible();
  await expect(page.getByText("Starting at offset 0")).toBeVisible();
  await expect(page.getByRole("button", { name: "Register device" })).toBeVisible();
  await expect(page.getByRole("navigation", { name: "Primary" }).getByRole("link", { name: "Devices" })).toBeVisible();
  await page.getByRole("button", { name: "Sign out" }).click();
  await expect(page.getByLabel("Device admin key")).toHaveValue("");
});

test("dashboard shows real telemetry, pagination, sign-out, and API loss", async ({ page }) => {
  const deviceId = `e2e${Date.now()}`;
  const firstSeed = String(Date.now());
  const secondSeed = String(Date.now() + 1);
  const duplicateSeed = String(Date.now() + 2);

  await page.setViewportSize({ width: 1280, height: 900 });
  await page.goto("/");
  await expect(page.getByText("not a public sign-in")).toBeVisible();
  await signIn(page, required("E2E_ADMIN_KEY"));
  await expect(page.getByTestId("api-status")).toHaveText(/API reachable/);

  await page.getByLabel("Device ID").fill(deviceId);
  await page.getByRole("button", { name: "Register device" }).click();
  await expect(page.getByText(`${deviceId} is registered.`)).toBeVisible();
  await page.getByRole("link", { name: new RegExp(deviceId) }).click();
  await expect(page.getByRole("heading", { name: "No accepted readings in this window" })).toBeVisible();
  await expect(page.getByTestId("accepted-in-this-window")).toHaveText("0");

  await simulate([
    "--base-url", "http://api:8080",
    "--allow-service-host", "true",
    "--device-id", deviceId,
    "--seed", firstSeed,
    "--count", "60",
    "--mode", "valid",
    "--clock", "now",
  ]);
  await page.getByRole("button", { name: "Refresh" }).click();
  await expect(page.getByTestId("accepted-in-this-window")).toHaveText("60");
  await expect.poll(async () => receiptCount(deviceId), { timeout: 60_000 }).toBe(60);
  await expect(page.getByRole("heading", { name: "Temperature", level: 3 })).toBeVisible();
  await expect(page.getByRole("heading", { name: "Humidity", level: 3 })).toBeVisible();
  await expect(page.getByRole("heading", { name: "Battery", level: 3 })).toBeVisible();
  await page.getByText("Show bucket values").first().click();
  await expect(page.getByRole("table", { name: "Temperature values" })).toBeVisible();

  await page.getByRole("button", { name: "Day", exact: true }).click();
  await expect(page.getByRole("button", { name: "Day", exact: true })).toHaveAttribute("aria-pressed", "true");
  await expect(page.getByText(/UTC days/).first()).toBeVisible();
  await page.getByRole("button", { name: "Last 7 days" }).click();
  await expect(page.getByTestId("accepted-in-this-window")).toHaveText("60");

  await page.getByRole("link", { name: "Readings" }).click();
  await expect(page.getByRole("button", { name: "Next page" })).toBeVisible();
  await page.getByRole("button", { name: "Next page" }).click();
  await expect(page.getByText("No further page")).toBeVisible();
  await page.getByRole("button", { name: "First page" }).click();
  await expect(page.getByRole("button", { name: "Next page" })).toBeVisible();

  await page.getByRole("link", { name: "Overview" }).click();
  await page.getByRole("button", { name: "Last 24 hours" }).click();
  await page.getByRole("button", { name: "Refresh" }).click();
  await expect(page.getByTestId("accepted-in-this-window")).toHaveText("60");

  await simulate([
    "--base-url", "http://api:8080",
    "--allow-service-host", "true",
    "--device-id", deviceId,
    "--seed", secondSeed,
    "--count", "3",
    "--mode", "valid",
    "--clock", "now",
  ]);
  await page.getByRole("button", { name: "Refresh" }).click();
  await expect(page.getByTestId("accepted-in-this-window")).toHaveText("63");

  await simulate([
    "--base-url", "http://api:8080",
    "--allow-service-host", "true",
    "--device-id", deviceId,
    "--seed", duplicateSeed,
    "--count", "2",
    "--mode", "duplicate",
  ]);
  await page.getByRole("button", { name: "Refresh" }).click();
  await expect(page.getByTestId("accepted-in-this-window")).toHaveText("63");
  await page.getByRole("button", { name: "Custom range" }).click();
  await page.getByLabel("Start").fill(localDateTime("2026-01-01T00:00:00Z"));
  await page.getByLabel("End").fill(localDateTime("2026-01-02T00:00:00Z"));
  await expect(page.getByTestId("accepted-in-this-window")).toHaveText("1");

  await page.getByRole("button", { name: "Sign out" }).click();
  await expect(page.getByLabel("Device admin key")).toHaveValue("");
  await expect(page.getByTestId("accepted-in-this-window")).toHaveCount(0);
  await page.reload();
  await expect(page.getByLabel("Device admin key")).toBeVisible();
  await expect(page.getByTestId("accepted-in-this-window")).toHaveCount(0);

  await signIn(page, required("E2E_INGESTION_KEY"));
  await expect(page.getByRole("alert").first()).toContainText("cannot view devices or analytics");
  await page.getByRole("button", { name: "Sign out" }).click();

  try {
    await compose(["stop", "api"]);
    await page.reload();
    await expect(page.getByTestId("api-status")).toHaveText(/API unreachable/, { timeout: 20_000 });
    await expect(page.getByLabel("Device admin key")).toBeVisible();
  } finally {
    await compose(["start", "api"]);
  }
});
