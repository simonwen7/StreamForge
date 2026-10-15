import { useEffect, useState } from "react";
import { NavLink, Outlet } from "react-router";

function useReducedMotion(): boolean {
  const [reduced, setReduced] = useState(false);
  useEffect(() => {
    if (typeof window.matchMedia !== "function") {
      return;
    }
    const query = window.matchMedia("(prefers-reduced-motion: reduce)");
    const apply = () => setReduced(query.matches);
    apply();
    query.addEventListener("change", apply);
    return () => query.removeEventListener("change", apply);
  }, []);
  return reduced;
}

export function ShowcaseLayout() {
  const reduced = useReducedMotion();
  return (
    <div className="sf-app" data-motion={reduced ? "reduce" : "full"}>
      <header className="sf-header">
        <NavLink to="/" className="sf-brand">
          <span className="sf-mark" aria-hidden="true">SF</span>
          <span>
            <span className="sf-brand-name">StreamForge</span>
            <span className="sf-brand-sub">Event processing</span>
          </span>
        </NavLink>
        <nav className="sf-nav" aria-label="Showcase">
          <NavLink to="/" end>Pipeline</NavLink>
          <NavLink to="/analytics">Analytics</NavLink>
          <NavLink to="/architecture">Architecture</NavLink>
        </nav>
        <p className="sf-chip">Browser-local simulation</p>
      </header>
      <main className="sf-main">
        <Outlet />
      </main>
    </div>
  );
}

export function NotFound() {
  return (
    <section className="sf-panel">
      <h1>Page not found</h1>
      <p>This showcase has the pipeline, analytics, and architecture pages.</p>
      <a className="sf-button" href="/">Back to the pipeline</a>
    </section>
  );
}
