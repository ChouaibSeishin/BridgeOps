import { render, screen, fireEvent, waitFor } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import { beforeEach, afterEach, it, expect, vi } from "vitest";
import { session, HttpFailure } from "../../lib/session";
import {
  approvalApi,
  approvalFailure,
  type Approval,
  type Policy,
} from "./api";
import { ApprovalInbox, ApprovalDetail, ApprovalPolicy } from "./Approvals";
const user = {
  id: "approver",
  email: "approver@example.invalid",
  displayName: "Synthetic approver",
};
const policy: Policy = {
  version: 0,
  revision: 0,
  requireRecordApproval: false,
  lifetimeSeconds: 3600,
};
const request = (patch: Partial<Approval> = {}): Approval => ({
  id: "approval",
  executionId: "execution",
  workflowId: "workflow",
  workflowName: "Governed records",
  versionNumber: 2,
  versionId: "version",
  definitionHash: "a".repeat(64),
  requestedBy: "requester",
  requesterName: "Morgan Requester",
  gateNodeId: "stable-gate-id",
  actionNodeId: "stable-action-id",
  prompt: "Review the exact internal record",
  capability: "synthetic",
  operation: "record",
  arguments: { message: "Exact record arguments" },
  argumentHash: "b".repeat(64),
  intentHash: "c".repeat(64),
  policyVersion: 0,
  policySnapshot: { required: false, lifetimeSeconds: 3600 },
  createdAt: "2026-10-19T12:00:00Z",
  expiresAt: "2099-10-19T13:00:00Z",
  deadlineAt: "2099-10-20T12:00:00Z",
  status: "PENDING",
  revision: 0,
  finalizedAt: null,
  errorCode: null,
  executionStatus: "WAITING_FOR_APPROVAL",
  actionable: true,
  invalidated: false,
  decision: null,
  events: [
    {
      sequence: 1,
      occurredAt: "2026-10-19T12:00:00Z",
      actor: "requester",
      actorName: "Synthetic reviewer",
      code: "APPROVAL_REQUESTED",
    },
  ],
  ...patch,
});
beforeEach(() => {
  vi.spyOn(session, "request").mockResolvedValue({
    permissions: [
      "approval.read",
      "approval.decide",
      "policy.manage",
      "workflow.read",
    ],
  });
  vi.spyOn(approvalApi, "get").mockResolvedValue(request());
  vi.spyOn(approvalApi, "list").mockResolvedValue({
    items: [],
    page: 0,
    size: 10,
    totalElements: 0,
    totalPages: 0,
  });
  vi.spyOn(approvalApi, "policy").mockResolvedValue(policy);
});
afterEach(() => vi.restoreAllMocks());
it("renders the server's exact canonical numeric intent without browser rounding", async () => {
  const canonical =
    '{"fields":{"amount":9223372036854775807},"pathParameters":{}}';
  vi.mocked(approvalApi.get).mockResolvedValue(
    request({
      capability: "rest",
      operation: "update",
      intentSchemaVersion: 2,
      classification: "WRITE",
      arguments: {
        fields: { amount: Number("9223372036854775807") },
        pathParameters: {},
      },
      canonicalArguments: canonical,
    }),
  );
  show();
  const proposed = await screen.findByLabelText("Exact proposed arguments");
  expect(proposed.textContent).toBe(canonical);
  expect(proposed).toHaveTextContent("9223372036854775807");
  expect(proposed.textContent).not.toContain("9223372036854776000");
});
function show(path = "/organizations/org/approvals/approval", actor = user) {
  const client = new QueryClient({
    defaultOptions: {
      queries: { retry: false, gcTime: 0, refetchOnWindowFocus: false },
    },
  });
  return render(
    <QueryClientProvider client={client}>
      <MemoryRouter initialEntries={[path]}>
        <Routes>
          <Route
            path="/organizations/:id/approvals"
            element={<ApprovalInbox user={actor} />}
          />
          <Route
            path="/organizations/:id/approvals/:approvalId"
            element={<ApprovalDetail user={actor} />}
          />
          <Route
            path="/organizations/:id/approval-policy"
            element={<ApprovalPolicy user={actor} />}
          />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  );
}
it("shows exact business arguments and connection revision without claiming synthetic or external delivery", async () => {
  vi.mocked(approvalApi.get).mockResolvedValue(
    request({
      capability: "support",
      operation: "assignBatch",
      classification: "WRITE",
      intentSchemaVersion: 2,
      connection: {
        id: "opaque-connection",
        revision: 7,
        implementationVersion: 1,
        operationId: "assignBatch",
      },
      arguments: {
        assignments: [
          {
            ticketId: "fixture-ticket",
            expectedRevision: 3,
            currentTeamId: null,
            targetTeamId: "fixture-team",
          },
        ],
      },
    }),
  );
  show();
  await screen.findByRole("heading", { name: "Exact proposed operation" });
  expect(
    screen.getByText("support.assignBatch", { exact: true }),
  ).toBeInTheDocument();
  expect(screen.getByText(/Connection revision 7/)).toBeInTheDocument();
  expect(screen.getByLabelText("Exact proposed arguments")).toHaveTextContent(
    '"expectedRevision": 3',
  );
  expect(
    screen.getByRole("button", { name: "Approve exact operation" }),
  ).toBeInTheDocument();
  expect(
    screen.queryByText("synthetic.record", { exact: true }),
  ).not.toBeInTheDocument();
});
it("loads the persisted inbox with bounded status filtering and an honest empty state", async () => {
  show("/organizations/org/approvals");
  await screen.findByText("No approval requests match this view.");
  expect(approvalApi.list).toHaveBeenCalledWith("org", 0, "PENDING", "");
  fireEvent.change(screen.getByLabelText("Approval status"), {
    target: { value: "REJECTED" },
  });
  await waitFor(() =>
    expect(approvalApi.list).toHaveBeenCalledWith("org", 0, "REJECTED", ""),
  );
  expect(screen.getByRole("link", { name: "Approval policy" })).toHaveAttribute(
    "href",
    "/organizations/org/approval-policy",
  );
});
it("shows exact immutable intent and hides decision controls from the requester", async () => {
  vi.mocked(approvalApi.get).mockResolvedValue(
    request({ requestedBy: user.id, actionable: false }),
  );
  show();
  await screen.findByText(/A different eligible person must decide/);
  expect(
    screen.queryByRole("button", { name: "Approve internal record" }),
  ).not.toBeInTheDocument();
  expect(screen.getByLabelText("Exact proposed arguments")).toHaveTextContent(
    "Exact record arguments",
  );
  expect(
    await screen.findByRole("link", {
      name: "Governed records · published version 2",
    }),
  ).toHaveAttribute(
    "href",
    "/organizations/org/workflows/workflow/versions/version",
  );
  fireEvent.click(screen.getByText("Technical intent details"));
  expect(screen.getByText("stable-action-id")).toBeInTheDocument();
});
it("commits a confirmed approve decision without pretending the execution succeeded", async () => {
  const result = request({
    status: "APPROVED",
    revision: 1,
    actionable: false,
    decision: {
      id: "decision",
      actor: user.id,
      actorName: "Synthetic reviewer",
      decision: "APPROVE",
      at: "2026-10-19T12:05:00Z",
      expectedRevision: 0,
      reason: "Reviewed",
    },
  });
  const decide = vi
    .spyOn(approvalApi, "decide")
    .mockImplementation(async () => {
      vi.mocked(approvalApi.get).mockResolvedValue(result);
      return result;
    });
  show();
  fireEvent.click(
    await screen.findByRole("button", { name: "Approve internal record" }),
  );
  fireEvent.change(screen.getByLabelText("Optional decision reason"), {
    target: { value: "Reviewed" },
  });
  fireEvent.click(screen.getByRole("button", { name: "Confirm approval" }));
  await screen.findByText(/Decision recorded/);
  expect(decide).toHaveBeenCalledWith(
    "org",
    "approval",
    0,
    "APPROVE",
    "Reviewed",
  );
  expect(screen.getByText("WAITING FOR APPROVAL")).toBeInTheDocument();
  expect(screen.queryByText("SUCCEEDED")).not.toBeInTheDocument();
  expect(
    screen.queryByRole("button", { name: "Approve internal record" }),
  ).not.toBeInTheDocument();
});
it("renders persisted rejection and preserves a final decision after a stale-tab conflict", async () => {
  const rejected = request({
    status: "REJECTED",
    revision: 1,
    actionable: false,
    executionStatus: "FAILED",
    errorCode: "APPROVAL_REJECTED",
    decision: {
      id: "decision",
      actor: "another-approver",
      actorName: "Synthetic reviewer",
      decision: "REJECT",
      at: "2026-10-19T12:05:00Z",
      expectedRevision: 0,
      reason: "Do not record",
    },
  });
  vi.spyOn(approvalApi, "decide").mockImplementation(async () => {
    vi.mocked(approvalApi.get).mockResolvedValue(rejected);
    throw new HttpFailure(409, "APPROVAL_CONFLICT");
  });
  show();
  fireEvent.click(
    await screen.findByRole("button", { name: "Approve internal record" }),
  );
  fireEvent.click(screen.getByRole("button", { name: "Confirm approval" }));
  await screen.findByText(/Your decision or policy update was not applied/);
  expect(await screen.findByText("REJECTED")).toBeInTheDocument();
  expect(screen.getByText("Do not record")).toBeInTheDocument();
  expect(
    screen.queryByRole("button", { name: "Approve internal record" }),
  ).not.toBeInTheDocument();
});
it.each(["EXPIRED", "INVALIDATED", "CANCELLED"] as const)(
  "shows %s without allowing a new decision",
  async (status) => {
    vi.mocked(approvalApi.get).mockResolvedValue(
      request({
        status,
        actionable: false,
        invalidated: status === "INVALIDATED",
        executionStatus: status === "CANCELLED" ? "CANCELLED" : "FAILED",
      }),
    );
    show();
    await screen.findByRole("status");
    expect(
      screen.queryByRole("button", { name: "Approve internal record" }),
    ).not.toBeInTheDocument();
    expect(
      screen.queryByRole("button", { name: "Reject request" }),
    ).not.toBeInTheDocument();
  },
);
it("denies approval reads before fetching request data when the live grant is missing", async () => {
  vi.mocked(session.request).mockResolvedValue({
    permissions: ["execution.read"],
  });
  show();
  await screen.findByRole("alert");
  expect(approvalApi.get).not.toHaveBeenCalled();
});
it("renders potentially hostile argument and reason text without HTML execution", async () => {
  vi.mocked(approvalApi.get).mockResolvedValue(
    request({
      arguments: { message: '<img src=x onerror="alert(1)">' },
      status: "REJECTED",
      actionable: false,
      executionStatus: "FAILED",
      decision: {
        id: "decision",
        actor: "other",
        actorName: "Synthetic reviewer",
        decision: "REJECT",
        at: "2026-10-19T12:05:00Z",
        expectedRevision: 0,
        reason: "<script>untrusted</script>",
      },
    }),
  );
  const view = show();
  await screen.findByText("<script>untrusted</script>");
  expect(view.container.querySelector("script,img")).toBeNull();
});
it("shows policy read-only when the fixed policy-management grant is absent", async () => {
  vi.mocked(session.request).mockResolvedValue({
    permissions: ["approval.read", "approval.decide"],
  });
  show("/organizations/org/approval-policy");
  await screen.findByText(/Policy changes require/);
  expect(
    screen.queryByRole("button", { name: "Save approval policy" }),
  ).not.toBeInTheDocument();
});
it("saves only implemented policy fields against the captured revision", async () => {
  const result = {
    ...policy,
    version: 1,
    revision: 1,
    requireRecordApproval: true,
    lifetimeSeconds: 600,
  };
  const save = vi.spyOn(approvalApi, "updatePolicy").mockResolvedValue(result);
  show("/organizations/org/approval-policy");
  const checkbox = await screen.findByRole("checkbox");
  fireEvent.click(checkbox);
  fireEvent.change(screen.getByLabelText("Approval lifetime in seconds"), {
    target: { value: "600" },
  });
  fireEvent.click(screen.getByRole("button", { name: "Save approval policy" }));
  await screen.findByText(/Policy committed/);
  expect(save).toHaveBeenCalledWith("org", 0, true, 600);
  expect(screen.getByText("Effective policy version 1")).toBeInTheDocument();
});
it("keeps a stale policy draft separate until explicit reload of the server version", async () => {
  vi.spyOn(approvalApi, "updatePolicy").mockImplementation(async () => {
    vi.mocked(approvalApi.policy).mockResolvedValue({
      ...policy,
      version: 1,
      revision: 1,
      lifetimeSeconds: 120,
    });
    throw new HttpFailure(409, "STALE_REVISION");
  });
  show("/organizations/org/approval-policy");
  fireEvent.click(await screen.findByRole("checkbox"));
  fireEvent.change(screen.getByLabelText("Approval lifetime in seconds"), {
    target: { value: "600" },
  });
  fireEvent.click(screen.getByRole("button", { name: "Save approval policy" }));
  await screen.findByText(/Your draft still targets revision 0/);
  expect(screen.getByRole("checkbox")).toBeChecked();
  expect(screen.getByLabelText("Approval lifetime in seconds")).toHaveValue(
    600,
  );
  expect(
    screen.getByRole("button", { name: "Save approval policy" }),
  ).toBeDisabled();
  fireEvent.click(
    screen.getByRole("button", { name: "Reload current policy" }),
  );
  await waitFor(() =>
    expect(screen.getByLabelText("Approval lifetime in seconds")).toHaveValue(
      120,
    ),
  );
  expect(screen.getByRole("checkbox")).not.toBeChecked();
});
it("sends decision values only, omits an empty optional reason and keeps arguments server-owned", async () => {
  vi.mocked(session.request).mockResolvedValue(request());
  await approvalApi.decide("org", "approval", 0, "REJECT", "");
  const call = vi.mocked(session.request).mock.calls[0];
  expect(call?.[0]).toBe(
    "/api/v1/organizations/org/approvals/approval/decision",
  );
  expect(JSON.parse(String(call?.[1]?.body))).toEqual({
    expectedRevision: 0,
    decision: "REJECT",
  });
  expect(
    approvalFailure(new HttpFailure(403, "SELF_APPROVAL_FORBIDDEN")),
  ).toContain("different eligible person");
});

