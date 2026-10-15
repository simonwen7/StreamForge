import type { DevicePage, Health, ProblemDetail, ReadingPage, Series, Summary } from "./types";

export class ApiError extends Error {
  readonly status: number;
  readonly problem: ProblemDetail;

  constructor(status: number, problem: ProblemDetail) {
    super(problemMessage(problem, status));
    this.name = "ApiError";
    this.status = status;
    this.problem = problem;
  }
}

export function problemMessage(problem: ProblemDetail, status: number): string {
  const field = problem.errors?.find((error) => error.message)?.message;
  if (field) {
    return field;
  }
  if (problem.detail) {
    return problem.detail;
  }
  if (problem.title) {
    return problem.title;
  }
  return `The request failed (${status}).`;
}

export function errorMessage(error: unknown): string {
  if (error instanceof ApiError) {
    if (error.status === 401) {
      return "Authentication is required.";
    }
    if (error.status === 403) {
      return "This key cannot view devices or analytics.";
    }
    if (error.status === 404) {
      return error.problem.detail || "Device was not found.";
    }
    return error.message;
  }
  if (error instanceof DOMException && error.name === "AbortError") {
    return "The request was cancelled.";
  }
  return "The API is unreachable.";
}

type Method = "GET" | "POST";

async function request<T>(method: Method, path: string, key: string | null, body: unknown, signal?: AbortSignal): Promise<T> {
  const headers = new Headers({ Accept: "application/json" });
  if (key) {
    headers.set("Authorization", `Bearer ${key}`);
  }
  if (body !== undefined) {
    headers.set("Content-Type", "application/json");
  }
  let response: Response;
  try {
    response = await fetch(path, {
      method,
      headers,
      body: body === undefined ? undefined : JSON.stringify(body),
      signal,
    });
  } catch (error) {
    if (error instanceof DOMException && error.name === "AbortError") {
      throw error;
    }
    throw new Error("The API is unreachable.", { cause: error });
  }
  if (response.status === 204) {
    return undefined as T;
  }
  const text = await response.text();
  const parsed = text ? parseJson(text) : {};
  if (!response.ok) {
    throw new ApiError(response.status, parsed as ProblemDetail);
  }
  return parsed as T;
}

function parseJson(text: string): unknown {
  try {
    return JSON.parse(text) as unknown;
  } catch {
    return {};
  }
}

export function getHealth(signal?: AbortSignal): Promise<Health> {
  return request("GET", "/actuator/health", null, undefined, signal);
}

export function listDevices(key: string, limit: number, offset: number, signal?: AbortSignal): Promise<DevicePage> {
  const params = new URLSearchParams({ limit: String(limit), offset: String(offset) });
  return request("GET", `/api/v1/devices?${params}`, key, undefined, signal);
}

export function createDevice(key: string, deviceId: string, signal?: AbortSignal) {
  return request<{ deviceId: string; createdAt: string }>("POST", "/api/v1/devices", key, { deviceId }, signal);
}

export function getSummary(key: string, deviceId: string, from: string, to: string, signal?: AbortSignal): Promise<Summary> {
  const params = new URLSearchParams({ from, to });
  return request("GET", `/api/v1/analytics/devices/${encodeURIComponent(deviceId)}/summary?${params}`, key, undefined, signal);
}

export function getSeries(
  key: string,
  deviceId: string,
  from: string,
  to: string,
  granularity: "hour" | "day",
  signal?: AbortSignal,
): Promise<Series> {
  const params = new URLSearchParams({ from, to, granularity });
  return request("GET", `/api/v1/analytics/devices/${encodeURIComponent(deviceId)}/series?${params}`, key, undefined, signal);
}

export function getReadings(
  key: string,
  deviceId: string,
  from: string,
  to: string,
  limit: number,
  cursor: string | null,
  signal?: AbortSignal,
): Promise<ReadingPage> {
  const params = new URLSearchParams({ from, to, limit: String(limit) });
  if (cursor) {
    params.set("cursor", cursor);
  }
  return request("GET", `/api/v1/devices/${encodeURIComponent(deviceId)}/readings?${params}`, key, undefined, signal);
}
