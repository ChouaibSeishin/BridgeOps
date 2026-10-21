import { render, screen, fireEvent, waitFor } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import { beforeEach, afterEach, it, expect, vi } from "vitest";
import { session, HttpFailure } from "../../lib/session";
import { Connections, ConnectionDetail } from "./Connections";
import { connectionApi, type ManagedConnection } from "./connectionApi";
const user = {
  id: "manager",
  email: "manager@example.invalid",
  displayName: "Synthetic Manager",
};
function managed(): ManagedConnection {
  return {
    connection: {
      id: "connection",
      name: "Persisted connection",
      description: "Approved provider",
      type: "REST",
      implementationVersion: 1,
      revision: 0,
      enabled: true,
      updatedAt: "2026-10-21T12:00:00Z",
      health: {
        status: "NOT_CHECKED",
        code: null,
        checkedAt: null,
        revision: null,
      },
    },
    configuration: {
      baseDestination: "https://api.example.com",
      healthOperation: "health",
      operations: [
        {
          id: "health",
          method: "GET",
          path: "/health",
          pathParameters: [],
          requestFields: {},
          responseFields: { message: "STRING" },
          classification: "READ",
          timeoutSeconds: 5,
          maximumResponseBytes: 4096,
          remoteIdempotencyKey: false,
        },
      ],
    },
    credentialConfigured: true,
  };
}
beforeEach(() => {
  vi.spyOn(session, "request").mockResolvedValue({
    permissions: ["integration.read", "integration.manage"],
  });
  vi.spyOn(connectionApi, "definitions").mockResolvedValue([
    {
      type: "REST",
      name: "Configured HTTPS",
      description: "Registered provider operations",
      implementationVersion: 1,
      operations: [],
    },
  ]);
  vi.spyOn(connectionApi, "list").mockResolvedValue({
    items: [managed().connection],
    page: 0,
    size: 10,
    totalElements: 1,
    totalPages: 1,
  });
  vi.spyOn(connectionApi, "get").mockResolvedValue(managed().connection);
  vi.spyOn(connectionApi, "configuration").mockResolvedValue(managed());
  vi.spyOn(connectionApi, "operations").mockResolvedValue([
    {
      id: "health",
      classification: "READ",
      requiresApproval: false,
      requestFields: {},
      responseFields: { message: "STRING" },
    },
  ]);
  vi.spyOn(connectionApi, "create").mockResolvedValue(managed());
  vi.spyOn(connectionApi, "update").mockResolvedValue({
    ...managed(),
    connection: { ...managed().connection, revision: 1 },
  });
  vi.spyOn(connectionApi, "check").mockResolvedValue({
    ...managed().connection,
    health: {
      status: "AVAILABLE",
      code: "READ_CHECK_SUCCEEDED",
      revision: 0,
      checkedAt: "2026-10-21T12:01:00Z",
    },
  });
});
afterEach(() => vi.restoreAllMocks());
function open(detail = false) {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  });
  return render(
    <QueryClientProvider client={client}>
      <MemoryRouter
        initialEntries={[
          detail
            ? "/organizations/org/integrations/connection"
            : "/organizations/org/integrations",
        ]}
      >
        <Routes>
          <Route
            path="/organizations/:id/integrations"
            element={<Connections user={user} />}
          />
          <Route
            path="/organizations/:id/integrations/:connectionId"
            element={<ConnectionDetail user={user} />}
          />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  );
}
it("shows actual catalog and persisted connection health", async () => {
  open();
  await screen.findByRole("heading", { name: "Integration catalog" });
  expect(
    await screen.findByRole("heading", { name: "Configured HTTPS" }),
  ).toBeVisible();
  expect(await screen.findByText("Persisted connection")).toBeVisible();
  expect(screen.getByText("Connection check: NOT_CHECKED")).toBeVisible();
});
it("does not load private configuration or controls for viewers", async () => {
  vi.mocked(session.request).mockResolvedValue({
    permissions: ["integration.read"],
  });
  open(true);
  expect(
    await screen.findByRole("heading", { name: "Registered operations" }),
  ).toBeVisible();
  expect(connectionApi.configuration).not.toHaveBeenCalled();
  expect(
    screen.queryByRole("heading", { name: "Manage connection" }),
  ).not.toBeInTheDocument();
  expect(screen.queryByText("https://api.example.com")).not.toBeInTheDocument();
  expect(
    screen.queryByRole("button", { name: "Check saved connection" }),
  ).not.toBeInTheDocument();
});
it("never fills credential inputs from saved configuration", async () => {
  open(true);
  const input = await screen.findByLabelText("New bearer credential");
  expect(input).toHaveAttribute("type", "password");
  expect(input).toHaveValue("");
  expect(
    screen.getByText(/saved credentials are never returned/),
  ).toBeVisible();
});
it("retains stale edits and clears credential after a rejected save", async () => {
  vi.mocked(connectionApi.update).mockRejectedValue(
    new HttpFailure(409, "STALE_REVISION"),
  );
  open(true);
  fireEvent.change(await screen.findByLabelText("Connection name"), {
    target: { value: "Unsaved operator edit" },
  });
  fireEvent.change(screen.getByLabelText("New bearer credential"), {
    target: { value: "disposable-new-credential" },
  });
  fireEvent.click(
    screen.getByRole("button", { name: "Save connection configuration" }),
  );
  expect(await screen.findByRole("alert")).toBeVisible();
  expect(screen.getByLabelText("Connection name")).toHaveValue(
    "Unsaved operator edit",
  );
  expect(screen.getByLabelText("New bearer credential")).toHaveValue("");
  expect(connectionApi.update).toHaveBeenCalledWith(
    "org",
    "connection",
    expect.objectContaining({
      expectedRevision: 0,
      credential: "disposable-new-credential",
    }),
  );
});
it("requires explicit reload confirmation before discarding edits", async () => {
  open(true);
  fireEvent.change(await screen.findByLabelText("Connection name"), {
    target: { value: "Unapplied edit" },
  });
  fireEvent.click(
    screen.getByRole("button", { name: "Reload saved connection" }),
  );
  expect(screen.getByLabelText("Connection name")).toHaveValue(
    "Unapplied edit",
  );
  fireEvent.click(
    screen.getByRole("button", { name: "Confirm reload connection" }),
  );
  await waitFor(() =>
    expect(screen.getByLabelText("Connection name")).toHaveValue(
      "Persisted connection",
    ),
  );
});
it("shows saved-check results from the server without fabricated availability", async () => {
  open(true);
  await screen.findByLabelText("New bearer credential");
  fireEvent.click(
    await screen.findByRole("button", { name: "Check saved connection" }),
  );
  expect(connectionApi.check).not.toHaveBeenCalled();
  expect(
    screen.getByRole("alertdialog", { name: "Confirm saved connection check" }),
  ).toHaveTextContent("https://api.example.com");
  expect(
    screen.getByRole("alertdialog", { name: "Confirm saved connection check" }),
  ).toHaveTextContent("Registered read operation: health");
  fireEvent.click(
    screen.getByRole("button", { name: "Confirm connection check" }),
  );
  expect(
    await screen.findByText("Recorded connection check: AVAILABLE."),
  ).toBeVisible();
  expect(connectionApi.check).toHaveBeenCalledWith("org", "connection", 0);
});
it("keeps disabled connections from outbound checks", async () => {
  vi.mocked(connectionApi.get).mockResolvedValue({
    ...managed().connection,
    enabled: false,
  });
  open(true);
  expect(
    await screen.findByRole("button", { name: "Check saved connection" }),
  ).toBeDisabled();
  expect(connectionApi.check).not.toHaveBeenCalled();
});
it("saves only explicit approved REST operation configuration and a write-only credential", async () => {
  open();
  fireEvent.click(
    await screen.findByRole("button", { name: "Configure a connection" }),
  );
  fireEvent.change(screen.getByLabelText("Connection name"), {
    target: { value: "New provider" },
  });
  fireEvent.change(screen.getByLabelText("Integration type"), {
    target: { value: "REST" },
  });
  fireEvent.change(screen.getByLabelText("HTTPS base destination"), {
    target: { value: "https://api.example.com" },
  });
  fireEvent.change(screen.getByLabelText("New bearer credential"), {
    target: { value: "disposable-new-credential" },
  });
  fireEvent.click(
    screen.getByRole("button", { name: "Save connection configuration" }),
  );
  await waitFor(() =>
    expect(connectionApi.create).toHaveBeenCalledWith(
      "org",
      expect.objectContaining({
        name: "New provider",
        type: "REST",
        credential: "disposable-new-credential",
        configuration: expect.objectContaining({
          baseDestination: "https://api.example.com",
          operations: expect.any(Array),
        }),
      }),
    ),
  );
});
it("does not fetch connection data without integration.read", async () => {
  vi.mocked(session.request).mockResolvedValue({ permissions: [] });
  open();
  expect(await screen.findByRole("alert")).toHaveTextContent(
    "Integration reading is not permitted",
  );
  expect(connectionApi.list).not.toHaveBeenCalled();
});
