import { session } from "../../lib/session";
import type { Page } from "../workflows/model";
export interface OperationDescriptor {
  id: string;
  classification: string;
  requiresApproval: boolean;
  requestFields: Record<string, string>;
  responseFields: Record<string, string>;
}
export interface IntegrationDefinition {
  type: string;
  name: string;
  description: string;
  implementationVersion: number;
  operations: OperationDescriptor[];
}
export interface ConnectionSummary {
  id: string;
  name: string;
  description: string;
  type: string;
  implementationVersion: number;
  revision: number;
  enabled: boolean;
  updatedAt: string;
  health: {
    status: string;
    code: string | null;
    checkedAt: string | null;
    revision: number | null;
  };
}
export interface ManagedConnection {
  connection: ConnectionSummary;
  configuration: Record<string, unknown>;
  credentialConfigured: boolean;
}
export interface ConnectionInput {
  name: string;
  description: string;
  type: string;
  configuration: Record<string, unknown>;
  credential?: string;
}
const base = (org: string) =>
  `/api/v1/organizations/${encodeURIComponent(org)}`;
const body = (method: string, value: unknown) => ({
  method,
  headers: { "Content-Type": "application/json" },
  body: JSON.stringify(value),
});
export const connectionApi = {
  definitions: (org: string) =>
    session.request<IntegrationDefinition[]>(
      `${base(org)}/integration-definitions`,
    ),
  list: (org: string, page: number) =>
    session.request<Page<ConnectionSummary>>(
      `${base(org)}/integrations?page=${page}&size=10`,
    ),
  get: (org: string, id: string) =>
    session.request<ConnectionSummary>(
      `${base(org)}/integrations/${encodeURIComponent(id)}`,
    ),
  configuration: (org: string, id: string) =>
    session.request<ManagedConnection>(
      `${base(org)}/integrations/${encodeURIComponent(id)}/configuration`,
    ),
  operations: (org: string, id: string) =>
    session.request<OperationDescriptor[]>(
      `${base(org)}/integrations/${encodeURIComponent(id)}/operations`,
    ),
  create: (org: string, value: ConnectionInput) =>
    session.request<ManagedConnection>(
      `${base(org)}/integrations`,
      body("POST", value),
    ),
  update: (
    org: string,
    id: string,
    value: Omit<ConnectionInput, "type"> & {
      expectedRevision: number;
      enabled: boolean;
      removeCredential: boolean;
    },
  ) =>
    session.request<ManagedConnection>(
      `${base(org)}/integrations/${encodeURIComponent(id)}`,
      body("PUT", value),
    ),
  check: (org: string, id: string, expectedRevision: number) =>
    session.request<ConnectionSummary>(
      `${base(org)}/integrations/${encodeURIComponent(id)}/test`,
      body("POST", { expectedRevision, effectsAcknowledged: true }),
    ),
};
