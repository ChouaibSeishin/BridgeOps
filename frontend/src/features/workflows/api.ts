import { session } from "../../lib/session";
import type {
  Page,
  WorkflowSummary,
  WorkflowDetail,
  Snapshot,
  VersionSummary,
  Validation,
} from "./model";

const base = (organization: string) =>
  `/api/v1/organizations/${encodeURIComponent(organization)}/workflows`;
const item = (organization: string, id: string) =>
  `${base(organization)}/${encodeURIComponent(id)}`;
const json = (method: string, body: unknown) => ({
  method,
  headers: { "Content-Type": "application/json" },
  body: JSON.stringify(body),
});
export const workflowApi = {
  list: (organization: string, page: number) =>
    session.request<Page<WorkflowSummary>>(
      `${base(organization)}?page=${page}&size=10`,
    ),
  create: (
    organization: string,
    name: string,
    description: string,
    definition: unknown,
  ) =>
    session.request<WorkflowDetail>(
      base(organization),
      json("POST", { name, description, definition }),
    ),
  get: (organization: string, id: string) =>
    session.request<WorkflowDetail>(item(organization, id)),
  save: (
    organization: string,
    id: string,
    expectedRevision: number,
    name: string,
    description: string,
    definition: unknown,
  ) =>
    session.request<WorkflowDetail>(
      item(organization, id),
      json("PATCH", { expectedRevision, name, description, definition }),
    ),
  validate: (organization: string, id: string, expectedRevision: number) =>
    session.request<Validation>(
      `${item(organization, id)}/validate`,
      json("POST", { expectedRevision }),
    ),
  publish: (organization: string, id: string, expectedRevision: number) =>
    session.request<Snapshot>(
      `${item(organization, id)}/publish`,
      json("POST", { expectedRevision }),
    ),
  duplicate: (organization: string, id: string, expectedRevision: number) =>
    session.request<WorkflowDetail>(
      `${item(organization, id)}/duplicate`,
      json("POST", { expectedRevision }),
    ),
  archive: (organization: string, id: string, expectedRevision: number) =>
    session.request<void>(item(organization, id), {
      method: "DELETE",
      headers: { "If-Match": `"${expectedRevision}"` },
    }),
  versions: (organization: string, id: string, page: number) =>
    session.request<Page<VersionSummary>>(
      `${item(organization, id)}/versions?page=${page}&size=10`,
    ),
  version: (organization: string, id: string, version: string) =>
    session.request<Snapshot>(
      `${item(organization, id)}/versions/${encodeURIComponent(version)}`,
    ),
};
