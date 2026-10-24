import { render, screen, fireEvent, waitFor } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import { beforeEach, afterEach, it, expect, vi } from "vitest";
import { session, HttpFailure } from "../../lib/session";
import { workflowApi } from "../workflows/api";
import { Automations } from "./Automations";
import {
  automationApi,
  type AutomationPrincipal,
  type WebhookEndpoint,
} from "./automationApi";
const user = {
  id: "manager",
  email: "manager@example.invalid",
  displayName: "Manager",
};
const principal: AutomationPrincipal = {
  id: "principal",
  name: "Triage principal",
  workflowId: "workflow",
  versionId: "version",
  definitionHash: "a".repeat(64),
  actorType: "AUTOMATION",
  status: "ACTIVE",
  revision: 0,
  createdBy: "manager",
  createdAt: "2026-10-24T10:00:00Z",
  grants: [
    {
      nodeId: "assign",
      integrationId: "support",
      revision: 2,
      operation: "support.assignBatch",
      classification: "WRITE",
    },
  ],
};
const endpoint: WebhookEndpoint = {
  id: "endpoint",
  name: "Triage endpoint",
  principalId: "principal",
  workflowId: "workflow",
  versionId: "version",
  definitionHash: principal.definitionHash,
  revision: 0,
  status: "ACTIVE",
  createdBy: "manager",
  createdAt: principal.createdAt,
  revisionAuthor: "manager",
  updatedAt: principal.createdAt,
};
const version = {
  id: "version",
  workflowId: "workflow",
  versionNumber: 2,
  sourceRevision: 3,
  schemaVersion: 2,
  canonicalizationVersion: 2,
  name: "Published triage",
  description: "",
  definitionHash: principal.definitionHash,
  publishedBy: "manager",
  publishedAt: principal.createdAt,
};
const workflow = {
  id: "workflow",
  name: "Saved triage",
  description: "",
  status: "ACTIVE" as const,
  revision: 4,
  currentPublishedVersionId: "version",
  updatedAt: principal.createdAt,
};
const page = <T,>(items: T[]) => ({
  items,
  page: 0,
  size: 10,
  totalElements: items.length,
  totalPages: items.length ? 1 : 0,
});
const secret = "synthetic-one-time-key-canary";
beforeEach(() => {
  vi.spyOn(session, "request").mockResolvedValue({
    membershipId: "member",
    permissions: [
      "integration.read",
      "integration.manage",
      "execution.run",
      "execution.read",
      "workflow.read",
      "capability.write",
    ],
  });
  vi.spyOn(automationApi, "principals").mockResolvedValue(page([principal]));
  vi.spyOn(automationApi, "endpoints").mockResolvedValue(page([endpoint]));
  vi.spyOn(automationApi, "endpoint").mockResolvedValue({
    endpoint,
    signingSecret: secret,
  });
  vi.spyOn(automationApi, "rotate").mockResolvedValue({
    endpoint: { ...endpoint, revision: 1 },
    signingSecret: secret,
  });
  vi.spyOn(automationApi, "revoke").mockResolvedValue({
    ...principal,
    status: "REVOKED",
    revision: 1,
  });
  vi.spyOn(automationApi, "revokeEndpoint").mockResolvedValue({
    ...endpoint,
    status: "REVOKED",
    revision: 1,
  });
  vi.spyOn(automationApi, "deliveries").mockResolvedValue(
    page([
      {
        deliveryId: "delivery-1",
        executionId: "execution",
        endpointRevision: 0,
        principalId: "principal",
        acceptedAt: principal.createdAt,
      },
    ]),
  );
  vi.spyOn(automationApi, "create").mockResolvedValue(principal);
  vi.spyOn(workflowApi, "list").mockResolvedValue(page([workflow]));
  vi.spyOn(workflowApi, "get").mockResolvedValue({
    workflow,
    definition: { schemaVersion: 2, nodes: [{ type: "manualTrigger" }] },
    currentPublishedVersion: version,
  });
  vi.spyOn(workflowApi, "version").mockResolvedValue({
    version,
    definition: { schemaVersion: 2, nodes: [{ type: "webhookTrigger" }] },
  });
});
afterEach(() => vi.restoreAllMocks());
function open() {
  const client = new QueryClient({
    defaultOptions: {
      queries: { retry: false, staleTime: Infinity },
      mutations: { retry: false },
    },
  });
  const result = render(
    <QueryClientProvider client={client}>
      <MemoryRouter initialEntries={["/organizations/org/automations"]}>
        <Routes>
          <Route
            path="/organizations/:id/automations"
            element={<Automations user={user} />}
          />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  );
  return { ...result, client };
}
async function endpointForm() {
  await screen.findByText("Triage endpoint");
  fireEvent.change(screen.getByLabelText("Endpoint name"), {
    target: { value: "New endpoint" },
  });
  fireEvent.change(screen.getByLabelText("Active automation principal"), {
    target: { value: "principal" },
  });
}
it("loads persisted scope without creating authority or accepting deliveries", async () => {
  open();
  await screen.findByText("Triage endpoint");
  expect(
    screen.getByText("support.assignBatch · WRITE", { exact: false }),
  ).toBeInTheDocument();
  expect(automationApi.create).not.toHaveBeenCalled();
  expect(automationApi.endpoint).not.toHaveBeenCalled();
  expect(screen.queryByLabelText("Signing key")).not.toBeInTheDocument();
  expect(screen.getByLabelText("Callback path")).toHaveValue(
    "/api/v1/webhooks/endpoint",
  );
});
it("requires explicit endpoint confirmation and keeps one-time keys out of query and persistent storage", async () => {
  const { client } = open();
  const storage = vi.spyOn(Storage.prototype, "setItem");
  await endpointForm();
  expect(
    screen.getByRole("button", { name: "Create webhook endpoint" }),
  ).toBeDisabled();
  fireEvent.click(
    screen.getByLabelText(
      "Create this signed endpoint and reveal its key once.",
    ),
  );
  fireEvent.click(
    screen.getByRole("button", { name: "Create webhook endpoint" }),
  );
  const key = await screen.findByLabelText("Signing key");
  expect(automationApi.endpoint).toHaveBeenCalledWith(
    "org",
    "New endpoint",
    "principal",
  );
  expect(key).toHaveAttribute("type", "password");
  expect(key).toHaveValue(secret);
  expect(
    JSON.stringify(
      client
        .getQueryCache()
        .getAll()
        .map((q) => q.state.data),
    ),
  ).not.toContain(secret);
  expect(storage).not.toHaveBeenCalled();
  fireEvent.click(screen.getByRole("button", { name: "Dismiss signing key" }));
  expect(screen.queryByLabelText("Signing key")).not.toBeInTheDocument();
});
it("reveals and copies only the newly generated key on request", async () => {
  const writeText = vi.fn().mockResolvedValue(undefined);
  vi.stubGlobal("navigator", { ...navigator, clipboard: { writeText } });
  try {
    open();
    await endpointForm();
    fireEvent.click(
      screen.getByLabelText(
        "Create this signed endpoint and reveal its key once.",
      ),
    );
    fireEvent.click(
      screen.getByRole("button", { name: "Create webhook endpoint" }),
    );
    await screen.findByLabelText("Signing key");
    expect(writeText).not.toHaveBeenCalled();
    fireEvent.click(screen.getByLabelText("Show signing key"));
    expect(screen.getByLabelText("Signing key")).toHaveAttribute(
      "type",
      "text",
    );
    fireEvent.click(screen.getByRole("button", { name: "Copy signing key" }));
    await screen.findByText("Signing key copied.");
    expect(writeText).toHaveBeenCalledWith(secret);
  } finally {
    vi.unstubAllGlobals();
  }
});
it("confirms rotation against the persisted revision and shows a new one-time key", async () => {
  open();
  await screen.findByText("Triage endpoint");
  fireEvent.click(
    screen.getByRole("button", { name: "Rotate key for Triage endpoint" }),
  );
  expect(automationApi.rotate).not.toHaveBeenCalled();
  fireEvent.click(screen.getByRole("button", { name: "Confirm key rotation" }));
  await screen.findByLabelText("Signing key");
  expect(automationApi.rotate).toHaveBeenCalledWith("org", "endpoint", 0);
});
it("confirms principal and endpoint revocation without erasing history", async () => {
  open();
  await screen.findByText("Triage endpoint");
  fireEvent.click(
    screen.getByRole("button", { name: "Revoke principal Triage principal" }),
  );
  expect(automationApi.revoke).not.toHaveBeenCalled();
  fireEvent.click(
    screen.getByRole("button", { name: "Keep current configuration" }),
  );
  expect(automationApi.revoke).not.toHaveBeenCalled();
  fireEvent.click(
    screen.getByRole("button", { name: "Revoke endpoint Triage endpoint" }),
  );
  fireEvent.click(screen.getByRole("button", { name: "Confirm revocation" }));
  await waitFor(() =>
    expect(automationApi.revokeEndpoint).toHaveBeenCalledWith(
      "org",
      "endpoint",
      0,
    ),
  );
});
it("pins the inspected published snapshot even when the draft has a different trigger", async () => {
  open();
  await screen.findByText("Triage endpoint");
  fireEvent.click(screen.getByText("Create a scoped automation principal"));
  await screen.findByLabelText("Workflow to pin");
  fireEvent.change(screen.getByLabelText("Principal name"), {
    target: { value: "New authority" },
  });
  fireEvent.change(screen.getByLabelText("Workflow to pin"), {
    target: { value: "workflow" },
  });
  await screen.findByText(/Pin published version 2/);
  expect(workflowApi.version).toHaveBeenCalledWith(
    "org",
    "workflow",
    "version",
  );
  expect(
    screen.getByRole("button", { name: "Create automation principal" }),
  ).toBeDisabled();
  fireEvent.click(screen.getByLabelText(/Grant only this immutable workflow/));
  fireEvent.click(
    screen.getByRole("button", { name: "Create automation principal" }),
  );
  await screen.findByText(
    "Scoped principal created. No endpoint, signing key or execution was created.",
  );
  expect(automationApi.create).toHaveBeenCalledWith(
    "org",
    "New authority",
    "workflow",
    "version",
  );
});
it("rejects a published manual workflow regardless of webhook draft contents", async () => {
  vi.mocked(workflowApi.version).mockResolvedValue({
    version,
    definition: { schemaVersion: 2, nodes: [{ type: "manualTrigger" }] },
  });
  open();
  await screen.findByText("Triage endpoint");
  fireEvent.click(screen.getByText("Create a scoped automation principal"));
  await screen.findByLabelText("Workflow to pin");
  fireEvent.change(screen.getByLabelText("Workflow to pin"), {
    target: { value: "workflow" },
  });
  await screen.findByText(
    "Choose a published schema-v2 workflow with a webhook trigger.",
  );
  expect(
    screen.getByRole("button", { name: "Create automation principal" }),
  ).toBeDisabled();
  expect(automationApi.create).not.toHaveBeenCalled();
});
it("reads and refreshes persisted delivery history with actual execution links", async () => {
  open();
  await screen.findByText("Triage endpoint");
  fireEvent.click(
    screen.getByRole("button", {
      name: "Delivery history for Triage endpoint",
    }),
  );
  const link = await screen.findByRole("link", { name: "delivery-1" });
  expect(link).toHaveAttribute(
    "href",
    "/organizations/org/executions/execution",
  );
  expect(automationApi.deliveries).toHaveBeenCalledWith("org", "endpoint", 0);
  fireEvent.click(
    screen.getByRole("button", { name: "Refresh delivery history" }),
  );
  await waitFor(() =>
    expect(automationApi.deliveries).toHaveBeenCalledTimes(2),
  );
});
it("shows safe mutation failure without a key or success claim", async () => {
  vi.mocked(automationApi.rotate).mockRejectedValue(
    new HttpFailure(409, "WEBHOOK_CONFLICT"),
  );
  open();
  await screen.findByText("Triage endpoint");
  fireEvent.click(
    screen.getByRole("button", { name: "Rotate key for Triage endpoint" }),
  );
  fireEvent.click(screen.getByRole("button", { name: "Confirm key rotation" }));
  await screen.findByRole("alert");
  expect(screen.queryByLabelText("Signing key")).not.toBeInTheDocument();
  expect(
    screen.queryByText("Signing key rotated. No execution was started."),
  ).not.toBeInTheDocument();
});
it("hides mutations from readers and denies the workspace without live read permission", async () => {
  vi.mocked(session.request).mockResolvedValue({
    permissions: ["integration.read", "execution.read"],
  });
  const result = open();
  await screen.findByText("Triage endpoint");
  expect(
    screen.queryByRole("button", { name: "Create webhook endpoint" }),
  ).not.toBeInTheDocument();
  expect(
    screen.queryByRole("button", { name: "Rotate key for Triage endpoint" }),
  ).not.toBeInTheDocument();
  result.unmount();
  vi.mocked(session.request).mockResolvedValue({ permissions: [] });
  open();
  await screen.findByRole("alert");
  expect(automationApi.principals).toHaveBeenCalledTimes(1);
});
