import { cleanup, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";
import { INVALID_KEY_MESSAGE } from "../auth/Session";
import { installFetch, jsonResponse, renderApp } from "./renderApp";

const summaryBody = {
  deviceId: "alpha",
  from: "2026-10-14T14:00:00Z",
  to: "2026-10-15T14:00:00Z",
  readingCount: 2,
  temperatureMin: 0,
  temperatureMax: 21.705,
  temperatureAvg: 21.705,
  humidityAvg: JSON.parse("21.700") as number,
  batteryAvg: 0.001,
  latest: {
    eventId: "00000000-0000-4000-8000-000000000001",
    occurredAt: "2026-10-15T13:00:00Z",
    receivedAt: "2026-10-15T13:00:01Z",
    temperatureC: 21.705,
    humidityPct: -0.002,
    batteryPct: 87,
  },
  history: {
    earliestOccurredAt: "2026-10-01T00:00:00Z",
    latestOccurredAt: "2026-10-15T13:00:00Z",
    latestReceivedAt: "2026-10-15T13:05:00Z",
  },
  publicationBacklog: 3,
  processingBacklog: 4,
  windowOpen: true,
};

function seriesBody(granularity: "hour" | "day" = "hour") {
  return {
    deviceId: "alpha",
    from: summaryBody.from,
    to: summaryBody.to,
    granularity,
    windowOpen: true,
    buckets: [
      {
        bucketStart: "2026-10-15T13:00:00Z",
        bucketEnd: "2026-10-15T14:00:00Z",
        readingCount: 2,
        temperatureMin: 0,
        temperatureMax: 21.705,
        temperatureAvg: 21.705,
        humidityAvg: 48.2,
        batteryAvg: 0.001,
      },
    ],
  };
}

function readingsBody(cursor: string | null, nextCursor: string | null) {
  return {
    deviceId: "alpha",
    from: summaryBody.from,
    to: summaryBody.to,
    readings: [
      {
        eventId: cursor ? "00000000-0000-4000-8000-000000000002" : "00000000-0000-4000-8000-000000000001",
        occurredAt: "2026-10-15T13:00:00Z",
        receivedAt: "2026-10-15T13:00:01Z",
        temperatureC: 21.705,
        humidityPct: 48.2,
        batteryPct: 87,
      },
    ],
    nextCursor,
  };
}

function route(url: string, init?: RequestInit): Promise<Response> {
  if (url.includes("/actuator/health")) {
    return jsonResponse({ status: "UP" });
  }
  if (url.includes("/readings")) {
    const cursor = new URL(url, "http://local").searchParams.get("cursor");
    return jsonResponse(readingsBody(cursor, cursor ? null : "next-token"));
  }
  if (url.includes("/summary")) {
    return jsonResponse(summaryBody);
  }
  if (url.includes("/series")) {
    const granularity = new URL(url, "http://local").searchParams.get("granularity") === "day" ? "day" : "hour";
    return jsonResponse(seriesBody(granularity));
  }
  if (url.includes("/api/v1/devices") && init?.method === "POST") {
    const body = JSON.parse(String(init.body)) as { deviceId: string };
    if (body.deviceId === "taken") {
      return jsonResponse({ title: "Conflict", detail: "Device ID is already registered." }, 409);
    }
    return jsonResponse({ deviceId: body.deviceId, createdAt: "2026-10-15T00:00:00Z" }, 201);
  }
  if (url.includes("/api/v1/devices")) {
    const offset = Number(new URL(url, "http://local").searchParams.get("offset") ?? "0");
    const devices = Array.from({ length: 100 }, (_, index) => ({
      deviceId: `device-${offset + index}`,
      createdAt: "2026-10-01T00:00:00Z",
    }));
    return jsonResponse({ devices, limit: 100, offset });
  }
  return jsonResponse({ title: "Not Found" }, 404);
}

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
  localStorage.clear();
  sessionStorage.clear();
  Object.defineProperty(document, "visibilityState", { configurable: true, value: "visible" });
});

