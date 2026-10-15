import type { ReactNode } from "react";
import { errorMessage } from "../api/client";

export function LoadingBlock({ label }: { label: string }) {
  return (
    <div role="status" aria-live="polite" className="grid gap-3">
      <span className="sr-only">{label}</span>
      <div className="h-24 animate-pulse rounded-xl bg-slate-200" />
      <div className="h-40 animate-pulse rounded-xl bg-slate-200" />
    </div>
  );
}

export function ErrorPanel({ error }: { error: unknown }) {
  return (
    <div role="alert" className="rounded-xl border border-red-200 bg-red-50 px-4 py-3 text-sm text-red-900">
      {errorMessage(error)}
    </div>
  );
}

export function EmptyState({ title, children }: { title: string; children: ReactNode }) {
  return (
    <div className="rounded-xl border border-dashed border-slate-300 bg-white px-5 py-8 text-center">
      <h2 className="text-base font-semibold text-slate-900">{title}</h2>
      <p className="mx-auto mt-2 max-w-lg text-sm leading-6 text-slate-600">{children}</p>
    </div>
  );
}
