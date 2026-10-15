import { NavLink, Outlet, useParams } from "react-router";
import { useState, type FormEvent } from "react";
import { useHealth } from "../api/queries";
import { useSession } from "../auth/Session";

export function Shell() {
  const { key, authError, signIn, signOut } = useSession();
  const { deviceId } = useParams();
  const health = useHealth();
  const reachable = health.data?.status === "UP";

  return (
    <div className="min-h-screen bg-[#f4f6f8] text-slate-900">
      <div className="h-1 bg-blue-700" />
      <header className="border-b border-slate-200 bg-white">
        <div className="mx-auto flex max-w-6xl flex-wrap items-center gap-x-6 gap-y-3 px-4 py-3">
          <NavLink to="/devices" className="flex items-center gap-2 rounded-lg focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-blue-700">
            <span className="grid h-9 w-9 place-items-center rounded-lg bg-blue-700 text-sm font-semibold text-white">SF</span>
            <span>
              <span className="block text-base font-semibold tracking-tight">StreamForge</span>
              <span className="block text-xs text-slate-500">Telemetry operations</span>
            </span>
          </NavLink>
          <nav aria-label="Primary" className="flex flex-wrap items-center gap-1">
            <PrimaryLink to="/devices">Devices</PrimaryLink>
            {deviceId ? <PrimaryLink to={`/devices/${encodeURIComponent(deviceId)}`}>Overview</PrimaryLink> : null}
            {deviceId ? <PrimaryLink to={`/devices/${encodeURIComponent(deviceId)}/readings`}>Readings</PrimaryLink> : null}
          </nav>
          <div className="ml-auto flex items-center gap-3">
            <p className="text-xs font-medium text-slate-600" role="status" data-testid="api-status">
              <span className={`mr-1.5 inline-block h-2 w-2 rounded-full ${reachable ? "bg-emerald-600" : "bg-slate-400"}`} />
              {health.isPending ? "Checking API" : reachable ? "API reachable" : "API unreachable"}
            </p>
            {key ? (
              <button type="button" className="btn-secondary" onClick={signOut}>
                Sign out
              </button>
            ) : null}
          </div>
        </div>
      </header>
      <main className="mx-auto max-w-6xl px-4 py-6">
        {deviceId ? (
          <p className="mb-4 text-sm text-slate-600">
            Device <span className="font-mono text-slate-900">{deviceId}</span>
          </p>
        ) : null}
        {key ? <Outlet /> : <SignIn onSubmit={signIn} authError={authError} />}
      </main>
    </div>
  );
}

function PrimaryLink({ to, children }: { to: string; children: string }) {
  return (
    <NavLink
      to={to}
      className={({ isActive }) =>
        `rounded-lg px-3 py-2 text-sm font-medium focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-blue-700 ${
          isActive ? "bg-blue-50 text-blue-800" : "text-slate-700 hover:bg-slate-100"
        }`
      }
    >
      {children}
    </NavLink>
  );
}

function SignIn({ onSubmit, authError }: { onSubmit: (key: string) => void; authError: string | null }) {
  const [value, setValue] = useState("");
  const [localError, setLocalError] = useState<string | null>(null);
  const message = localError ?? authError;

  function submit(event: FormEvent) {
    event.preventDefault();
    const next = value.trim();
    if (!next) {
      setLocalError("Enter the device admin key.");
      return;
    }
    setLocalError(null);
    setValue("");
    onSubmit(next);
  }

  return (
    <section className="mx-auto max-w-lg rounded-2xl border border-slate-200 bg-white p-6 shadow-sm">
      <h1 className="text-xl font-semibold tracking-tight">Local demonstration access</h1>
      <p className="mt-2 text-sm leading-6 text-slate-600">
        Enter the device-admin key for this browser session. It stays in memory, is cleared when you sign out or refresh, and is not a public sign-in.
      </p>
      <form className="mt-5 grid gap-3" onSubmit={submit} autoComplete="off">
        <label className="grid gap-1 text-sm font-medium text-slate-800" htmlFor="admin-key">
          Device admin key
          <input
            id="admin-key"
            name="streamforge-admin-key"
            type="password"
            autoComplete="new-password"
            spellCheck={false}
            value={value}
            onChange={(event) => setValue(event.target.value)}
            className="field"
          />
        </label>
        {message ? (
          <p role="alert" className="text-sm text-red-800">
            {message}
          </p>
        ) : null}
        <button type="submit" className="btn-primary w-fit">
          Continue
        </button>
      </form>
    </section>
  );
}