function bearer(init?: RequestInit): string {
  return new Headers(init?.headers).get("Authorization") ?? "";
}

async function resumeVisibleWindow() {
  Object.defineProperty(document, "visibilityState", { configurable: true, value: "hidden" });
  document.dispatchEvent(new Event("visibilitychange"));
  await new Promise((resolve) => setTimeout(resolve, 5));
  Object.defineProperty(document, "visibilityState", { configurable: true, value: "visible" });
  document.dispatchEvent(new Event("visibilitychange"));
}

describe("dashboard", () => {
  it("renders a paged device list and registers a device", async () => {
    const user = userEvent.setup();
    const calls = installFetch(route);
    renderApp("/devices");
    expect(await screen.findByRole("link", { name: /device-0/ })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Next devices" })).toBeEnabled();
    await user.click(screen.getByRole("button", { name: "Next devices" }));
    await waitFor(() => {
      expect(calls.some((call) => call.url.includes("offset=100"))).toBe(true);
    });
    await user.type(screen.getByLabelText("Device ID"), "sensor-042");
    await user.click(screen.getByRole("button", { name: "Register device" }));
    expect(await screen.findByText("sensor-042 is registered.")).toBeInTheDocument();
    const created = calls.find((call) => call.init?.method === "POST");
    expect(created?.url).toBe("/api/v1/devices");
    expect(created?.init?.headers && new Headers(created.init.headers).get("Authorization")).toBe("Bearer memory-admin-key");
  });

  it("shows a duplicate device error from the API", async () => {
    const user = userEvent.setup();
    installFetch(route);
    renderApp("/devices");
    await screen.findByRole("link", { name: /device-0/ });
    await user.type(screen.getByLabelText("Device ID"), "taken");
    await user.click(screen.getByRole("button", { name: "Register device" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("already registered");
  });

  it("selects a device from the list", async () => {
    installFetch(route);
    renderApp("/devices");
    const link = await screen.findByRole("link", { name: /device-0/ });
    expect(link).toHaveAttribute("href", "/devices/device-0");
  });

  it("renders summary cards, units, backlog meaning, and chart labels", async () => {
    installFetch(route);
    renderApp("/devices/alpha");
    expect(await screen.findByRole("heading", { name: "Accepted in this window" })).toBeInTheDocument();
    expect(screen.getAllByText(/21\.705 °C/).length).toBeGreaterThan(0);
    expect(screen.getAllByText("21.7 %").length).toBeGreaterThan(0);
    expect(screen.getAllByText("0.001 %").length).toBeGreaterThan(0);
    expect(screen.getByRole("heading", { name: "Latest event in this window" })).toBeInTheDocument();
    expect(screen.getByRole("heading", { name: "Latest accepted at" })).toBeInTheDocument();
    expect(screen.getByText(/not a processing time/i)).toBeInTheDocument();
    expect(screen.getByRole("heading", { name: "Pending publication" })).toBeInTheDocument();
    expect(screen.getByText(/Failed publication rows are not included/)).toBeInTheDocument();
    expect(screen.getByRole("heading", { name: "Awaiting processing" })).toBeInTheDocument();
    expect(screen.getByText(/do not yet have a processing receipt/)).toBeInTheDocument();
    expect(screen.getAllByText(/UTC hours/).length).toBeGreaterThan(0);
    expect(screen.getAllByRole("heading", { name: "Temperature" }).length).toBeGreaterThan(0);
    expect(screen.getAllByRole("heading", { name: "Humidity" }).length).toBeGreaterThan(0);
    expect(screen.getAllByRole("heading", { name: "Battery" }).length).toBeGreaterThan(0);
    expect(screen.getAllByText("Show bucket values").map((node) => node.getAttribute("aria-label"))).toEqual([
      "Show Temperature bucket values",
      "Show Humidity bucket values",
      "Show Battery bucket values",
    ]);
  });

  it("renders null averages as an em dash and an empty device without a chart", async () => {
    installFetch((url, init) => {
      if (url.includes("/summary")) {
        return jsonResponse({
          ...summaryBody,
          readingCount: 0,
          temperatureMin: null,
          temperatureMax: null,
          temperatureAvg: null,
          humidityAvg: null,
          batteryAvg: null,
          latest: null,
        });
      }
      return route(url, init);
    });
    renderApp("/devices/alpha");
    expect(await screen.findByRole("heading", { name: "No accepted readings in this window" })).toBeInTheDocument();
    expect(screen.getAllByText("—").length).toBeGreaterThan(0);
    expect(screen.queryByText("Show bucket values")).not.toBeInTheDocument();
  });

  it("uses one pair of UTC bounds for summary and series and switches granularity", async () => {
    const user = userEvent.setup();
    const calls = installFetch(route);
    renderApp("/devices/alpha");
    await screen.findByRole("heading", { name: "Overview" });
    await waitFor(() => {
      expect(calls.some((call) => call.url.includes("/summary"))).toBe(true);
      expect(calls.some((call) => call.url.includes("/series"))).toBe(true);
    });
    const summaryUrl = calls.find((call) => call.url.includes("/summary"))?.url ?? "";
    const seriesUrl = calls.find((call) => call.url.includes("/series"))?.url ?? "";
    const summaryParams = new URL(summaryUrl, "http://local").searchParams;
    const seriesParams = new URL(seriesUrl, "http://local").searchParams;
    expect(summaryParams.get("from")).toBe(seriesParams.get("from"));
    expect(summaryParams.get("to")).toBe(seriesParams.get("to"));
    expect(seriesParams.get("granularity")).toBe("hour");
    await user.click(screen.getByRole("button", { name: "Day" }));
    await waitFor(() => {
      expect(calls.some((call) => call.url.includes("granularity=day"))).toBe(true);
    });
  });

  it("keeps a custom range fixed across refresh", async () => {
    const user = userEvent.setup();
    const calls = installFetch(route);
    renderApp("/devices/alpha");
    await screen.findByRole("heading", { name: "Overview" });
    await user.click(screen.getByRole("button", { name: "Custom range" }));
    await user.type(screen.getByLabelText("Start"), "2026-01-01T00:00");
    await user.type(screen.getByLabelText("End"), "2026-01-02T00:00");
    await waitFor(() => {
      const url = calls.filter((call) => call.url.includes("/summary")).at(-1)?.url ?? "";
      expect(url).toContain("from=2026-01-01T");
    });
    const before = calls.filter((call) => call.url.includes("/summary")).at(-1)?.url ?? "";
    await user.click(screen.getByRole("button", { name: "Refresh" }));
    await waitFor(() => {
      const urls = calls.filter((call) => call.url.includes("/summary"));
      expect(urls.length).toBeGreaterThan(1);
    });
    const after = calls.filter((call) => call.url.includes("/summary")).at(-1)?.url ?? "";
    expect(new URL(before, "http://local").searchParams.get("from")).toBe(
      new URL(after, "http://local").searchParams.get("from"),
    );
    expect(new URL(before, "http://local").searchParams.get("to")).toBe(
      new URL(after, "http://local").searchParams.get("to"),
    );
  });

  it("does not show the previous device count while the next device is loading", async () => {
    const user = userEvent.setup();
    installFetch((url, init) => {
      if (url.includes("/devices/beta/")) {
        return new Promise(() => undefined);
      }
      if (url.includes("/summary")) {
        return jsonResponse({ ...summaryBody, readingCount: 42 });
      }
      return route(url, init);
    });
    renderApp("/devices/alpha");
    expect(await screen.findByText("42")).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Switch device" }));
    expect(await screen.findByText("beta")).toBeInTheDocument();
    expect(screen.queryByText("42")).not.toBeInTheDocument();
  });

  it("cancels the in-flight request when the device changes", async () => {
    const user = userEvent.setup();
    const signals: AbortSignal[] = [];
    installFetch((url, init) => {
      if (url.includes("/summary")) {
        signals.push(init?.signal as AbortSignal);
        return new Promise(() => undefined);
      }
      if (url.includes("/series")) {
        return new Promise(() => undefined);
      }
      return route(url, init);
    });
    renderApp("/devices/alpha");
    await waitFor(() => expect(signals.length).toBeGreaterThan(0));
    const first = signals[0];
    await user.click(screen.getByRole("button", { name: "Switch device" }));
    await waitFor(() => expect(first?.aborted).toBe(true));
  });

  it("pages readings with the opaque cursor and hides Next on the last page", async () => {
    const user = userEvent.setup();
    const calls = installFetch(route);
    renderApp("/devices/alpha/readings");
    expect(await screen.findByRole("button", { name: "Next page" })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "First page" })).toBeDisabled();
    await user.click(screen.getByRole("button", { name: "Next page" }));
    await waitFor(() => expect(calls.some((call) => call.url.includes("cursor=next-token"))).toBe(true));
    expect(await screen.findByText("No further page")).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Next page" })).not.toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "First page" }));
    expect(await screen.findByRole("button", { name: "Next page" })).toBeInTheDocument();
  });

  it("keeps the admin key in memory and clears protected data on sign-out", async () => {
    const user = userEvent.setup();
    installFetch(route);
    renderApp("/devices", false);
    const input = await screen.findByLabelText("Device admin key");
    expect(input).toHaveAttribute("type", "password");
    await user.type(input, "memory-admin-key");
    await user.click(screen.getByRole("button", { name: "Continue" }));
    expect(await screen.findByRole("heading", { name: "Devices" })).toBeInTheDocument();
    expect(localStorage.length).toBe(0);
    expect(sessionStorage.length).toBe(0);
    await user.click(screen.getByRole("button", { name: "Sign out" }));
    expect(await screen.findByLabelText("Device admin key")).toHaveValue("");
    expect(screen.queryByText(INVALID_KEY_MESSAGE)).not.toBeInTheDocument();
    expect(screen.queryByRole("link", { name: /device-0/ })).not.toBeInTheDocument();
    expect(localStorage.length).toBe(0);
    expect(sessionStorage.length).toBe(0);
  });

  it("returns to key entry on 401 and explains 403, 404, and an unreachable API", async () => {
    installFetch((url) => {
      if (url.includes("/summary")) {
        return jsonResponse({ title: "Unauthorized", detail: "Authentication is required." }, 401);
      }
      return jsonResponse({ status: "UP" });
    });
    renderApp("/devices/alpha");
    expect(await screen.findByLabelText("Device admin key")).toBeInTheDocument();
    expect(screen.getByRole("alert")).toHaveTextContent(INVALID_KEY_MESSAGE);
    expect(screen.queryByText("Authentication is required.")).not.toBeInTheDocument();

    cleanup();
    installFetch((url) => {
      if (url.includes("/summary")) {
        return jsonResponse({ title: "Forbidden", detail: "The credential is not authorized for this operation." }, 403);
      }
      if (url.includes("/series")) {
        return jsonResponse({ title: "Forbidden" }, 403);
      }
      return jsonResponse({ status: "UP" });
    });
    renderApp("/devices/alpha");
    expect((await screen.findAllByRole("alert")).some((node) => /cannot view devices or analytics/.test(node.textContent ?? ""))).toBe(true);
    expect(screen.queryByText(INVALID_KEY_MESSAGE)).not.toBeInTheDocument();
    expect(screen.queryByLabelText("Device admin key")).not.toBeInTheDocument();

    cleanup();
    installFetch((url) => {
      if (url.includes("/summary") || url.includes("/series")) {
        return jsonResponse({ title: "Not Found", detail: "Device was not found." }, 404);
      }
      return jsonResponse({ status: "UP" });
    });
    renderApp("/devices/missing");
    await waitFor(() => {
      expect(screen.getAllByRole("alert").filter((node) => /Device was not found/.test(node.textContent ?? ""))).toHaveLength(1);
    });

    cleanup();
    installFetch(() => Promise.reject(new TypeError("network")));
    renderApp("/devices");
    expect((await screen.findAllByText(/unreachable/i)).length).toBeGreaterThan(0);
  });

  it("explains an invalid key and keeps the form ready for another attempt", async () => {
    const user = userEvent.setup();
    let deviceAttempts = 0;
    const calls = installFetch((url, init) => {
      if (url.includes("/api/v1/devices")) {
        deviceAttempts += 1;
        if (deviceAttempts === 1) {
          return jsonResponse({ title: "Unauthorized", detail: init?.body ? String(init.body) : "Authentication is required." }, 401);
        }
        return jsonResponse({ devices: [], limit: 100, offset: 0 });
      }
      return jsonResponse({ status: "UP" });
    });
    renderApp("/devices", false);
    await user.type(await screen.findByLabelText("Device admin key"), "wrong-key-value");
    await user.click(screen.getByRole("button", { name: "Continue" }));
    expect(await screen.findByRole("alert")).toHaveTextContent(INVALID_KEY_MESSAGE);
    expect(screen.getByLabelText("Device admin key")).toHaveValue("");
    expect(screen.getByRole("button", { name: "Continue" })).toBeEnabled();
    expect(screen.queryByText("wrong-key-value")).not.toBeInTheDocument();
    expect(calls.some((call) => call.url.includes("wrong-key-value"))).toBe(false);
    await user.type(screen.getByLabelText("Device admin key"), "right-key-value");
    await user.click(screen.getByRole("button", { name: "Continue" }));
    expect(await screen.findByRole("heading", { name: "Devices" })).toBeInTheDocument();
    expect(screen.queryByText(INVALID_KEY_MESSAGE)).not.toBeInTheDocument();
    expect(localStorage.length).toBe(0);
    expect(sessionStorage.length).toBe(0);
  });

  it("does not show a signed-out session's delayed response after a new sign-in", async () => {
    const user = userEvent.setup();
    let releaseFirst: (response: Response) => void = () => undefined;
    const firstSummary = new Promise<Response>((resolve) => {
      releaseFirst = resolve;
    });
    let summaryCalls = 0;
    const calls = installFetch((url) => {
      if (url.includes("/summary")) {
        summaryCalls += 1;
        if (summaryCalls === 1) {
          return firstSummary;
        }
        return jsonResponse({ ...summaryBody, readingCount: 81 });
      }
      if (url.includes("/series")) {
        return jsonResponse(seriesBody());
      }
      return jsonResponse({ status: "UP" });
    });
    renderApp("/devices/alpha", false);
    await user.type(await screen.findByLabelText("Device admin key"), "session-a-key");
    await user.click(screen.getByRole("button", { name: "Continue" }));
    await waitFor(() => expect(summaryCalls).toBe(1));
    await user.click(screen.getByRole("button", { name: "Sign out" }));
    expect(screen.queryByTestId("accepted-in-this-window")).not.toBeInTheDocument();
    await user.type(screen.getByLabelText("Device admin key"), "session-b-key");
    await user.click(screen.getByRole("button", { name: "Continue" }));
    expect(await screen.findByTestId("accepted-in-this-window")).toHaveTextContent("81");
    releaseFirst(
      new Response(JSON.stringify({ ...summaryBody, readingCount: 19 }), {
        status: 200,
        headers: { "Content-Type": "application/json" },
      }),
    );
    await new Promise((resolve) => setTimeout(resolve, 50));
    expect(screen.getByTestId("accepted-in-this-window")).toHaveTextContent("81");
    expect(screen.queryByText("19")).not.toBeInTheDocument();
    const summaryCallsMade = calls.filter((call) => call.url.includes("/summary"));
    expect(bearer(summaryCallsMade[0]?.init)).toBe("Bearer session-a-key");
    expect(bearer(summaryCallsMade[1]?.init)).toBe("Bearer session-b-key");
    expect(calls.some((call) => call.url.includes("session-a-key") || call.url.includes("session-b-key"))).toBe(false);
    expect(localStorage.length).toBe(0);
    expect(sessionStorage.length).toBe(0);
  });

  it("keeps a readings cursor on the window that issued it until refresh or a new filter", async () => {
    const user = userEvent.setup();
    const calls = installFetch(route);
    renderApp("/devices/alpha/readings");
    expect(await screen.findByRole("button", { name: "Next page" })).toBeInTheDocument();
    const opened = () => calls.filter((call) => call.url.includes("/readings"));
    const firstTo = new URL(opened()[0]?.url ?? "", "http://local").searchParams.get("to");

    await resumeVisibleWindow();
    await waitFor(() => {
      const latest = opened().at(-1);
      expect(new URL(latest?.url ?? "", "http://local").searchParams.get("to")).not.toBe(firstTo);
      expect(latest?.url).not.toContain("cursor=");
    });
    const rolledTo = new URL(opened().at(-1)?.url ?? "", "http://local").searchParams.get("to");

    await user.click(screen.getByRole("button", { name: "Next page" }));
    await waitFor(() => expect(opened().some((call) => call.url.includes("cursor=next-token"))).toBe(true));
    const paged = opened().at(-1);
    expect(new URL(paged?.url ?? "", "http://local").searchParams.get("to")).toBe(rolledTo);
    const callsWhilePaged = opened().length;

    await resumeVisibleWindow();
    await new Promise((resolve) => setTimeout(resolve, 30));
    expect(opened().length).toBe(callsWhilePaged);
    expect(screen.getByText("00000000-0000-4000-8000-000000000002")).toBeInTheDocument();

    await user.click(screen.getByRole("button", { name: "Refresh" }));
    await waitFor(() => {
      const latest = opened().at(-1);
      const params = new URL(latest?.url ?? "", "http://local").searchParams;
      expect(params.get("cursor")).toBeNull();
      expect(params.get("to")).not.toBe(rolledTo);
    });
    expect(screen.getByRole("button", { name: "First page" })).toBeDisabled();

    await user.click(screen.getByRole("button", { name: "Next page" }));
    await screen.findByText("No further page");
    await user.click(screen.getByRole("button", { name: "Last 7 days" }));
    await waitFor(() => {
      const latest = opened().at(-1);
      const params = new URL(latest?.url ?? "", "http://local").searchParams;
      expect(params.get("cursor")).toBeNull();
      expect(params.get("from")).not.toBe(new URL(paged?.url ?? "", "http://local").searchParams.get("from"));
    });
    expect(screen.getByRole("button", { name: "First page" })).toBeDisabled();
  });

  it("shows a loading state before analytics arrive", async () => {
    installFetch((url) => {
      if (url.includes("/summary") || url.includes("/series")) {
        return new Promise(() => undefined);
      }
      return jsonResponse({ status: "UP" });
    });
    renderApp("/devices/alpha");
    expect(await screen.findByText("Loading analytics")).toBeInTheDocument();
  });

  it("exposes named navigation controls", async () => {
    installFetch(route);
    renderApp("/devices/alpha");
    const nav = await screen.findByRole("navigation", { name: "Primary" });
    expect(within(nav).getByRole("link", { name: "Devices" })).toBeInTheDocument();
    expect(within(nav).getByRole("link", { name: "Overview" })).toBeInTheDocument();
    expect(within(nav).getByRole("link", { name: "Readings" })).toBeInTheDocument();
  });
});

