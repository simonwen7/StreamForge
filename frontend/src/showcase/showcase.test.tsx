import { cleanup, render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter } from "react-router";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { ShowcaseRoutes } from "./ShowcaseApp";
import { ShowcaseProvider } from "./ShowcaseProvider";

function renderAt(path = "/") {
  return render(
    <ShowcaseProvider>
      <MemoryRouter initialEntries={[path]}>
        <ShowcaseRoutes />
      </MemoryRouter>
    </ShowcaseProvider>,
  );
}

describe("showcase", () => {
  afterEach(() => {
    cleanup();
  });

  beforeEach(() => {
    vi.stubGlobal("matchMedia", (query: string) => ({
      matches: query.includes("reduce"),
      addEventListener: () => undefined,
      removeEventListener: () => undefined,
    }));
  });

  it("lands on the pipeline without a key and discloses the simulation", () => {
    renderAt();
    expect(screen.getByRole("heading", { name: /survive ingestion/i })).toBeInTheDocument();
    expect(screen.getByText("Browser-local simulation")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Send events" })).toBeInTheDocument();
    expect(screen.queryByLabelText(/device admin key/i)).not.toBeInTheDocument();
    expect(screen.getByTestId("accepted-count")).toHaveTextContent("0");
  });

  it("sends, steps, pauses, and shows a receipt after the outbox publishes", async () => {
    const user = userEvent.setup();
    renderAt();
    await user.click(screen.getByTestId("pause"));
    await user.click(screen.getByTestId("send-one"));
    expect(screen.getByTestId("accepted-count")).toHaveTextContent("1");
    expect(screen.getByTestId("receipt-count")).toHaveTextContent("0");
    expect(screen.getAllByText("Pending").length).toBeGreaterThan(0);
    await user.click(screen.getByTestId("step"));
    await user.click(screen.getByTestId("step"));
    expect(screen.getByTestId("receipt-count")).toHaveTextContent("1");
    expect(screen.getByRole("button", { name: /sf-evt-/ })).toBeInTheDocument();
    expect(screen.getByLabelText("Transition timeline").textContent).toMatch(/Accepted/);
    expect(screen.getByLabelText("Transition timeline").textContent).toMatch(/Receipt recorded/);
  });

  it("keeps accepted rows when the broker is paused and drains them after restore", async () => {
    const user = userEvent.setup();
    renderAt();
    await user.click(screen.getByTestId("pause"));
    await user.click(screen.getByTestId("broker"));
    expect(screen.getAllByText(/Broker paused/).length).toBeGreaterThan(0);
    await user.click(screen.getByTestId("send-ten"));
    await user.click(screen.getByTestId("step"));
    await user.click(screen.getByTestId("step"));
    expect(screen.getByTestId("accepted-count")).toHaveTextContent("10");
    expect(screen.getByTestId("pending-count")).toHaveTextContent("10");
    await user.click(screen.getByTestId("broker"));
    await user.click(screen.getByTestId("step"));
    await user.click(screen.getByTestId("step"));
    await user.click(screen.getByTestId("step"));
    await user.click(screen.getByTestId("step"));
    expect(screen.getByTestId("pending-count")).toHaveTextContent("0");
    expect(screen.getByTestId("receipt-count")).toHaveTextContent("10");
  });

  it("stops receipts while the worker is stopped", async () => {
    const user = userEvent.setup();
    renderAt();
    await user.click(screen.getByTestId("pause"));
    await user.click(screen.getByTestId("worker"));
    await user.click(screen.getByTestId("send-one"));
    await user.click(screen.getByTestId("step"));
    await user.click(screen.getByTestId("step"));
    expect(screen.getByTestId("awaiting-count")).toHaveTextContent("1");
    expect(screen.getByTestId("receipt-count")).toHaveTextContent("0");
    await user.click(screen.getByTestId("worker"));
    await user.click(screen.getByTestId("step"));
    expect(screen.getByTestId("receipt-count")).toHaveTextContent("1");
  });

  it("does not double the accepted count when a delivery is replayed", async () => {
    const user = userEvent.setup();
    renderAt();
    await user.click(screen.getByTestId("pause"));
    await user.click(screen.getByTestId("send-one"));
    await user.click(screen.getByTestId("step"));
    await user.click(screen.getByTestId("step"));
    await user.click(screen.getByRole("button", { name: /sf-evt-/ }));
    await user.click(screen.getByTestId("replay"));
    await user.click(screen.getByTestId("step"));
    expect(screen.getByTestId("accepted-count")).toHaveTextContent("1");
    expect(screen.getByTestId("duplicate-count")).toHaveTextContent("1");
    expect(screen.getByText(/not exactly-once/i)).toBeInTheDocument();
  });

  it("records a dead letter for a poison delivery", async () => {
    const user = userEvent.setup();
    renderAt();
    await user.click(screen.getByTestId("pause"));
    await user.click(screen.getByTestId("send-one"));
    await user.click(screen.getByTestId("step"));
    await user.click(screen.getByTestId("poison"));
    await user.click(screen.getByTestId("step"));
    expect(screen.getByTestId("dlt-count")).toHaveTextContent("1");
    expect(screen.getByTestId("accepted-count")).toHaveTextContent("1");
    expect(screen.getByText(/crash between those steps/i)).toBeInTheDocument();
  });

  it("shows a late reading in an earlier analytics bucket and keeps route state", async () => {
    const user = userEvent.setup();
    renderAt();
    await user.click(screen.getByTestId("pause"));
    await user.click(screen.getByTestId("late"));
    expect(screen.getByTestId("accepted-count")).toHaveTextContent("1");
    await user.click(screen.getByRole("link", { name: "Analytics" }));
    expect(await screen.findByRole("heading", { name: "Analytics from accepted readings" })).toBeInTheDocument();
    expect(screen.getByText("Selected device and time range")).toBeInTheDocument();
    expect(screen.getByText("Every device in this browser run")).toBeInTheDocument();
    expect(screen.getByTestId("analytics-count")).toHaveTextContent("1");
    expect(screen.getByRole("table", { name: /Newest occurredAt/ }).textContent).toContain("2026-06-15T10:00:00Z");
    expect(screen.getByRole("table", { name: /Newest occurredAt/ }).textContent).toContain("2026-06-15T12:00:00Z");
    await user.click(screen.getByRole("link", { name: "Pipeline" }));
    expect(screen.getByTestId("accepted-count")).toHaveTextContent("1");
    await user.click(screen.getByTestId("reset"));
    expect(screen.getByTestId("accepted-count")).toHaveTextContent("0");
  });

  it("marks reduced motion and sends from the keyboard", async () => {
    const user = userEvent.setup();
    renderAt();
    expect(document.querySelector("[data-motion='reduce']")).toBeTruthy();
    screen.getByTestId("send-one").focus();
    await user.keyboard("{Enter}");
    expect(screen.getByTestId("accepted-count")).toHaveTextContent("1");
  });

  it("renders an unknown route without the live sign-in", () => {
    renderAt("/missing");
    expect(screen.getByRole("heading", { name: "Page not found" })).toBeInTheDocument();
    expect(screen.queryByLabelText(/device admin key/i)).not.toBeInTheDocument();
  });
});
