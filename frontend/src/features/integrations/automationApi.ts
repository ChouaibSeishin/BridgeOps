import { session } from "../../lib/session";
import type { Page } from "../workflows/model";
export interface AutomationGrant {
  nodeId: string;
  integrationId: string | null;
  revision: number | null;
  operation: string;
  classification: string;
}
export interface AutomationPrincipal {
  id: string;
  name: string;
  workflowId: string;
  versionId: string;
  definitionHash: string;
  actorType: "AUTOMATION";
  status: "ACTIVE" | "REVOKED";
  revision: number;
  createdBy: string;
  createdAt: string;
  grants: AutomationGrant[];
}
export interface WebhookEndpoint {
  id: string;
  name: string;
  principalId: string;
  workflowId: string;
  versionId: string;
  definitionHash: string;
  revision: number;
  status: "ACTIVE" | "REVOKED";
  createdBy: string;
  createdAt: string;
  revisionAuthor: string;
  updatedAt: string;
}
export interface SigningReveal {
  endpoint: WebhookEndpoint;
  signingSecret: string;
}
export interface WebhookDelivery {
  deliveryId: string;
  executionId: string;
  endpointRevision: number;
  principalId: string;
  acceptedAt: string;
}
const base = (org: string) =>
  `/api/v1/organizations/${encodeURIComponent(org)}`;
const body = (value: unknown) => ({
  method: "POST",
  headers: { "Content-Type": "application/json" },
  body: JSON.stringify(value),
});
export const automationApi = {
  principals: (org: string, page: number) =>
    session.request<Page<AutomationPrincipal>>(
      `${base(org)}/automations?page=${page}&size=10`,
    ),
  create: (org: string, name: string, workflowId: string, versionId: string) =>
    session.request<AutomationPrincipal>(
      `${base(org)}/automations`,
      body({ name, workflowId, versionId }),
    ),
  revoke: (org: string, id: string, expectedRevision: number) =>
    session.request<AutomationPrincipal>(
      `${base(org)}/automations/${encodeURIComponent(id)}/revoke`,
      body({ expectedRevision }),
    ),
  endpoints: (org: string, page: number) =>
    session.request<Page<WebhookEndpoint>>(
      `${base(org)}/webhook-endpoints?page=${page}&size=10`,
    ),
  endpoint: (org: string, name: string, principalId: string) =>
    session.request<SigningReveal>(
      `${base(org)}/webhook-endpoints`,
      body({ name, principalId, confirmed: true }),
    ),
  rotate: (org: string, id: string, expectedRevision: number) =>
    session.request<SigningReveal>(
      `${base(org)}/webhook-endpoints/${encodeURIComponent(id)}/rotate`,
      body({ expectedRevision, confirmed: true }),
    ),
  revokeEndpoint: (org: string, id: string, expectedRevision: number) =>
    session.request<WebhookEndpoint>(
      `${base(org)}/webhook-endpoints/${encodeURIComponent(id)}/revoke`,
      body({ expectedRevision }),
    ),
  deliveries: (org: string, id: string, page: number) =>
    session.request<Page<WebhookDelivery>>(
      `${base(org)}/webhook-endpoints/${encodeURIComponent(id)}/deliveries?page=${page}&size=10`,
    ),
};
