import { useQueryClient } from "@tanstack/react-query";
import { useState, type FormEvent } from "react";
import { Link } from "react-router";
import { z } from "zod";
import { ApiError, createDevice, errorMessage } from "../api/client";
import { DEVICE_PAGE_SIZE, useDevices } from "../api/queries";
import { useSession } from "../auth/Session";
import { ErrorPanel, LoadingBlock } from "../components/States";
import { localLabel, utcLabel } from "../lib/time";

const deviceIdSchema = z.string().regex(/^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$/, "deviceId format is invalid");

export function DevicesPage() {
  const [offset, setOffset] = useState(0);
  const devices = useDevices(offset);
  const timeZone = Intl.DateTimeFormat().resolvedOptions().timeZone;

  return (
    <div className="grid gap-6">
      <div>
        <h1 className="text-2xl font-semibold tracking-tight">Devices</h1>
        <p className="mt-1 max-w-2xl text-sm leading-6 text-slate-600">
          Registered devices come from the API. A full page of {DEVICE_PAGE_SIZE} may have another page. There is no total count.
        </p>
      </div>
      <RegisterForm />
      {devices.isPending ? <LoadingBlock label="Loading devices" /> : null}
      {devices.isError ? <ErrorPanel error={devices.error} /> : null}
      {devices.data ? (
        <section className="overflow-hidden rounded-xl border border-slate-200 bg-white shadow-sm">
          {devices.data.devices.length === 0 ? (
            <p className="px-4 py-8 text-sm text-slate-600">No devices are registered on this page.</p>
          ) : (
            <ul className="divide-y divide-slate-100">
              {devices.data.devices.map((device) => (
                <li key={device.deviceId}>
                  <Link
                    to={`/devices/${encodeURIComponent(device.deviceId)}`}
                    className="flex flex-col gap-1 px-4 py-3 hover:bg-slate-50 focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-[-2px] focus-visible:outline-blue-700 sm:flex-row sm:items-center sm:justify-between"
                  >
                    <span className="font-mono text-sm text-slate-900">{device.deviceId}</span>
                    <span className="text-xs text-slate-500">
                      Created {utcLabel(device.createdAt)} · {localLabel(device.createdAt, timeZone)}
                    </span>
                  </Link>
                </li>
              ))}
            </ul>
          )}
          <div className="flex items-center justify-between border-t border-slate-100 px-4 py-3 text-sm">
            <span className="text-slate-600">Starting at offset {devices.data.offset}</span>
            <div className="flex gap-2">
              <button type="button" className="btn-secondary" disabled={offset === 0} onClick={() => setOffset(Math.max(0, offset - DEVICE_PAGE_SIZE))}>
                Previous devices
              </button>
              <button
                type="button"
                className="btn-secondary"
                disabled={devices.data.devices.length < DEVICE_PAGE_SIZE}
                onClick={() => setOffset(offset + DEVICE_PAGE_SIZE)}
              >
                Next devices
              </button>
            </div>
          </div>
        </section>
      ) : null}
    </div>
  );
}

function RegisterForm() {
  const { key } = useSession();
  const queryClient = useQueryClient();
  const [deviceId, setDeviceId] = useState("");
  const [message, setMessage] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [pending, setPending] = useState(false);

  async function submit(event: FormEvent) {
    event.preventDefault();
    const parsed = deviceIdSchema.safeParse(deviceId.trim());
    if (!parsed.success) {
      setError(parsed.error.issues[0]?.message ?? "deviceId format is invalid");
      setMessage(null);
      return;
    }
    if (!key) {
      return;
    }
    setPending(true);
    setError(null);
    setMessage(null);
    try {
      await createDevice(key, parsed.data);
      setDeviceId("");
      setMessage(`${parsed.data} is registered.`);
      await queryClient.invalidateQueries({ queryKey: ["devices"] });
    } catch (caught) {
      setError(caught instanceof ApiError ? errorMessage(caught) : errorMessage(caught));
    } finally {
      setPending(false);
    }
  }

  return (
    <form onSubmit={submit} className="rounded-xl border border-slate-200 bg-white p-4 shadow-sm" autoComplete="off">
      <h2 className="text-sm font-semibold text-slate-900">Register a device</h2>
      <div className="mt-3 flex flex-col gap-3 sm:flex-row sm:items-end">
        <label className="grid flex-1 gap-1 text-sm font-medium text-slate-800" htmlFor="device-id">
          Device ID
          <input id="device-id" value={deviceId} onChange={(event) => setDeviceId(event.target.value)} className="field font-mono" />
        </label>
        <button type="submit" className="btn-primary" disabled={pending}>
          Register device
        </button>
      </div>
      {message ? <p className="mt-3 text-sm text-emerald-800">{message}</p> : null}
      {error ? (
        <p role="alert" className="mt-3 text-sm text-red-800">
          {error}
        </p>
      ) : null}
    </form>
  );
}
