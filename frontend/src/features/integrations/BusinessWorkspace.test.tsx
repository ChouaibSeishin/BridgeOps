import { render, screen, fireEvent, waitFor } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import { beforeEach, afterEach, it, expect, vi } from "vitest";
import { session } from "../../lib/session";
import { BusinessWorkspace } from "./BusinessWorkspace";
import { businessApi } from "./businessApi";
const user = {
  id: "owner",
  email: "owner@example.invalid",
  displayName: "Synthetic Owner",
};
beforeEach(() => {
  vi.spyOn(session, "request").mockResolvedValue({
    permissions: ["capability.read", "capability.write", "integration.manage"],
  });
  vi.spyOn(businessApi, "tickets").mockResolvedValue({
    items: [
      {
        id: "ticket",
        title: "Persisted billing issue",
        status: "OPEN",
        priority: "HIGH",
        category: "BILLING",
        assignedTeamId: "team",
        revision: 3,
        provenance: "SEEDED_EXAMPLE",
        updatedAt: "2026-10-21T12:00:00Z",
      },
    ],
    page: 0,
    size: 10,
    totalElements: 1,
    totalPages: 1,
  });
  vi.spyOn(businessApi, "teams").mockResolvedValue([
    { id: "team", name: "Billing support", provenance: "SEEDED_EXAMPLE" },
  ]);
  vi.spyOn(businessApi, "responsibilities").mockResolvedValue([
    { category: "BILLING", teamId: "team" },
  ]);
  vi.spyOn(businessApi, "notifications").mockResolvedValue({
    items: [
      {
        id: "notification",
        message: "Persisted internal update",
        status: "DELIVERED_INTERNAL",
        provenance: "LIVE_EXECUTION",
        createdAt: "2026-10-21T12:00:00Z",
      },
    ],
    page: 0,
    size: 10,
    totalElements: 1,
    totalPages: 1,
  });
  vi.spyOn(businessApi, "attempts").mockResolvedValue([
    {
      number: 1,
      outcome: "DELIVERED_INTERNAL",
      occurredAt: "2026-10-21T12:00:00Z",
    },
  ]);
  vi.spyOn(businessApi, "seed").mockResolvedValue({
    created: true,
    teams: 3,
    tickets: 5,
    notifications: 1,
    provenance: "SEEDED_EXAMPLE",
  });
});
afterEach(() => vi.restoreAllMocks());
function open() {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  });
  return render(
    <QueryClientProvider client={client}>
      <MemoryRouter initialEntries={["/organizations/org/business"]}>
        <Routes>
          <Route
            path="/organizations/:id/business"
            element={<BusinessWorkspace user={user} />}
          />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  );
}
it("shows persisted ticket revision, team mapping and honest notification status", async () => {
  open();
  expect(await screen.findByText("Persisted billing issue")).toBeVisible();
  expect(screen.getByText(/Revision 3/)).toBeVisible();
  expect(screen.getByText(/Assigned team: Billing support/)).toBeVisible();
  expect(screen.getByText(/DELIVERED_INTERNAL · LIVE_EXECUTION/)).toBeVisible();
  expect(
    screen.getByText(/No email, SMS or third-party delivery is implied/),
  ).toBeVisible();
});
it("requires a separate explicit confirmation before opt-in seed", async () => {
  open();
  fireEvent.click(
    await screen.findByRole("button", {
      name: "Add example data",
    }),
  );
  expect(businessApi.seed).not.toHaveBeenCalled();
  fireEvent.click(
    screen.getByRole("button", { name: "Confirm add example data" }),
  );
  await waitFor(() => expect(businessApi.seed).toHaveBeenCalledWith("org"));
  expect(
    await screen.findByText(/Added 5 example tickets, 3 teams/),
  ).toBeVisible();
});
it("reports idempotent setup without claiming new examples were created", async () => {
  vi.mocked(businessApi.seed).mockResolvedValue({
    created: false,
    teams: 3,
    tickets: 5,
    notifications: 1,
    provenance: "SEEDED_EXAMPLE",
  });
  open();
  fireEvent.click(
    await screen.findByRole("button", {
      name: "Add example data",
    }),
  );
  fireEvent.click(
    screen.getByRole("button", { name: "Confirm add example data" }),
  );
  expect(
    await screen.findByText(
      "Example data was already installed. Existing data was retained.",
    ),
  ).toBeVisible();
});
it("keeps read-only viewers from setup controls", async () => {
  vi.mocked(session.request).mockResolvedValue({
    permissions: ["capability.read"],
  });
  open();
  expect(await screen.findByText("Persisted billing issue")).toBeVisible();
  expect(
    screen.queryByRole("button", { name: "Add example data" }),
  ).not.toBeInTheDocument();
});
it("does not fetch business records when permission is denied", async () => {
  vi.mocked(session.request).mockResolvedValue({ permissions: [] });
  open();
  expect(await screen.findByRole("alert")).toHaveTextContent(
    "Business-data reading is not permitted",
  );
  expect(businessApi.tickets).not.toHaveBeenCalled();
});
it("uses server filters and persisted notification attempts", async () => {
  open();
  await screen.findByText("Persisted billing issue");
  fireEvent.change(screen.getByLabelText("Priority"), {
    target: { value: "HIGH" },
  });
  await waitFor(() =>
    expect(businessApi.tickets).toHaveBeenCalledWith("org", 0, "", "HIGH", ""),
  );
  fireEvent.click(
    screen.getByRole("button", { name: "Inspect internal delivery" }),
  );
  expect(
    await screen.findByText(/Attempt 1: DELIVERED_INTERNAL/),
  ).toBeVisible();
  expect(businessApi.attempts).toHaveBeenCalledWith("org", "notification");
});
it("renders dependency failure without fabricated ticket data", async () => {
  vi.mocked(businessApi.tickets).mockRejectedValue(new Error("Unavailable"));
  open();
  expect(await screen.findByRole("alert")).toBeVisible();
  expect(screen.queryByText("Persisted billing issue")).not.toBeInTheDocument();
});
