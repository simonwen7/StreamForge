import { lazy, Suspense } from "react";
import { BrowserRouter, Route, Routes } from "react-router";
import { NotFound, ShowcaseLayout } from "./ShowcaseLayout";
import { PipelinePage } from "./PipelinePage";
import { ShowcaseProvider } from "./ShowcaseProvider";

const AnalyticsPage = lazy(() => import("./AnalyticsPage").then((module) => ({ default: module.AnalyticsPage })));
const ArchitecturePage = lazy(() => import("./ArchitecturePage").then((module) => ({ default: module.ArchitecturePage })));

export function ShowcaseRoutes() {
  return (
    <Routes>
      <Route element={<ShowcaseLayout />}>
        <Route path="/" element={<PipelinePage />} />
        <Route path="/analytics" element={<Suspense fallback={<p className="sf-empty">Loading analytics</p>}><AnalyticsPage /></Suspense>} />
        <Route path="/architecture" element={<Suspense fallback={<p className="sf-empty">Loading architecture</p>}><ArchitecturePage /></Suspense>} />
        <Route path="*" element={<NotFound />} />
      </Route>
    </Routes>
  );
}

export function ShowcaseApp() {
  return (
    <ShowcaseProvider>
      <BrowserRouter>
        <ShowcaseRoutes />
      </BrowserRouter>
    </ShowcaseProvider>
  );
}
