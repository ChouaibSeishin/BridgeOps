import { render, screen, fireEvent } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { MemoryRouter } from "react-router-dom";
import { afterEach, describe, expect, it, vi } from "vitest";
import { App } from "./App";

function renderApp() {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false, gcTime: 0 } },
  });
  return render(
    <QueryClientProvider client={client}>
      <MemoryRouter>
        <App />
      </MemoryRouter>
    </QueryClientProvider>,
  );
}
function healthyFetch() {
  return vi
    .fn()
    .mockImplementation((url: string) =>
      Promise.resolve(
        new Response(
          JSON.stringify(
            url.includes("/info")
              ? { name: "BridgeOps", apiVersion: "v1" }
              : { status: "UP" },
          ),
          { status: 200, headers: { "Content-Type": "application/json" } },
        ),
      ),
    );
}
afterEach(() => vi.unstubAllGlobals());
describe("foundation workspace", () => {
  it("shows actual API and readiness results without fabricated workflow counts", async () => {
    const fetcher = healthyFetch();
    vi.stubGlobal("fetch", fetcher);
    renderApp();
    expect(await screen.findByText("BridgeOps · v1")).toBeInTheDocument();
    expect(await screen.findAllByText("Connected")).toHaveLength(2);
    expect(fetcher).toHaveBeenCalledWith(
      "/api/v1/system/info",
      expect.anything(),
    );
    expect(fetcher).toHaveBeenCalledWith(
      "/health/readiness",
      expect.anything(),
    );
    expect(screen.queryByText("Total workflows")).not.toBeInTheDocument();
  });
  it("shows the pending state while responses are unresolved", () => {
    vi.stubGlobal(
      "fetch",
      vi.fn(() => new Promise(() => {})),
    );
    renderApp();
    expect(screen.getAllByText("Checking")).toHaveLength(2);
  });
  it("offers retry and recovers after service failure", async () => {
    const fetcher = vi.fn().mockRejectedValue(new Error("network"));
    vi.stubGlobal("fetch", fetcher);
    renderApp();
    expect(await screen.findByRole("alert")).toHaveTextContent(
      "couldn’t verify the connection",
    );
    fetcher.mockImplementation(healthyFetch());
    fireEvent.click(screen.getByRole("button", { name: "Try again" }));
    expect(await screen.findByText("BridgeOps · v1")).toBeInTheDocument();
  });
  it("switches theme and navigates to live runtime diagnostics", async () => {
    vi.stubGlobal("fetch", healthyFetch());
    renderApp();
    fireEvent.click(
      screen.getByRole("button", { name: "Switch to dark theme" }),
    );
    expect(document.documentElement).toHaveAttribute("data-theme", "dark");
    fireEvent.click(screen.getByRole("link", { name: "Runtime" }));
    expect(
      await screen.findByRole("heading", { name: "Connection diagnostics" }),
    ).toBeInTheDocument();
    expect(await screen.findByText("Ready")).toBeInTheDocument();
  });
});
