import {
  render,
  screen,
  fireEvent,
  waitFor,
  within,
} from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import { beforeEach, afterEach, it, expect, vi } from "vitest";
import { session, HttpFailure } from "../../lib/session";
import { ExecutionStart } from "./ExecutionStart";
import { ExecutionList, ExecutionDetail } from "./Executions";
import { executionApi, active } from "./api";
import type { Execution } from "./api";
const user = {
  id: "synthetic-user",
  email: "monitor@example.invalid",
  displayName: "Synthetic",
};
const execution = (status: Execution["status"] = "QUEUED"): Execution => ({
  id: "execution",
  workflowId: "workflow",
  versionId: "version",
  definitionHash: "a".repeat(64),
  requestedBy: "actor",
  source: "MANUAL",
  mode: "LIVE_EXECUTION",
  status,
  revision: 0,
  currentPosition: 0,
  createdAt: "2026-10-17T12:00:00Z",
  startedAt: null,
  completedAt: active(status) ? null : "2026-10-17T12:00:05Z",
  deadlineAt: "2026-10-18T12:00:00Z",
  notBefore: "2026-10-17T12:00:00Z",
  errorCode: null,
  retryOfExecutionId: null,
  retryEligible: false,
});
const page = <T,>(items: T[]) => ({
  items,
  page: 0,
  size: 100,
  totalElements: items.length,
  totalPages: items.length ? 1 : 0,
});
beforeEach(() => {
  vi.spyOn(session, "request").mockResolvedValue({
    permissions: [
      "execution.read",
      "execution.run",
      "execution.cancel",
      "execution.retry",
    ],
  });
  vi.spyOn(executionApi, "get").mockResolvedValue(execution("SUCCEEDED"));
  vi.spyOn(executionApi, "list").mockResolvedValue(page([]));
  vi.spyOn(executionApi, "steps").mockResolvedValue([
    {
      nodeId: "stable-node",
      position: 0,
      type: "syntheticAction",
      status: "SUCCEEDED",
      attemptCount: 1,
      startedAt: "2026-10-17T12:00:00Z",
      completedAt: "2026-10-17T12:00:05Z",
      output: { recorded: true },
      errorCode: null,
    },
  ]);
  vi.spyOn(executionApi, "attempts").mockResolvedValue(
    page([
      {
        id: "attempt",
        nodeId: "stable-node",
        attemptNumber: 1,
        startedAt: "2026-10-17T12:00:00Z",
        completedAt: "2026-10-17T12:00:05Z",
        outcome: "SUCCEEDED",
        errorCode: null,
      },
    ]),
  );
  vi.spyOn(executionApi, "events").mockResolvedValue(
    page([
      {
        sequence: 1,
        occurredAt: "2026-10-17T12:00:05Z",
        code: "RECORD_COMMITTED",
        nodeId: "stable-node",
      },
    ]),
  );
});
afterEach(() => vi.restoreAllMocks());
function show(
  route = "/organizations/org/executions/execution",
  start = false,
) {
  render(
    <QueryClientProvider
      client={
        new QueryClient({
          defaultOptions: { queries: { retry: false, gcTime: 0 } },
        })
      }
    >
      <MemoryRouter initialEntries={[route]}>
        <Routes>
          <Route
            path="/start"
            element={
              start ? (
                <ExecutionStart
                  organization="org"
                  workflow="workflow"
                  version="version"
                  permissions={["execution.run", "execution.read"]}
                />
              ) : null
            }
          />
          <Route
            path="/organizations/:id/executions"
            element={<ExecutionList user={user} />}
          />
          <Route
            path="/organizations/:id/executions/:executionId"
            element={<ExecutionDetail user={user} />}
          />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  );
}
it("confirms the exact snapshot, shows submission state and preserves an idempotency key after unavailability", async () => {
  const start = vi
    .spyOn(executionApi, "start")
    .mockRejectedValueOnce(new HttpFailure(503, "DEPENDENCY_UNAVAILABLE"))
    .mockResolvedValue(execution("SUCCEEDED"));
  show("/start", true);
  fireEvent.click(
    screen.getByRole("button", { name: "Run published version" }),
  );
  expect(screen.getByRole("dialog")).toHaveTextContent(
    "No external service or approval decision",
  );
  fireEvent.change(screen.getByLabelText("Start delay in seconds"), {
    target: { value: "12" },
  });
  fireEvent.click(
    screen.getByRole("button", { name: "Confirm internal execution" }),
  );
  await screen.findByRole("alert");
  fireEvent.click(
    screen.getByRole("button", { name: "Confirm internal execution" }),
  );
  await screen.findByRole("heading", { name: "Execution detail" });
  expect(start).toHaveBeenCalledTimes(2);
  expect(start.mock.calls[0]).toEqual(start.mock.calls[1]);
  expect(start.mock.calls[0]?.slice(0, 4)).toEqual([
    "org",
    "workflow",
    "version",
    12,
  ]);
});
it("reports unsupported approval definitions without fabricating execution success", async () => {
  vi.spyOn(executionApi, "start").mockRejectedValue(
    new HttpFailure(422, "UNSUPPORTED_RUNTIME_NODE"),
  );
  show("/start", true);
  fireEvent.click(
    screen.getByRole("button", { name: "Run published version" }),
  );
  fireEvent.click(
    screen.getByRole("button", { name: "Confirm internal execution" }),
  );
  expect(await screen.findByRole("alert")).toHaveTextContent(
    "No execution was created",
  );
  expect(
    screen.queryByRole("heading", { name: "Execution detail" }),
  ).not.toBeInTheDocument();
});
it("renders persisted result, attempts, timeline and immutable version link with terminal controls absent", async () => {
  show();
  await screen.findByText("RECORD COMMITTED · Synthetic action 1");
  expect(screen.getByText(/"recorded": true/)).toBeInTheDocument();
  expect(screen.getByText(/attempt 1 · SUCCEEDED/)).toBeInTheDocument();
  expect(
    screen.getByRole("link", { name: "Inspect pinned workflow version" }),
  ).toHaveAttribute(
    "href",
    "/organizations/org/workflows/workflow/versions/version",
  );
  expect(
    screen.queryByRole("button", { name: "Cancel execution" }),
  ).not.toBeInTheDocument();
  expect(
    screen.queryByRole("button", { name: "Retry eligible failure" }),
  ).not.toBeInTheDocument();
  expect(active("SUCCEEDED")).toBe(false);
  expect(active("FAILED")).toBe(false);
  expect(active("CANCELLED")).toBe(false);
  expect(active("WAITING_FOR_RETRY")).toBe(true);
});
it("cancels a waiting execution through explicit confirmation with the current revision", async () => {
  vi.mocked(executionApi.get).mockResolvedValue(execution("WAITING_FOR_DELAY"));
  const cancel = vi
    .spyOn(executionApi, "cancel")
    .mockResolvedValue(execution("CANCELLED"));
  show();
  fireEvent.click(
    await screen.findByRole("button", { name: "Cancel execution" }),
  );
  expect(screen.getByRole("dialog")).toHaveTextContent(
    "Completed effects will remain",
  );
  fireEvent.change(screen.getByLabelText("Reason"), {
    target: { value: "Stop waiting" },
  });
  fireEvent.click(screen.getByRole("button", { name: "Confirm cancellation" }));
  await waitFor(() =>
    expect(cancel).toHaveBeenCalledWith("org", "execution", 0, "Stop waiting"),
  );
});
it("shows retry only for backend-confirmed eligible failures and creates a linked execution", async () => {
  vi.mocked(executionApi.get).mockResolvedValue({
    ...execution("FAILED"),
    errorCode: "TRANSIENT_EXHAUSTED",
    retryEligible: true,
  });
  const retry = vi
    .spyOn(executionApi, "retry")
    .mockResolvedValue({ ...execution(), id: "linked" });
  show();
  fireEvent.click(
    await screen.findByRole("button", { name: "Retry eligible failure" }),
  );
  expect(screen.getByRole("dialog")).toHaveTextContent(
    "original remains failed",
  );
  fireEvent.change(screen.getByLabelText("Reason"), {
    target: { value: "Retry recovered dependency" },
  });
  fireEvent.click(screen.getByRole("button", { name: "Confirm linked retry" }));
  await waitFor(() =>
    expect(retry).toHaveBeenCalledWith(
      "org",
      "execution",
      0,
      "Retry recovered dependency",
      expect.any(String),
    ),
  );
});
it("preserves a conflict reason and reports mutation failure before refreshing", async () => {
  vi.mocked(executionApi.get).mockResolvedValue(execution());
  vi.spyOn(executionApi, "cancel").mockRejectedValue(
    new HttpFailure(409, "STALE_REVISION"),
  );
  show();
  fireEvent.click(
    await screen.findByRole("button", { name: "Cancel execution" }),
  );
  fireEvent.change(screen.getByLabelText("Reason"), {
    target: { value: "Keep this reason" },
  });
  fireEvent.click(screen.getByRole("button", { name: "Confirm cancellation" }));
  expect(
    await within(screen.getByRole("dialog")).findByRole("alert"),
  ).toHaveTextContent("Reload");
  expect(screen.getByLabelText("Reason")).toHaveValue("Keep this reason");
});
it("readers see persisted executions but cannot cancel or retry", async () => {
  vi.mocked(session.request).mockResolvedValue({
    permissions: ["execution.read"],
  });
  vi.mocked(executionApi.get).mockResolvedValue({
    ...execution("FAILED"),
    retryEligible: true,
  });
  show();
  await screen.findByRole("heading", { name: "Execution state" });
  expect(
    screen.queryByRole("button", { name: "Retry eligible failure" }),
  ).not.toBeInTheDocument();
  expect(
    screen.queryByRole("button", { name: "Cancel execution" }),
  ).not.toBeInTheDocument();
});
it("renders loading, empty, filtered history and a recoverable list error", async () => {
  vi.mocked(executionApi.list)
    .mockRejectedValueOnce(new HttpFailure(503, "DEPENDENCY_UNAVAILABLE"))
    .mockResolvedValue(page([]));
  show("/organizations/org/executions?workflowId=workflow");
  await screen.findByRole("alert");
  fireEvent.click(screen.getByRole("button", { name: "Try again" }));
  await screen.findByText("No executions match this view.");
  fireEvent.change(screen.getByLabelText("Execution status"), {
    target: { value: "FAILED" },
  });
  await waitFor(() =>
    expect(executionApi.list).toHaveBeenLastCalledWith(
      "org",
      0,
      "workflow",
      "FAILED",
    ),
  );
});
it("denies missing read capability before requesting any execution data", async () => {
  vi.mocked(session.request).mockResolvedValue({ permissions: [] });
  show();
  expect(await screen.findByRole("alert")).toHaveTextContent("not permitted");
  expect(executionApi.get).not.toHaveBeenCalled();
});
