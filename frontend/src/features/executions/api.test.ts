import { afterEach, it, expect, vi } from "vitest";
import { executionApi } from "./api";
import { session } from "../../lib/session";
afterEach(() => vi.restoreAllMocks());
it("sends explicit v2 trigger fields and acknowledged effects with the pinned version", async () => {
  const request = vi.spyOn(session, "request").mockResolvedValue({});
  await executionApi.start("org", "workflow", "v2", 0, "typed-key", {
    count: 2,
  });
  expect(request).toHaveBeenCalledWith(
    "/api/v1/organizations/org/workflows/workflow/executions",
    expect.objectContaining({
      body: JSON.stringify({
        versionId: "v2",
        input: { count: 2 },
        delaySeconds: 0,
        effectsAcknowledged: true,
      }),
    }),
  );
});
it("pins the exact version and empty input with a stable request header", async () => {
  const request = vi.spyOn(session, "request").mockResolvedValue({});
  await executionApi.start("org", "workflow", "version", 60, "stable-key");
  expect(request).toHaveBeenCalledWith(
    "/api/v1/organizations/org/workflows/workflow/executions",
    {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        "Idempotency-Key": "stable-key",
      },
      body: JSON.stringify({
        versionId: "version",
        input: {},
        delaySeconds: 60,
        effectsAcknowledged: true,
      }),
    },
  );
});
it("sends current revisions and explicit retry linkage requests without graph mutation", async () => {
  const request = vi.spyOn(session, "request").mockResolvedValue({});
  await executionApi.cancel("org", "execution", 8, "Stop dispatch");
  expect(request).toHaveBeenLastCalledWith(
    "/api/v1/organizations/org/executions/execution/cancel",
    expect.objectContaining({
      body: JSON.stringify({ expectedRevision: 8, reason: "Stop dispatch" }),
    }),
  );
  await executionApi.retry(
    "org",
    "execution",
    9,
    "Retry eligible failure",
    "retry-key",
  );
  expect(request).toHaveBeenLastCalledWith(
    "/api/v1/organizations/org/executions/execution/retry",
    expect.objectContaining({
      headers: {
        "Content-Type": "application/json",
        "Idempotency-Key": "retry-key",
      },
      body: JSON.stringify({
        expectedRevision: 9,
        reason: "Retry eligible failure",
        effectsAcknowledged: true,
      }),
    }),
  );
});
it("bounds collection requests and encodes resource and filter values", async () => {
  const request = vi.spyOn(session, "request").mockResolvedValue({});
  await executionApi.list("org/path", 2, "workflow&other", "FAILED");
  expect(request).toHaveBeenLastCalledWith(
    "/api/v1/organizations/org%2Fpath/executions?page=2&size=10&workflowId=workflow%26other&status=FAILED",
  );
  await executionApi.events("org", "execution", 1);
  expect(request).toHaveBeenLastCalledWith(
    "/api/v1/organizations/org/executions/execution/events?page=1&size=100",
  );
});
