import { QueryClientProvider } from "@tanstack/react-query";
import { render, type RenderResult } from "@testing-library/react";
import { useEffect, useRef } from "react";
import { vi } from "vitest";
import { MemoryRouter, Route, Routes, useNavigate } from "react-router";
import { createQueryClient } from "../app/App";
import { Shell } from "../app/Shell";
import { SessionProvider, useSession } from "../auth/Session";
import { DevicesPage } from "../pages/DevicesPage";
import { OverviewPage } from "../pages/OverviewPage";
import { ReadingsPage } from "../pages/ReadingsPage";

export function renderApp(path = "/devices", signedIn = true): RenderResult {
  const client = createQueryClient();
  client.setDefaultOptions({ queries: { retry: false } });
  return render(
    <QueryClientProvider client={client}>
      <SessionProvider>
        {signedIn ? <SignInOnMount /> : null}
        <MemoryRouter initialEntries={[path]}>
          <Routes>
            <Route element={<Shell />}>
              <Route path="/devices" element={<DevicesPage />} />
              <Route path="/devices/:deviceId" element={<OverviewPage />} />
              <Route path="/devices/:deviceId/readings" element={<ReadingsPage />} />
            </Route>
          </Routes>
          <Jump />
        </MemoryRouter>
      </SessionProvider>
    </QueryClientProvider>,
  );
}

function SignInOnMount() {
  const { signIn } = useSession();
  const started = useRef(false);
  useEffect(() => {
    if (!started.current) {
      started.current = true;
      signIn("memory-admin-key");
    }
  }, [signIn]);
  return null;
}

function Jump() {
  const navigate = useNavigate();
  return (
    <button type="button" onClick={() => navigate("/devices/beta")}>
      Switch device
    </button>
  );
}

export function jsonResponse(body: unknown, status = 200): Promise<Response> {
  return Promise.resolve(
    new Response(JSON.stringify(body), {
      status,
      headers: { "Content-Type": "application/problem+json" },
    }),
  );
}

export function installFetch(handler: (url: string, init?: RequestInit) => Promise<Response> | Response) {
  const calls: { url: string; init?: RequestInit }[] = [];
  vi.stubGlobal("fetch", (input: RequestInfo | URL, init?: RequestInit) => {
    const url = String(input);
    calls.push({ url, init });
    return handler(url, init);
  });
  return calls;
}

