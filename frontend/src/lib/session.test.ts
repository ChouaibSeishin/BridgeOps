import { describe, it, expect, vi } from "vitest";
import { SessionClient, HttpFailure } from "./session";
const user = {
  id: "account",
  email: "person@example.com",
  displayName: "Person",
};
const json = (body: unknown, status = 200) =>
  new Response(JSON.stringify(body), {
    status,
    headers: { "Content-Type": "application/json" },
  });
function fixture() {
  let refreshes = 0;
  let expiry = Date.now() + 600000;
  let failures = 0;
  const transport = vi.fn<typeof fetch>(async (input, init) => {
    const url = String(input);
    const headers = init?.headers as Record<string, string> | undefined;
    if (url.endsWith("/csrf"))
      return json({ headerName: "X-CSRF-TOKEN", token: "test-proof" });
    if (url.endsWith("/refresh") || url.endsWith("/login")) {
      expect(headers?.Authorization).toBeUndefined();
      expect(headers?.["X-CSRF-TOKEN"]).toBe("test-proof");
      refreshes++;
      return json({
        accessToken: `synthetic-${refreshes}`,
        expiresAt: new Date(expiry).toISOString(),
      });
    }
    if (url.endsWith("/me")) return json({ user });
    if (url.endsWith("/logout")) {
      expect(headers?.Authorization).toBeUndefined();
      return new Response(null, { status: 204 });
    }
    if (url.endsWith("/logout-all")) return new Response(null, { status: 204 });
    if (failures-- > 0) return json({ code: "AUTHENTICATION_REQUIRED" }, 401);
    return json({ ok: true });
  });
  // Two clients share the same queue, like the origin-wide browser Web Lock.
  let queue = Promise.resolve();
  const lock = <T>(work: () => Promise<T>): Promise<T> => {
    const next = queue.then(work);
    queue = next.then(
      () => {},
      () => {},
    );
    return next;
  };
  const client = new SessionClient(lock, transport);
  return {
    client,
    transport,
    lock,
    refreshes: () => refreshes,
    expire: () => {
      expiry = Date.now() - 1;
    },
    unauthorized: (count: number) => {
      failures = count;
    },
  };
}
describe("session coordinator", () => {
  it("initializes once through CSRF, cookie refresh and live identity", async () => {
    const f = fixture();
    await Promise.all([f.client.initialize(), f.client.initialize()]);
    expect(f.refreshes()).toBe(1);
    expect(f.client.snapshot().user).toEqual(user);
    for (const [, init] of f.transport.mock.calls) {
      expect(init?.cache).toBe("no-store");
      expect(init?.credentials).toBe("same-origin");
    }
  });
  it("serializes concurrent unauthorized requests and reuses the new token", async () => {
    const f = fixture();
    await f.client.initialize();
    f.unauthorized(1);
    await Promise.all([
      f.client.request("/api/v1/example"),
      f.client.request("/api/v1/example"),
    ]);
    expect(f.refreshes()).toBe(2);
  });
  it("refreshes expired access before sending a protected request", async () => {
    const f = fixture();
    f.expire();
    await f.client.initialize();
    await f.client.request("/api/v1/example");
    expect(f.refreshes()).toBe(2);
  });
  it("makes concurrent tab restores nonoverlapping", async () => {
    const f = fixture();
    const other = new SessionClient(f.lock, f.transport);
    await Promise.all([f.client.initialize(), other.initialize()]);
    expect(f.transport.mock.calls.map(([url]) => url)).toEqual([
      "/api/v1/auth/csrf",
      "/api/v1/auth/refresh",
      "/api/v1/auth/me",
      "/api/v1/auth/csrf",
      "/api/v1/auth/refresh",
      "/api/v1/auth/me",
    ]);
  });
  it("ends the session on rejected refresh", async () => {
    const f = fixture();
    f.transport.mockImplementation(async () =>
      json({ code: "INVALID_CREDENTIALS" }, 401),
    );
    await f.client.initialize();
    expect(f.client.snapshot().status).toBe("anonymous");
  });
  it("retains a retryable unavailable state on network failure", async () => {
    const f = fixture();
    f.transport.mockRejectedValue(new TypeError("offline"));
    await f.client.initialize();
    expect(f.client.snapshot().status).toBe("unavailable");
  });
  it("clears local state even when logout cannot be confirmed", async () => {
    const f = fixture();
    await f.client.initialize();
    f.transport.mockRejectedValue(new TypeError("offline"));
    await expect(f.client.logout()).rejects.toThrow();
    expect(f.client.snapshot().status).toBe("anonymous");
  });
  it("uses cookie logout and authenticated logout-all", async () => {
    const f = fixture();
    await f.client.initialize();
    await f.client.logout(true);
    expect(f.client.snapshot().status).toBe("anonymous");
    expect(
      f.transport.mock.calls.some(
        ([url, init]) =>
          String(url).endsWith("logout-all") &&
          (init?.headers as Record<string, string>).Authorization,
      ),
    ).toBe(true);
    await f.client.login(user.email, "synthetic-password");
    await f.client.logout();
    expect(f.client.snapshot().status).toBe("anonymous");
  });
  it("does not retry forbidden, conflict or throttled mutations", async () => {
    for (const status of [403, 409, 429]) {
      const f = fixture();
      await f.client.initialize();
      f.transport.mockImplementation(async (url) =>
        String(url).endsWith("csrf")
          ? json({ headerName: "X-CSRF-TOKEN", token: "test-proof" })
          : json({ code: "DENIED" }, status),
      );
      await expect(
        f.client.request("/api/v1/example", { method: "POST" }),
      ).rejects.toBeInstanceOf(HttpFailure);
      expect(f.refreshes()).toBe(1);
    }
  });
});
