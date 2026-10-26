import { session, HttpFailure, failureMessage } from "../../lib/session";
import type { Page } from "../workflows/model";
export type ApprovalStatus =
  "PENDING" | "APPROVED" | "REJECTED" | "EXPIRED" | "INVALIDATED" | "CANCELLED";
export interface Approval {
  id: string;
  executionId: string;
  workflowId: string;
  workflowName: string;
  versionNumber: number;
  versionId: string;
  definitionHash: string;
  requestedBy: string | null;
  actor?: import("../../lib/actors").ActorReference;
  origin?: import("../../lib/actors").AutomationOrigin | null;
  requesterName: string;
  gateNodeId: string;
  actionNodeId: string;
  prompt: string;
  capability: string;
  operation: string;
  arguments: Record<string, unknown>;
  canonicalArguments?: string | null;
  intentSchemaVersion?: number;
  classification?: "READ" | "WRITE";
  connection?: {
    id: string;
    revision: number;
    implementationVersion: number;
    operationId: string;
  } | null;
  argumentHash: string;
  intentHash: string;
  policyVersion: number;
  policySnapshot: { required: boolean; lifetimeSeconds: number };
  createdAt: string;
  expiresAt: string;
  deadlineAt: string;
  status: ApprovalStatus;
  revision: number;
  finalizedAt: string | null;
  errorCode: string | null;
  executionStatus: string;
  actionable: boolean;
  invalidated: boolean;
  decision: {
    id: string;
    actor: string;
    actorName: string;
    decision: "APPROVE" | "REJECT";
    at: string;
    expectedRevision: number;
    reason: string | null;
  } | null;
  events: {
    sequence: number;
    occurredAt: string;
    actor: string | null;
    actorName: string;
    code: string;
  }[];
}
export interface Policy {
  version: number;
  revision: number;
  requireRecordApproval: boolean;
  lifetimeSeconds: number;
}
const base = (org: string) =>
  `/api/v1/organizations/${encodeURIComponent(org)}`;
const body = (method: string, value: unknown) => ({
  method,
  headers: { "Content-Type": "application/json" },
  body: JSON.stringify(value),
});
export const approvalApi = {
  list: (org: string, page: number, status: string, executionId = "") =>
    session.request<Page<Approval>>(
      `${base(org)}/approvals?page=${page}&size=10${status ? `&status=${encodeURIComponent(status)}` : ""}${executionId ? `&executionId=${encodeURIComponent(executionId)}` : ""}`,
    ),
  get: (org: string, id: string) =>
    session.request<Approval>(
      `${base(org)}/approvals/${encodeURIComponent(id)}`,
    ),
  decide: (
    org: string,
    id: string,
    expectedRevision: number,
    decision: "APPROVE" | "REJECT",
    reason: string,
  ) =>
    session.request<Approval>(
      `${base(org)}/approvals/${encodeURIComponent(id)}/decision`,
      body("POST", {
        expectedRevision,
        decision,
        ...(reason.trim() ? { reason: reason.trim() } : {}),
      }),
    ),
  policy: (org: string) =>
    session.request<Policy>(`${base(org)}/approval-policy`),
  updatePolicy: (
    org: string,
    expectedRevision: number,
    requireRecordApproval: boolean,
    lifetimeSeconds: number,
  ) =>
    session.request<Policy>(
      `${base(org)}/approval-policy`,
      body("PUT", { expectedRevision, requireRecordApproval, lifetimeSeconds }),
    ),
};
export function approvalFailure(error: unknown): string {
  if (error instanceof HttpFailure) {
    if (error.code === "SELF_APPROVAL_FORBIDDEN")
      return "A different eligible person must decide. Owner status does not permit self-approval.";
    if (error.status === 409)
      return "The server state changed or this request is no longer actionable. Your decision or policy update was not applied. Review the refreshed state before continuing.";
    if (error.code === "INVALID_APPROVAL_POLICY")
      return "Approval lifetime must be between 60 and 86400 seconds.";
  }
  return failureMessage(error);
}
export const terminalExecution = (state: string) =>
  ["SUCCEEDED", "FAILED", "CANCELLED"].includes(state);
