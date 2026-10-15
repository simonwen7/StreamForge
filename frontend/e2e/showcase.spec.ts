import { expect, test, type Page } from "@playwright/test";

function watch(page: Page): string[] {
  const urls: string[] = [];
  page.on("request", (request) => urls.push(request.url()));
  page.on("console", (message) => {
    if (message.type() === "error") {
      urls.push(`console:${message.text()}`);
    }
  });
  return urls;
}

function assertIsolated(urls: string[]) {
  const joined = urls.join("\n");
  expect(joined).not.toContain("/api/");
  expect(joined).not.toContain("/actuator");
  expect(joined).not.toContain("localhost:8080");
  expect(joined).not.toContain("console:");
}

test("runs the pipeline scenarios without a backend", async ({ page }) => {
  const urls = watch(page);
  await page.goto("/");
  await expect(page.getByText("Browser-local simulation")).toBeVisible();
  await expect(page.getByLabel("Device admin key")).toHaveCount(0);
  await page.getByTestId("pause").click();
  await page.getByTestId("send-one").click();
  await expect(page.getByTestId("accepted-count")).toHaveText("1");
  await expect(page.getByTestId("receipt-count")).toHaveText("0");
  await page.getByTestId("step").click();
  await page.getByTestId("step").click();
  await expect(page.getByTestId("receipt-count")).toHaveText("1");

  await page.getByTestId("broker").click();
  await page.getByTestId("send-ten").click();
  await page.getByTestId("step").click();
  await page.getByTestId("step").click();
  await expect(page.getByTestId("pending-count")).toHaveText("10");
  await page.getByTestId("broker").click();
  await page.getByTestId("step").click();
  await page.getByTestId("step").click();
  await page.getByTestId("step").click();
  await page.getByTestId("step").click();
  await expect(page.getByTestId("pending-count")).toHaveText("0");

  await page.getByTestId("reset").click();
  await page.getByTestId("worker").click();
  await page.getByTestId("send-one").click();
  await page.getByTestId("step").click();
  await page.getByTestId("step").click();
  await expect(page.getByTestId("awaiting-count")).toHaveText("1");
  await expect(page.getByTestId("receipt-count")).toHaveText("0");
  await page.getByTestId("worker").click();
  await page.getByTestId("step").click();
  await expect(page.getByTestId("receipt-count")).toHaveText("1");

  await page.getByRole("button", { name: /sf-evt-/ }).click();
  await page.getByTestId("replay").click();
  await page.getByTestId("step").click();
  await expect(page.getByTestId("accepted-count")).toHaveText("1");
  await expect(page.getByTestId("duplicate-count")).toHaveText("1");

  await page.getByTestId("poison").click();
  await page.getByTestId("step").click();
  await expect(page.getByTestId("dlt-count")).toHaveText("1");
  await expect(page.getByTestId("accepted-count")).toHaveText("1");

  await page.getByTestId("reset").click();
  await page.getByTestId("late").click();
  await page.getByRole("link", { name: "Analytics" }).click();
  await expect(page.getByTestId("analytics-count")).toHaveText("1");
  await expect(page.getByText("2026-06-15T10:00:00Z").first()).toBeVisible();
  await expect(page.getByRole("heading", { name: "Temperature" })).toBeVisible();
  await expect(page.getByRole("heading", { name: "Humidity" })).toBeVisible();
  await expect(page.getByRole("heading", { name: "Battery" })).toBeVisible();

  await page.getByRole("link", { name: "Architecture" }).click();
  await expect(page.getByRole("link", { name: "Outbox publisher" })).toHaveAttribute("href", /OutboxPublisher\.java$/);
  await page.getByRole("link", { name: "Pipeline", exact: true }).click();
  await expect(page.getByTestId("accepted-count")).toHaveText("1");
  await page.getByTestId("reset").click();
  await expect(page.getByTestId("accepted-count")).toHaveText("0");
  assertIsolated(urls);
});

