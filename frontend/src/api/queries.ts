import { useQuery } from "@tanstack/react-query";
import { getHealth, getReadings, getSeries, getSummary, listDevices } from "./client";
import { useSession } from "../auth/Session";
import type { Bounds, Granularity } from "../lib/time";

const DEVICE_PAGE_SIZE = 100;
export const READING_PAGE_SIZE = 50;

export function useHealth() {
  return useQuery({
    queryKey: ["health"],
    queryFn: ({ signal }) => getHealth(signal),
    refetchInterval: () => (document.visibilityState === "visible" ? 15_000 : false),
    retry: false,
  });
}

export function useDevices(offset: number) {
  const { key, sessionId } = useSession();
  return useQuery({
    queryKey: ["devices", sessionId, offset],
    enabled: Boolean(key),
    queryFn: ({ signal }) => listDevices(key as string, DEVICE_PAGE_SIZE, offset, signal),
  });
}

export function useSummary(deviceId: string, bounds: Bounds | null) {
  const { key, sessionId } = useSession();
  return useQuery({
    queryKey: ["summary", sessionId, deviceId, bounds?.from, bounds?.to],
    enabled: Boolean(key && bounds),
    queryFn: ({ signal }) => getSummary(key as string, deviceId, bounds!.from, bounds!.to, signal),
  });
}

export function useSeries(deviceId: string, bounds: Bounds | null, granularity: Granularity) {
  const { key, sessionId } = useSession();
  return useQuery({
    queryKey: ["series", sessionId, deviceId, bounds?.from, bounds?.to, granularity],
    enabled: Boolean(key && bounds),
    queryFn: ({ signal }) => getSeries(key as string, deviceId, bounds!.from, bounds!.to, granularity, signal),
  });
}

export function useReadings(deviceId: string, bounds: Bounds | null, cursor: string | null) {
  const { key, sessionId } = useSession();
  return useQuery({
    queryKey: ["readings", deviceId, sessionId, bounds?.from, bounds?.to, READING_PAGE_SIZE, cursor],
    enabled: Boolean(key && bounds),
    queryFn: ({ signal }) =>
      getReadings(key as string, deviceId, bounds!.from, bounds!.to, READING_PAGE_SIZE, cursor, signal),
  });
}

export { DEVICE_PAGE_SIZE };
