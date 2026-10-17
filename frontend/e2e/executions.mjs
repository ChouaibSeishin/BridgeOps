import { expect } from "@playwright/test";
import { resolve } from "node:path";

/** Actual runtime/API/browser only; protected failure control exists solely in the disposable gateway. */
export async function executionJourney({
  page,
  guest,
  organizationUrl,
  access,
  guestAccess,
  control,
  report,
}) {
  const org = new URL(organizationUrl).pathname.split("/").at(-1);
  const base = `/api/v1/organizations/${org}`;
  async function http(method, path, body, key, context = page, token = access) {
    return context.evaluate(
      async (input) =>
        navigator.locks.request("bridgeops-session", async () => {
          const headers = {
            Authorization: `Bearer ${input.token}`,
            "Content-Type": "application/json",
          };
          if (input.key) headers["Idempotency-Key"] = input.key;
          if (input.method !== "GET")
            headers["X-CSRF-TOKEN"] = (
              await (
                await fetch("/api/v1/auth/csrf", { cache: "no-store" })
              ).json()
            ).token;
          const response = await fetch(input.path, {
            method: input.method,
            headers,
            cache: "no-store",
            body:
              input.body === undefined ? undefined : JSON.stringify(input.body),
          });
          return {
            status: response.status,
            body: response.status === 204 ? null : await response.json(),
          };
        }),
      { method, path, body, key, token },
    );
  }
  const graph = {
    schemaVersion: 1,
    nodes: [
      { id: "finish", type: "end", typeVersion: 1, config: {} },
      {
        id: "retry-step",
        type: "syntheticAction",
        typeVersion: 1,
        config: { operation: "record", message: "Record completed intent" },
      },
      { id: "start", type: "manualTrigger", typeVersion: 1, config: {} },
      {
        id: "record-step",
        type: "syntheticAction",
        typeVersion: 1,
        config: { operation: "record", message: "Record request" },
      },
    ],
    edges: [
      { source: "start", target: "record-step" },
      { source: "record-step", target: "retry-step" },
      { source: "retry-step", target: "finish" },
    ],
  };
  async function published(name, definition) {
    const created = await http("POST", `${base}/workflows`, {
      name,
      description: "Live internal record demonstration",
      definition,
    });
    expect(created.status).toBe(201);
    const id = created.body.workflow.id;
    const version = await http("POST", `${base}/workflows/${id}/publish`, {
      expectedRevision: 0,
    });
    expect(version.status).toBe(201);
    return { id, version: version.body.version.id };
  }
  async function state(text) {
    await expect(
      page.getByRole("status").filter({ hasText: new RegExp(`^${text}$`) }),
    ).toBeVisible({ timeout: 20000 });
  }
  async function run(workflow, delay = 0) {
    await page.goto(`/organizations/${org}/workflows/${workflow.id}`);
    await page
      .getByRole("button", { name: "Run published version", exact: true })
      .click();
    await page.getByLabel("Start delay in seconds").fill(String(delay));
    const pending = page.waitForResponse(
      (r) => r.request().method() === "POST" && r.url().endsWith("/executions"),
    );
    await page
      .getByRole("button", { name: "Confirm internal execution", exact: true })
      .click();
    expect((await pending).status()).toBe(202);
    await expect(
      page.getByRole("heading", { name: "Execution detail", exact: true }),
    ).toBeVisible();
    return new URL(page.url()).pathname.split("/").at(-1);
  }
  report("execution start, edge order and persistent history");
  const workflow = await published("Internal record processing", graph);
  const success = await run(workflow);
  await state("SUCCEEDED");
  await expect(
    page.getByText('"recorded": true', { exact: false }),
  ).toHaveCount(2);
  const steps = await http("GET", `${base}/executions/${success}/steps`);
  expect(steps.body.map((s) => s.nodeId)).toEqual([
    "start",
    "record-step",
    "retry-step",
    "finish",
  ]);
  expect(steps.body.every((s) => s.status === "SUCCEEDED")).toBe(true);
  const summary = await http("GET", `${base}/executions/${success}`);
  expect(summary.body.versionId).toBe(workflow.version);
  await page.reload();
  await state("SUCCEEDED");
  await page.getByRole("link", { name: "All executions", exact: true }).click();
  await expect(
    page.getByRole("link", {
      name: "Inspect execution · SUCCEEDED",
      exact: true,
    }),
  ).toBeVisible();
  await page
    .getByRole("link", { name: "Inspect execution · SUCCEEDED", exact: true })
    .click();
  await state("SUCCEEDED");
  await expect(
    page.getByText('"recorded": true', { exact: false }),
  ).toHaveCount(2);
  await expect(page.getByText("Loading attempts…", { exact: true })).toHaveCount(0);
  await expect(page.getByText("Loading timeline…", { exact: true })).toHaveCount(0);
  await page.evaluate(() => window.scrollTo(0, 0));
  await page.screenshot({
    path: resolve("backend/target/browser-checks/execution-desktop.png"),
    fullPage: true,
  });
  await page.getByRole("button", { name: "Switch to dark theme" }).click();
  await page.screenshot({
    path: resolve("backend/target/browser-checks/execution-dark.png"),
    fullPage: true,
  });
  await page.getByRole("button", { name: "Switch to light theme" }).click();
  await page.setViewportSize({ width: 390, height: 844 });
  await page.screenshot({
    path: resolve("backend/target/browser-checks/execution-mobile.png"),
    fullPage: true,
  });
  expect(
    await page.evaluate(() => document.documentElement.scrollWidth <= 390),
  ).toBe(true);
  await page.setViewportSize({ width: 1440, height: 1000 });
  report("execution scheduled wait and durable cancellation");
  const cancelled = await run(workflow, 60);
  await state("WAITING FOR DELAY");
  await page
    .getByRole("button", { name: "Cancel execution", exact: true })
    .click();
  await page
    .getByLabel("Reason", { exact: true })
    .fill("Stop scheduled internal work");
  await page
    .getByRole("button", { name: "Confirm cancellation", exact: true })
    .click();
  await state("CANCELLED");
  expect(
    (await http("GET", `${base}/executions/${cancelled}/steps`)).body.every(
      (s) => s.status === "CANCELLED",
    ),
  ).toBe(true);
  report(
    "execution exhausted transient failure and linked receipt-preserving retry",
  );
  const injected = await page.request.post("/__test/execution-failure", {
    headers: { "X-Test-Control": control },
  });
  expect(injected.status()).toBe(204);
  const failed = await run(workflow);
  await state("FAILED");
  await expect(page.getByText(/TRANSIENT EXHAUSTED/)).toBeVisible();
  await page
    .getByRole("button", { name: "Retry eligible failure", exact: true })
    .click();
  await page
    .getByLabel("Reason", { exact: true })
    .fill("Retry recovered synthetic operation");
  await page
    .getByRole("button", { name: "Confirm linked retry", exact: true })
    .click();
  await state("SUCCEEDED");
  await expect(page.getByText(/RECEIPT REUSED/)).toBeVisible();
  const retried = new URL(page.url()).pathname.split("/").at(-1);
  expect(
    (await http("GET", `${base}/executions/${retried}`)).body
      .retryOfExecutionId,
  ).toBe(failed);
  expect((await http("GET", `${base}/executions/${failed}`)).body.status).toBe(
    "FAILED",
  );
  report("unsupported approval definition and cross-tenant execution denial");
  const unsupported = structuredClone(graph);
  unsupported.nodes.find((n) => n.id === "retry-step").type = "approval";
  unsupported.nodes.find((n) => n.id === "retry-step").config = {
    prompt: "Await future approval service",
  };
  const approval = await published("Approval definition only", unsupported);
  await page.goto(`/organizations/${org}/workflows/${approval.id}`);
  await page
    .getByRole("button", { name: "Run published version", exact: true })
    .click();
  await page
    .getByRole("button", { name: "Confirm internal execution", exact: true })
    .click();
  await expect(page.getByRole("alert")).toContainText(
    "No execution was created",
  );
  const foreign = `/api/v1/organizations/${process.env.BRIDGEOPS_TEST_FOREIGN_ORG}/executions/${success}`;
  expect((await http("GET", foreign)).status).toBe(404);
  expect(
    (
      await http("POST", foreign + "/cancel", {
        expectedRevision: 0,
        reason: "Foreign attempt",
      })
    ).status,
  ).toBe(404);
  report("execution read-only browser and API permission checks");
  const members = (await http("GET", `${base}/members`)).body.items;
  const member = members.find((m) => m.account.displayName === "Jordan Lee");
  const roles = (await http("GET", `${base}/roles`)).body;
  const roleList = Array.isArray(roles) ? roles : roles.items;
  const viewer = roleList.find((r) => r.name === "VIEWER");
  const operator = roleList.find((r) => r.name === "OPERATOR");
  const changed = await http("PATCH", `${base}/members/${member.id}`, {
    expectedRevision: member.revision,
    status: "ACTIVE",
    roleIds: [viewer.id],
  });
  expect(changed.status).toBe(200);
  await guest.goto(`/organizations/${org}/executions/${success}`);
  await expect(
    guest.getByRole("heading", { name: "Execution detail", exact: true }),
  ).toBeVisible();
  await expect(
    guest.getByRole("button", { name: "Cancel execution", exact: true }),
  ).toHaveCount(0);
  await guest.goto(`/organizations/${org}/workflows/${workflow.id}`);
  await expect(
    guest.getByRole("heading", {
      name: "Internal record processing",
      exact: true,
    }),
  ).toBeVisible();
  await expect(
    guest.getByRole("button", { name: "Run published version", exact: true }),
  ).toHaveCount(0);
  expect(
    (
      await http(
        "POST",
        `${base}/workflows/${workflow.id}/executions`,
        { versionId: workflow.version, input: {} },
        "viewer-execution-key",
        guest,
        guestAccess,
      )
    ).status,
  ).toBe(403);
  const current = (await http("GET", `${base}/members`)).body.items.find(
    (m) => m.id === member.id,
  );
  expect(
    (
      await http("PATCH", `${base}/members/${member.id}`, {
        expectedRevision: current.revision,
        status: "ACTIVE",
        roleIds: [operator.id],
      })
    ).status,
  ).toBe(200);
  await page.goto(organizationUrl);
  await expect(
    page.getByRole("link", { name: "Open executions", exact: true }),
  ).toHaveAttribute("href", `/organizations/${org}/executions`);
  await guest.goto(organizationUrl);
}
