import { afterEach, it, expect, vi } from "vitest";
import { session } from "../../lib/session";
import { workflowApi } from "./api";
import { emptyGraph } from "./model";
afterEach(() => vi.restoreAllMocks());
it("sends metadata, graph and expected revision through the authenticated session abstraction", async () => {
  const request = vi.spyOn(session, "request").mockResolvedValue({});
  const graph = emptyGraph();
  await workflowApi.save(
    "organization",
    "workflow",
    7,
    "Name",
    "Description",
    graph,
  );
  expect(request).toHaveBeenCalledWith(
    "/api/v1/organizations/organization/workflows/workflow",
    {
      method: "PATCH",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({
        expectedRevision: 7,
        name: "Name",
        description: "Description",
        definition: graph,
      }),
    },
  );
});
it("validates and publishes only the persisted revision, never an unsaved request graph", async () => {
  const request = vi.spyOn(session, "request").mockResolvedValue({});
  await workflowApi.validate("organization", "workflow", 3);
  await workflowApi.publish("organization", "workflow", 3);
  expect(request).toHaveBeenNthCalledWith(
    1,
    "/api/v1/organizations/organization/workflows/workflow/validate",
    expect.objectContaining({ method: "POST", body: '{"expectedRevision":3}' }),
  );
  expect(request).toHaveBeenNthCalledWith(
    2,
    "/api/v1/organizations/organization/workflows/workflow/publish",
    expect.objectContaining({ method: "POST", body: '{"expectedRevision":3}' }),
  );
});
it("archives with quoted If-Match and requests exact immutable version IDs", async () => {
  const request = vi.spyOn(session, "request").mockResolvedValue({});
  await workflowApi.archive("organization", "workflow", 9);
  await workflowApi.version("organization", "workflow", "published-version");
  expect(request).toHaveBeenNthCalledWith(
    1,
    "/api/v1/organizations/organization/workflows/workflow",
    { method: "DELETE", headers: { "If-Match": '"9"' } },
  );
  expect(request).toHaveBeenNthCalledWith(
    2,
    "/api/v1/organizations/organization/workflows/workflow/versions/published-version",
  );
});
