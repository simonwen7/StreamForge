import { useQueryClient } from "@tanstack/react-query";
import { createContext, useContext, useEffect, useMemo, useState, type ReactNode } from "react";
import { ApiError } from "../api/client";

export const INVALID_KEY_MESSAGE = "Invalid API key. Please try again.";

type SessionValue = {
  key: string | null;
  /** Opaque generation. Query identity uses this, never the API key. */
  sessionId: number;
  authError: string | null;
  signIn: (key: string) => void;
  signOut: () => void;
};

const SessionContext = createContext<SessionValue | null>(null);

export function SessionProvider({ children }: { children: ReactNode }) {
  const queryClient = useQueryClient();
  const [key, setKey] = useState<string | null>(null);
  const [sessionId, setSessionId] = useState(0);
  const [authError, setAuthError] = useState<string | null>(null);

  const signOut = useMemo(() => {
    return () => {
      setAuthError(null);
      setKey(null);
      void queryClient.cancelQueries();
      queryClient.clear();
    };
  }, [queryClient]);

  const rejectKey = useMemo(() => {
    return () => {
      setAuthError(INVALID_KEY_MESSAGE);
      setKey(null);
      void queryClient.cancelQueries();
      queryClient.clear();
    };
  }, [queryClient]);

  useEffect(() => {
    return queryClient.getQueryCache().subscribe((event) => {
      if (event.type !== "updated" || event.query.state.status !== "error") {
        return;
      }
      if (event.query.state.error instanceof ApiError && event.query.state.error.status === 401) {
        rejectKey();
      }
    });
  }, [queryClient, rejectKey]);

  const value = useMemo<SessionValue>(
    () => ({
      key,
      sessionId,
      authError,
      signIn: (next) => {
        void queryClient.cancelQueries();
        queryClient.clear();
        setAuthError(null);
        setSessionId((current) => current + 1);
        setKey(next);
      },
      signOut,
    }),
    [key, sessionId, authError, queryClient, signOut],
  );

  return <SessionContext.Provider value={value}>{children}</SessionContext.Provider>;
}

export function useSession(): SessionValue {
  const value = useContext(SessionContext);
  if (!value) {
    throw new Error("Session is unavailable.");
  }
  return value;
}
