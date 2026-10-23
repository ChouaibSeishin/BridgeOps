import { session } from "../../lib/session";
export interface ExampleConnection {
  id: string;
  revision: number;
}
export interface ExampleVersion {
  workflowId: string;
  versionId: string;
  definitionHash: string;
}
export interface DemoArtifacts {
  support: ExampleConnection;
  teams: ExampleConnection;
  notifications: ExampleConnection;
  manual: ExampleVersion;
  webhook: ExampleVersion;
  createdAt: string;
  provenance: string;
}
export interface DemoInstallation {
  created: boolean;
  artifacts: DemoArtifacts | null;
}
const path = (org: string) =>
  `/api/v1/organizations/${encodeURIComponent(org)}/demo-workflows`;
export const demoWorkflowApi = {
  get: (org: string) => session.request<DemoInstallation>(path(org)),
  install: (org: string) =>
    session.request<DemoInstallation>(path(org), {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ confirmed: true }),
    }),
};
