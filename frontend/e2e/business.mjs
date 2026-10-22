import { chromium, expect } from "@playwright/test";
import { resolve } from "node:path";
const {
  BRIDGEOPS_TEST_ORIGIN: origin,
  BRIDGEOPS_TEST_ORG: org,
  BRIDGEOPS_TEST_FOREIGN_ORG: foreign,
  BRIDGEOPS_TEST_PASSWORD: password,
} = process.env;
if (!origin || !org || !foreign || !password)
  throw new Error("Disposable business browser inputs missing");
const browser = await chromium.launch({ headless: true });
const ownerContext = await browser.newContext({
  baseURL: origin,
  viewport: { width: 1440, height: 1000 },
});
const reviewerContext = await browser.newContext({
  baseURL: origin,
  viewport: { width: 1440, height: 1000 },
});
const owner = await ownerContext.newPage(),
  reviewer = await reviewerContext.newPage();
const base = `/api/v1/organizations/${org}`;
let step = "initialization";
function report(value) {
  step = value;
  console.log("STEP: " + value);
}
async function login(page, email) {
  await page.goto("/sign-in");
  await page.getByLabel("Email", { exact: true }).fill(email);
  await page.getByLabel("Password", { exact: true }).fill(password);
  const pending = page.waitForResponse(
    (r) =>
      r.url().endsWith("/api/v1/auth/login") && r.request().method() === "POST",
  );
  await page.getByRole("button", { name: "Sign in", exact: true }).click();
  const response = await pending;
  expect(response.status()).toBe(200);
  await page.waitForURL((url) => url.pathname === "/");
  return (await response.json()).accessToken;
}
async function http(page, token, method, path, body) {
  return page.evaluate(
    async (input) =>
      navigator.locks.request("bridgeops-session", async () => {
        const headers = {
          Authorization: `Bearer ${input.token}`,
          "Content-Type": "application/json",
        };
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
    { token, method, path, body },
  );
}
async function state(page, value) {
  await expect(
    page.getByRole("status").filter({ hasText: new RegExp(`^${value}$`) }),
  ).toBeVisible({ timeout: 20000 });
}
try {
  report("independent real requester and reviewer");
  const ownerToken = await login(owner, "owner@example.invalid"),
    reviewerToken = await login(reviewer, "approver@example.invalid");
  report("explicit isolated business examples and registered connections");
  expect(
    (
      await http(owner, ownerToken, "POST", `${base}/demo-data`, {
        confirmed: true,
      })
    ).status,
  ).toBe(200);
  const connections = {};
  for (const type of ["SUPPORT", "TEAMS", "NOTIFICATIONS"]) {
    const response = await http(
      owner,
      ownerToken,
      "POST",
      `${base}/integrations`,
      {
        name: `Browser ${type}`,
        description: "Disposable internal workflow fixture",
        type,
        configuration: {},
      },
    );
    expect(response.status).toBe(201);
    connections[type] = response.body.connection.id;
  }
  const node = (id, type, config = {}, inputs = {}) => ({
    id,
    type,
    typeVersion: 1,
    config,
    inputs,
  });
  const graph = {
    schemaVersion: 2,
    nodes: [
      node("start", "manualTrigger", { inputFields: { limit: "INTEGER" } }),
      node(
        "read",
        "capabilityAction",
        {
          integrationId: connections.SUPPORT,
          revision: 0,
          operation: "support.list",
        },
        {
          limit: { from: "start", path: "limit" },
          priorities: { literal: ["HIGH", "URGENT"] },
          statuses: { literal: ["OPEN", "IN_PROGRESS"] },
        },
      ),
      node(
        "resolve",
        "capabilityAction",
        {
          integrationId: connections.TEAMS,
          revision: 0,
          operation: "teams.resolve",
        },
        { tickets: { from: "read", path: "tickets" } },
      ),
      node(
        "hasWork",
        "condition",
        { operator: "GT", compare: 0 },
        { value: { from: "resolve", path: "count" } },
      ),
      node("review", "approval", {
        prompt: "Review the exact ticket revisions and target teams",
      }),
      node(
        "assign",
        "capabilityAction",
        {
          integrationId: connections.SUPPORT,
          revision: 0,
          operation: "support.assignBatch",
        },
        { assignments: { from: "resolve", path: "assignments" } },
      ),
      node(
        "notify",
        "notification",
        { integrationId: connections.NOTIFICATIONS, revision: 0 },
        {
          message: {
            parts: [
              { literal: "Assigned " },
              { from: "assign", path: "applied" },
              { literal: " tickets" },
            ],
          },
        },
      ),
      node(
        "noWork",
        "transform",
        {},
        { result: { literal: "No assignment needed" } },
      ),
      node("finish", "end"),
    ],
    edges: [
      { source: "start", target: "read" },
      { source: "read", target: "resolve" },
      { source: "resolve", target: "hasWork" },
      { source: "hasWork", target: "review", branch: "true" },
      { source: "hasWork", target: "noWork", branch: "false" },
      { source: "review", target: "assign" },
      { source: "assign", target: "notify" },
      { source: "notify", target: "finish" },
      { source: "noWork", target: "finish" },
    ],
  };
  const created = await http(owner, ownerToken, "POST", `${base}/workflows`, {
    name: "Controlled ticket triage",
    description: "Internal business capabilities only",
    definition: graph,
  });
  expect(created.status).toBe(201);
  const workflow = created.body.workflow.id;
  const publication = await http(
    owner,
    ownerToken,
    "POST",
    `${base}/workflows/${workflow}/publish`,
    { expectedRevision: 0 },
  );
  expect(publication.status).toBe(201);
  async function start() {
    await owner.goto(`/organizations/${org}/workflows/${workflow}`);
    await owner
      .getByRole("button", { name: "Run published version", exact: true })
      .click();
    await owner.getByLabel("limit (INTEGER)").fill("50");
    await expect(
      owner.getByText("Registered operations in this snapshot", {
        exact: true,
      }),
    ).toBeVisible();
    const pending = owner.waitForResponse(
      (r) => r.request().method() === "POST" && r.url().endsWith("/executions"),
    );
    await owner
      .getByRole("button", { name: "Confirm internal execution", exact: true })
      .click();
    const response = await pending;
    expect(response.status()).toBe(202);
    return (await response.json()).id;
  }
  report(
    "typed browser submission pins and displays the published business plan",
  );
  const execution = await start();
  await state(owner, "WAITING FOR APPROVAL");
  const waiting = await http(
    owner,
    ownerToken,
    "GET",
    `${base}/executions/${execution}`,
  );
  expect(waiting.body.versionId).toBe(publication.body.version.id);
  expect(waiting.body.definitionHash).toBe(
    publication.body.version.definitionHash,
  );
  const request = await http(
    owner,
    ownerToken,
    "GET",
    `${base}/approvals/${waiting.body.approvalId}`,
  );
  expect(request.body.capability).toBe("support");
  expect(request.body.operation).toBe("assignBatch");
  expect(request.body.arguments.assignments).toHaveLength(2);
  expect(
    (
      await http(
        owner,
        ownerToken,
        "POST",
        `${base}/approvals/${request.body.id}/decision`,
        { expectedRevision: 0, decision: "APPROVE" },
      )
    ).status,
  ).toBe(403);
  const ticketsBefore = await http(
    owner,
    ownerToken,
    "GET",
    `${base}/support/tickets?page=0&size=10`,
  );
  expect(
    ticketsBefore.body.items.filter((t) => t.assignedTeamId !== null),
  ).toHaveLength(0);
  report("different human inspects and approves the exact batch");
  await reviewer.goto(`/organizations/${org}/approvals/${request.body.id}`);
  await state(reviewer, "PENDING");
  const args = JSON.parse(
    await reviewer.getByLabel("Exact proposed arguments").innerText(),
  );
  expect(args).toEqual(request.body.arguments);
  await reviewer
    .getByRole("button", { name: "Approve exact operation", exact: true })
    .click();
  await reviewer
    .getByRole("button", { name: "Confirm approval", exact: true })
    .click();
  await state(reviewer, "APPROVED");
  await state(owner, "SUCCEEDED");
  const tickets = await http(
    owner,
    ownerToken,
    "GET",
    `${base}/support/tickets?page=0&size=10`,
  );
  expect(
    tickets.body.items.filter(
      (t) => t.assignedTeamId !== null && t.revision === 1,
    ),
  ).toHaveLength(2);
  report(
    "real ticket effects and internal delivery survive refresh and navigation",
  );
  await owner.reload();
  await state(owner, "SUCCEEDED");
  await expect(
    owner.locator("pre").filter({ hasText: '"applied": 2' }),
  ).toBeVisible();
  await owner.goto(`/organizations/${org}/executions?workflowId=${workflow}`);
  await expect(
    owner.getByRole("heading", { name: "Execution history", exact: true }),
  ).toBeVisible();
  await expect(
    owner.getByRole("link", {
      name: "Inspect execution · SUCCEEDED",
      exact: true,
    }),
  ).toHaveAttribute("href", `/organizations/${org}/executions/${execution}`);
  await owner.goto(`/organizations/${org}/business`);
  await expect(
    owner.getByText("Assigned 2 tickets", { exact: true }),
  ).toBeVisible();
  await expect(
    owner.getByText(/DELIVERED_INTERNAL.*LIVE_EXECUTION/),
  ).toBeVisible();
  const notifications = await http(
    owner,
    ownerToken,
    "GET",
    `${base}/notifications?page=0&size=10`,
  );
  const delivered = notifications.body.items.find(
    (n) => n.provenance === "LIVE_EXECUTION",
  );
  expect(delivered.status).toBe("DELIVERED_INTERNAL");
  expect(
    (
      await http(
        owner,
        ownerToken,
        "GET",
        `${base}/notifications/${delivered.id}/attempts`,
      )
    ).body,
  ).toHaveLength(1);
  await owner.screenshot({
    path: resolve("backend/target/browser-checks/business-triage-desktop.png"),
    fullPage: true,
  });
  report(
    "fresh execution takes the persisted no-work branch without replaying effects",
  );
  const second = await start();
  await state(owner, "SUCCEEDED");
  const secondSteps = await http(
    owner,
    ownerToken,
    "GET",
    `${base}/executions/${second}/steps`,
  );
  expect(secondSteps.body.find((s) => s.nodeId === "noWork").status).toBe(
    "SUCCEEDED",
  );
  for (const id of ["review", "assign", "notify"])
    expect(secondSteps.body.find((s) => s.nodeId === id).status).toBe(
      "SKIPPED",
    );
  expect(
    (
      await http(
        reviewer,
        reviewerToken,
        "GET",
        `/api/v1/organizations/${foreign}/executions/${execution}`,
      )
    ).status,
  ).toBe(404);
  expect(
    (
      await http(
        owner,
        ownerToken,
        "GET",
        `/api/v1/organizations/${foreign}/executions/${execution}`,
      )
    ).status,
  ).toBe(404);
  for (const page of [owner, reviewer])
    expect(
      await page.evaluate(() => localStorage.length + sessionStorage.length),
    ).toBe(0);
  console.log(
    "PASS: typed published start, exact distinct-human ticket approval, real assignment/receipts/internal delivery, refresh/history, no-work branch, tenant isolation; disposable internal fixtures only.",
  );
} catch (error) {
  console.error(
    "FAIL: " +
      step +
      " · " +
      (String(error?.name).includes("Timeout")
        ? "TIMEOUT"
        : "ASSERTION_OR_TRANSPORT"),
  );
  const location = String(error?.stack).match(/business\.mjs:\d+:\d+/)?.[0];
  if (location) console.error("AT: " + location);
  process.exitCode = 1;
} finally {
  await browser.close();
}