it("explains why a configuring human cannot decide an automated request", async () => {
  vi.mocked(approvalApi.get).mockResolvedValue(
    request({
      requestedBy: null,
      requesterName: "Automation principal",
      origin: {
        principal: "accepted-principal",
        principalCreator: user.id,
        endpoint: "accepted-endpoint",
        endpointRevision: 3,
        endpointAuthor: user.id,
      },
      actor: {
        type: "AUTOMATION",
        id: "accepted-principal",
        configuringHumans: [user.id],
      },
      actionable: false,
      events: [
        {
          sequence: 1,
          occurredAt: "2026-10-23T12:00:00Z",
          actor: null,
          actorName: "Automation principal",
          code: "APPROVAL_REQUESTED",
        },
      ],
    }),
  );
  show();
  expect(
    await screen.findByText(
      /You configured this automation or its accepted signing revision/,
    ),
  ).toBeInTheDocument();
  expect(
    screen.queryByRole("button", { name: /Approve/ }),
  ).not.toBeInTheDocument();
  const details = screen
    .getByText("Technical intent details")
    .closest("details")!;
  expect(details.open).toBe(false);
  expect(screen.getByText("accepted-principal")).toBeInTheDocument();
  expect(screen.getByText("accepted-endpoint")).toBeInTheDocument();
  expect(details).toHaveTextContent("revision 3");
});
it("offers an automated decision only when the backend confirms a distinct human is eligible", async () => {
  vi.mocked(approvalApi.get).mockResolvedValue(
    request({
      requestedBy: null,
      requesterName: "Automation principal",
      actor: {
        type: "AUTOMATION",
        id: "accepted-principal",
        configuringHumans: ["different-configurer"],
      },
      actionable: true,
    }),
  );
  show();
  expect(
    await screen.findByText("Requester Automation principal"),
  ).toBeInTheDocument();
  expect(screen.getByRole("button", { name: /Approve/ })).toBeInTheDocument();
});
