import { session, failureMessage, HttpFailure } from "../../lib/session";
import type { Page } from "../workflows/model";
export type State =
  | "QUEUED"
  | "RUNNING"
  | "WAITING_FOR_RETRY"
  | "WAITING_FOR_DELAY"
  | "SUCCEEDED"
  | "FAILED"
  | "CANCELLED";
export interface Execution {
  id: string;
  workflowId: string;
  versionId: string;
  definitionHash: string;
  requestedBy: string;
  source: string;
  mode: string;
  status: State;
  revision: number;
  currentPosition: number;
  createdAt: string;
  startedAt: string | null;
  completedAt: string | null;
  deadlineAt: string;
  notBefore: string;
  errorCode: string | null;
  retryOfExecutionId: string | null;
  retryEligible: boolean;
}
export interface Step {
  retryAt?: string | null;
  nodeId: string;
  position: number;
  type: string;
  status: string;
  attemptCount: number;
  startedAt: string | null;
  completedAt: string | null;
  output: unknown;
  errorCode: string | null;
}
export interface Attempt {
  id: string;
  nodeId: string;
  attemptNumber: number;
  startedAt: string;
  completedAt: string;
  outcome: string;
  errorCode: string | null;
}
export interface Event {
  sequence: number;
  occurredAt: string;
  code: string;
  nodeId: string | null;
}
export const active = (state: State) =>
  !["SUCCEEDED", "FAILED", "CANCELLED"].includes(state);
const base = (org: string) =>
  `/api/v1/organizations/${encodeURIComponent(org)}/executions`;
const item = (org: string, id: string) =>
  `${base(org)}/${encodeURIComponent(id)}`;
const post = (body: unknown, key?: string) => ({
  method: "POST",
  headers: {
    "Content-Type": "application/json",
    ...(key ? { "Idempotency-Key": key } : {}),
  },
  body: JSON.stringify(body),
});
export const executionApi = {
  start: (
    org: string,
    workflow: string,
    versionId: string,
    delaySeconds: number,
    key: string,
  ) =>
    session.request<Execution>(
      `/api/v1/organizations/${encodeURIComponent(org)}/workflows/${encodeURIComponent(workflow)}/executions`,
      post({ versionId, input: {}, delaySeconds }, key),
    ),
  list: (org: string, page: number, workflow: string, status: string) =>
    session.request<Page<Execution>>(
      `${base(org)}?page=${page}&size=10${workflow ? `&workflowId=${encodeURIComponent(workflow)}` : ""}${status ? `&status=${encodeURIComponent(status)}` : ""}`,
    ),
  get: (org: string, id: string) => session.request<Execution>(item(org, id)),
  steps: (org: string, id: string) =>
    session.request<Step[]>(`${item(org, id)}/steps`),
  attempts: (org: string, id: string, page: number) =>
    session.request<Page<Attempt>>(
      `${item(org, id)}/attempts?page=${page}&size=100`,
    ),
  events: (org: string, id: string, page: number) =>
    session.request<Page<Event>>(
      `${item(org, id)}/events?page=${page}&size=100`,
    ),
  cancel: (org: string, id: string, expectedRevision: number, reason: string) =>
    session.request<Execution>(
      `${item(org, id)}/cancel`,
      post({ expectedRevision, reason }),
    ),
  retry: (
    org: string,
    id: string,
    expectedRevision: number,
    reason: string,
    key: string,
  ) =>
    session.request<Execution>(
      `${item(org, id)}/retry`,
      post({ expectedRevision, reason }, key),
    ),
};
export function executionFailure(error: unknown): string {
  if (error instanceof HttpFailure) {
    if (error.code === "UNSUPPORTED_RUNTIME_NODE")
      return "This version contains an approval gate or another unsupported runtime node. Approval processing arrives in Phase 4. No execution was created.";
    if (error.code === "WORKFLOW_NOT_PUBLISHED")
      return "Publish a valid workflow before starting an execution.";
    if (error.code === "RETRY_INELIGIBLE")
      return "This failure is not eligible for manual retry.";
    if (error.code === "IDEMPOTENCY_CONFLICT")
      return "This request key already identifies a different version or input. Reload and begin a new explicit request.";
  }
  return failureMessage(error);
}
