import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { lazy, Suspense, useState } from "react";
import { BrowserRouter, Navigate, Route, Routes } from "react-router";
import { ApiError } from "../api/client";
import { SessionProvider } from "../auth/Session";
import { LoadingBlock } from "../components/States";
import { DevicesPage } from "../pages/DevicesPage";
import { Shell } from "./Shell";

const OverviewPage = lazy(() => import("../pages/OverviewPage").then((module) => ({ default: module.OverviewPage })));
const ReadingsPage = lazy(() => import("../pages/ReadingsPage").then((module) => ({ default: module.ReadingsPage })));

export function createQueryClient() {
  return new QueryClient({
    defaultOptions: {
      queries: {
        retry: (count, error) => {
          if (error instanceof ApiError && [400, 401, 403, 404].includes(error.status)) {
            return false;
          }
          return count < 2;
        },
        refetchOnWindowFocus: false,
        staleTime: 5_000,
      },
    },
  });
}

export function App() {
  const [queryClient] = useState(createQueryClient);
  return (
    <QueryClientProvider client={queryClient}>
      <SessionProvider>
        <BrowserRouter>
          <Routes>
            <Route element={<Shell />}>
              <Route path="/" element={<Navigate to="/devices" replace />} />
              <Route path="/devices" element={<DevicesPage />} />
              <Route path="/devices/:deviceId" element={<Suspense fallback={<LoadingBlock label="Loading overview" />}><OverviewPage /></Suspense>} />
              <Route path="/devices/:deviceId/readings" element={<Suspense fallback={<LoadingBlock label="Loading readings" />}><ReadingsPage /></Suspense>} />
              <Route path="*" element={<Navigate to="/devices" replace />} />
            </Route>
          </Routes>
        </BrowserRouter>
      </SessionProvider>
    </QueryClientProvider>
  );
}
