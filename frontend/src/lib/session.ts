export interface User {
  id: string;
  email: string;
  displayName: string;
  canCreateOrganizations?: boolean;
}
export interface SessionState {
  status: "loading" | "anonymous" | "authenticated" | "unavailable";
  user?: User;
  error?: string;
}
export class HttpFailure extends Error {
  constructor(
    public status: number,
    public code: string,
    public reference?: string,
    public retryAfter?: string,
  ) {
    super(code);
  }
}
export function failureMessage(error: unknown): string {
  if (!(error instanceof HttpFailure))
    return "The connection was interrupted. Check your connection before trying again.";
  const messages: Record<number, string> = {
    400: "Check the submitted details and try again.",
    401: "Your sign-in was not accepted or your session has ended.",
    403: "This operation is not permitted. Your permissions or browser security state may have changed.",
    404: "This resource is unavailable to your account.",
    409: "The record changed or conflicts with existing data. Reload before trying again.",
    429: "Too many requests. Wait before trying again.",
    503: "The service or email delivery is unavailable. Try again later.",
  };
  return (
    (messages[error.status] ?? "The request could not be completed.") +
    (error.reference ? ` Reference: ${error.reference}` : "")
  );
}
type Lock = <T>(work: () => Promise<T>) => Promise<T>;
export const browserLock: Lock = async (work) => {
  if (!navigator.locks)
    return Promise.reject(new Error("Web Locks unavailable"));
  return navigator.locks.request(
    "bridgeops-session",
    { mode: "exclusive" },
    work,
  );
};
/** Credentials live only here. Every cookie-sensitive sequence shares an origin-wide lock. */
export class SessionClient {
  private access?: { value: string; expires: number };
  private state: SessionState = { status: "loading" };
  private listeners = new Set<() => void>();
  private initialization?: Promise<void>;
  private refreshFlight?: Promise<void>;
  private generation = 0;
  constructor(
    private lock: Lock = browserLock,
    private transport: typeof fetch = (...args) => fetch(...args),
    private notify: () => void = () => {},
  ) {}
  snapshot = () => this.state;
  subscribe = (listener: () => void) => {
    this.listeners.add(listener);
    return () => {
      this.listeners.delete(listener);
    };
  };
  private publish(state: SessionState) {
    this.state = state;
    this.listeners.forEach((listener) => listener());
  }
  clear = () => {
    this.generation++;
    this.access = undefined;
    this.publish({ status: "anonymous" });
  };
  private async send(path: string, init: RequestInit = {}): Promise<Response> {
    const response = await this.transport(path, {
      ...init,
      credentials: "same-origin",
      cache: "no-store",
      redirect: "error",
      signal: AbortSignal.timeout(15000),
    });
    if (!response.ok) {
      let code = "REQUEST_FAILED";
      try {
        const body = (await response.json()) as { code?: string };
        code = body.code ?? code;
      } catch {
        /* Fixed safe errors only. */
      }
      throw new HttpFailure(
        response.status,
        code,
        response.headers.get("X-Correlation-ID") ?? undefined,
        response.headers.get("Retry-After") ?? undefined,
      );
    }
    return response;
  }
  private async csrf(): Promise<Record<string, string>> {
    const response = await this.send("/api/v1/auth/csrf");
    const value = (await response.json()) as {
      headerName: string;
      token: string;
    };
    if (value.headerName !== "X-CSRF-TOKEN" || typeof value.token !== "string")
      throw new Error("Invalid CSRF response");
    return { [value.headerName]: value.token };
  }
  private async restoreLocked() {
    const generation = this.generation;
    const headers = await this.csrf();
    const response = await this.send("/api/v1/auth/refresh", {
      method: "POST",
      headers,
    });
    await this.install(response, generation);
  }
  private async install(response: Response, generation: number) {
    const value = (await response.json()) as {
      accessToken: string;
      expiresAt: string;
    };
    if (
      typeof value.accessToken !== "string" ||
      !Number.isFinite(Date.parse(value.expiresAt))
    )
      throw new Error("Invalid session response");
    const me = await this.send("/api/v1/auth/me", {
      headers: { Authorization: `Bearer ${value.accessToken}` },
    });
    const identity = (await me.json()) as { user: User };
    if (
      !identity.user?.id ||
      !identity.user.email ||
      !identity.user.displayName
    )
      throw new Error("Invalid identity response");
    if (generation !== this.generation) return;
    this.access = {
      value: value.accessToken,
      expires: Date.parse(value.expiresAt),
    };
    this.publish({ status: "authenticated", user: identity.user });
  }
  initialize = () =>
    (this.initialization ??= this.refresh().catch((error) => {
      if (error instanceof HttpFailure && error.status === 401) this.clear();
      else
        this.publish({
          status: "unavailable",
          error: navigator.locks
            ? failureMessage(error)
            : "This browser needs Web Locks support for secure session coordination. Use a current Chrome, Edge, Firefox or Safari browser.",
        });
    }));
  retry = async () => {
    this.initialization = undefined;
    this.publish({ status: "loading" });
    await this.initialize();
  };
  private refresh() {
    if (!this.refreshFlight)
      this.refreshFlight = this.lock(() => this.restoreLocked()).finally(() => {
        this.refreshFlight = undefined;
      });
    return this.refreshFlight;
  }
  login = async (email: string, password: string) => {
    await this.lock(async () => {
      const generation = this.generation;
      const headers = await this.csrf();
      const response = await this.send("/api/v1/auth/login", {
        method: "POST",
        headers: { ...headers, "Content-Type": "application/json" },
        body: JSON.stringify({ email, password }),
      });
      await this.install(response, generation);
      this.notify();
    });
  };
  request = async <T>(path: string, init: RequestInit = {}): Promise<T> => {
    if (
      !path.startsWith("/api/v1/") ||
      path.includes("..") ||
      path.includes("?token=")
    )
      throw new Error("Invalid API path");
    const perform = async () => {
      if (!this.access || this.access.expires <= Date.now() + 5000)
        await this.restoreLocked();
      const token = this.access?.value;
      const unsafe = !["GET", "HEAD"].includes(init.method ?? "GET");
      const call = async () => {
        const csrf = unsafe ? await this.csrf() : {};
        return this.send(path, {
          ...init,
          headers: {
            ...init.headers,
            ...csrf,
            Authorization: `Bearer ${this.access?.value}`,
            Accept: "application/json",
          },
        });
      };
      let response: Response;
      try {
        response = await call();
      } catch (error) {
        if (!(error instanceof HttpFailure) || error.status !== 401)
          throw error;
        if (this.access?.value === token) await this.restoreLocked();
        response = await call();
      }
      return (response.status === 204 ? undefined : await response.json()) as T;
    };
    try {
      return await this.lock(perform);
    } catch (error) {
      if (error instanceof HttpFailure && error.status === 401) {
        this.clear();
        this.notify();
      }
      throw error;
    }
  };
  /** Anonymous invitation/recovery mutations still use Origin + fresh signed CSRF. */
  publicRequest = async <T>(path: string, body: unknown): Promise<T> =>
    this.lock(async () => {
      const headers = await this.csrf();
      const response = await this.send(path, {
        method: "POST",
        headers: { ...headers, "Content-Type": "application/json" },
        body: JSON.stringify(body),
      });
      return (response.status === 204 ? undefined : await response.json()) as T;
    });
  logout = async (all = false) => {
    try {
      if (all)
        await this.request("/api/v1/auth/logout-all", { method: "POST" });
      else
        await this.lock(async () => {
          const headers = await this.csrf();
          await this.send("/api/v1/auth/logout", { method: "POST", headers });
        });
      this.notify();
    } finally {
      this.clear();
    }
  };
}
const changes =
  typeof BroadcastChannel !== "undefined"
    ? new BroadcastChannel("bridgeops-session-events")
    : undefined;
export const session = new SessionClient(browserLock, undefined, () =>
  changes?.postMessage("session-changed"),
);
if (changes)
  changes.onmessage = (event) => {
    if (event.data === "session-changed") session.clear();
  };