test("reloads analytics and architecture directly", async ({ page }) => {
  const urls = watch(page);
  await page.goto("/analytics");
  await expect(page.getByRole("heading", { name: "Analytics from accepted readings" })).toBeVisible();
  await page.goto("/architecture");
  await expect(page.getByRole("heading", { name: "What the repository actually runs" })).toBeVisible();
  await page.goto("/missing-showcase-page");
  await expect(page.getByRole("heading", { name: "Page not found" })).toBeVisible();
  assertIsolated(urls);
});

test("keeps the pipeline readable from a phone width through a laptop", async ({ page }) => {
  const widths = [
    [320, 720],
    [360, 800],
    [390, 844],
    [768, 1024],
    [820, 1180],
    [1024, 800],
    [1100, 800],
    [1280, 800],
  ] as const;
  for (const [width, height] of widths) {
    await page.setViewportSize({ width, height });
    await page.goto("/");
    await page.getByTestId("pause").click();
    await page.getByTestId("send-one").click();
    const layout = await page.evaluate(() => {
      const box = (selector: string) => {
        const element = document.querySelector(selector);
        if (!element) {
          return null;
        }
        const rect = element.getBoundingClientRect();
        return { top: rect.top, bottom: rect.bottom, width: rect.width, scroll: element.scrollWidth, client: element.clientWidth };
      };
      const stages = [...document.querySelectorAll(".sf-rail li")].map((element) => {
        const label = element.querySelector(".sf-node-label");
        const value = element.querySelector(".sf-node-value");
        return {
          text: (element.textContent ?? "").replace(/\s+/g, " ").trim(),
          width: element.getBoundingClientRect().width,
          labelClipped: label ? label.scrollWidth > label.clientWidth + 1 : true,
          valueClipped: value ? value.scrollWidth > value.clientWidth + 1 : true,
        };
      });
      const headline = document.querySelector("h1")?.getBoundingClientRect();
      const controls = document.querySelector(".sf-controls")?.getBoundingClientRect();
      return {
        overflow: document.documentElement.scrollWidth - document.documentElement.clientWidth,
        stages,
        headlineBottom: headline?.bottom ?? 0,
        controlsTop: controls?.top ?? 0,
        send: box("[data-testid='send-one']"),
      };
    });
    expect(layout.overflow, `${width}px document overflow`).toBeLessThanOrEqual(1);
    expect(layout.stages, `${width}px stage count`).toHaveLength(6);
    expect(layout.controlsTop, `${width}px headline overlap`).toBeGreaterThanOrEqual(layout.headlineBottom - 1);
    expect(layout.send?.width ?? 0, `${width}px send button`).toBeGreaterThan(40);
    for (const stage of layout.stages) {
      expect(stage.width, `${width}px ${stage.text}`).toBeGreaterThan(96);
      expect(stage.labelClipped, `${width}px label ${stage.text}`).toBe(false);
      expect(stage.valueClipped, `${width}px value ${stage.text}`).toBe(false);
    }
    await expect(page.getByRole("link", { name: "Analytics" })).toBeVisible();
    await expect(page.getByRole("link", { name: "Architecture" })).toBeVisible();
    await expect(page.getByTestId("broker")).toBeVisible();
  }
  await page.setViewportSize({ width: 768, height: 1024 });
  await page.goto("/analytics");
  await expect(page.getByText("Selected device and time range")).toBeVisible();
  await page.goto("/architecture");
  await expect(page.getByRole("heading", { name: "What the repository actually runs" })).toBeVisible();
});

test("keeps stage text when motion is reduced", async ({ browser }) => {
  const context = await browser.newContext({ reducedMotion: "reduce", viewport: { width: 1280, height: 800 } });
  const page = await context.newPage();
  await page.goto("/");
  await page.getByTestId("pause").click();
  await page.getByTestId("send-one").click();
  await expect(page.locator("[data-motion='reduce']")).toBeVisible();
  await expect(page.getByText("Pending").first()).toBeVisible();
  await context.close();
});
