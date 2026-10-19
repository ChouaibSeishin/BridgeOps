import { chromium, expect } from "@playwright/test";
import { resolve } from "node:path";
const origin = process.env.BRIDGEOPS_TEST_ORIGIN,
  org = process.env.BRIDGEOPS_TEST_ORG,
  foreign = process.env.BRIDGEOPS_TEST_FOREIGN_ORG,
  control = process.env.BRIDGEOPS_TEST_CONTROL,
  password = process.env.BRIDGEOPS_TEST_PASSWORD;
if (!origin || !org || !foreign || !control || !password)
  throw new Error("Required disposable approval browser inputs missing");
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
  const token = (await response.json()).accessToken;
  await page.waitForURL((url) => url.pathname === "/");
  return token;
}
async function http(page, token, method, path, body, key) {
  return page.evaluate(
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
    { token, method, path, body, key },
  );
}
async function state(page, value) {
  await expect(
    page.getByRole("status").filter({ hasText: new RegExp(`^${value}$`) }),
  ).toBeVisible({ timeout: 20000 });
}
const graph = {
  schemaVersion: 1,
  nodes: [
    { id: "finish", type: "end", typeVersion: 1, config: {} },
    {
      id: "protected",
      type: "syntheticAction",
      typeVersion: 1,
      config: { operation: "record", message: "Exact protected record" },
    },
    { id: "start", type: "manualTrigger", typeVersion: 1, config: {} },
    {
      id: "gate",
      type: "approval",
      typeVersion: 1,
      config: { prompt: "Review the exact protected internal record" },
    },
    {
      id: "prepare",
      type: "syntheticAction",
      typeVersion: 1,
      config: { operation: "record", message: "Preparation record" },
    },
  ],
  edges: [
    { source: "start", target: "prepare" },
    { source: "prepare", target: "gate" },
    { source: "gate", target: "protected" },
    { source: "protected", target: "finish" },
  ],
};
try {
  report("two independent authenticated human contexts");
  const ownerToken = await login(owner, "owner@example.invalid"),
    reviewerToken = await login(reviewer, "approver@example.invalid");
  async function publish(name, definition) {
    const created = await http(owner, ownerToken, "POST", `${base}/workflows`, {
      name,
      description: "Internal record governance only",
      definition,
    });
    expect(created.status).toBe(201);
    const id = created.body.workflow.id;
    const published = await http(
      owner,
      ownerToken,
      "POST",
      `${base}/workflows/${id}/publish`,
      { expectedRevision: 0 },
    );
    expect(published.status).toBe(201);
    return { id, version: published.body.version.id };
  }
  async function run(workflow) {
    await owner.goto(`/organizations/${org}/workflows/${workflow.id}`);
    await owner
      .getByRole("button", { name: "Run published version", exact: true })
      .click();
    const pending = owner.waitForResponse(
      (r) => r.request().method() === "POST" && r.url().endsWith("/executions"),
    );
    await owner
      .getByRole("button", { name: "Confirm internal execution", exact: true })
      .click();
    const response = await pending;
    expect(response.status()).toBe(202);
    const execution = (await response.json()).id;
    await state(owner, "WAITING FOR APPROVAL");
    const persisted = await http(
      owner,
      ownerToken,
      "GET",
      `${base}/executions/${execution}`,
    );
    expect(persisted.status).toBe(200);
    expect(persisted.body.approvalId).toBeTruthy();
    return {
      id: execution,
      approval: persisted.body.approvalId,
      deadline: persisted.body.deadlineAt,
    };
  }
  async function review(execution) {
    await reviewer.goto(
      `/organizations/${org}/approvals/${execution.approval}`,
    );
    await expect(
      reviewer.getByRole("heading", { name: "Approval detail", exact: true }),
    ).toBeVisible();
    await state(reviewer, "PENDING");
    await expect(reviewer.getByLabel("Exact proposed arguments")).toBeVisible();
  }
  async function decide(execution, outcome) {
    await review(execution);
    await reviewer
      .getByRole("button", {
        name:
          outcome === "APPROVE" ? "Approve internal record" : "Reject request",
        exact: true,
      })
      .click();
    await reviewer
      .getByLabel("Optional decision reason")
      .fill(
        outcome === "APPROVE"
          ? "Reviewed the exact bounded record."
          : "Do not apply this proposed record.",
      );
    const pending = reviewer.waitForResponse(
      (r) => r.request().method() === "POST" && r.url().endsWith("/decision"),
    );
    await reviewer
      .getByRole("button", {
        name: outcome === "APPROVE" ? "Confirm approval" : "Confirm rejection",
        exact: true,
      })
      .click();
    const response = await pending;
    expect(response.status()).toBe(200);
    const body = await response.json();
    expect(body.status).toBe(outcome === "APPROVE" ? "APPROVED" : "REJECTED");
    expect(body.executionStatus).toBe("WAITING_FOR_APPROVAL");
    return body;
  }
  async function inspect(execution, expected) {
    await owner.goto(`/organizations/${org}/executions/${execution.id}`);
    await state(owner, expected);
    const current = await http(
      owner,
      ownerToken,
      "GET",
      `${base}/executions/${execution.id}`,
    );
    expect(current.body.deadlineAt).toBe(execution.deadline);
    return current.body;
  }
  async function policy(required, lifetime) {
    await owner.goto(`/organizations/${org}/approval-policy`);
    await expect(
      owner.getByRole("heading", { name: "Approval policy", exact: true }),
    ).toBeVisible();
    const checkbox = owner.getByRole("checkbox", {
      name: "Require approval for every internal record",
    });
    await checkbox.setChecked(required);
    await owner
      .getByLabel("Approval lifetime in seconds")
      .fill(String(lifetime));
    const pending = owner.waitForResponse(
      (r) =>
        r.request().method() === "PUT" && r.url().endsWith("/approval-policy"),
    );
    await owner
      .getByRole("button", { name: "Save approval policy", exact: true })
      .click();
    expect((await pending).status()).toBe(200);
    await expect(owner.getByText(/Policy committed/)).toBeVisible();
  }
  const workflow = await publish("Governed internal record", graph);
  report("real explicit approval wait and no protected effect");
  const first = await run(workflow);
  const steps = await http(
    owner,
    ownerToken,
    "GET",
    `${base}/executions/${first.id}/steps`,
  );
  expect(steps.body.find((s) => s.nodeId === "prepare").status).toBe(
    "SUCCEEDED",
  );
  expect(steps.body.find((s) => s.nodeId === "protected").status).toBe(
    "PENDING",
  );
  expect(steps.body.find((s) => s.nodeId === "protected").output).toBeNull();
  await owner
    .getByRole("link", { name: "Review outstanding approval" })
    .click();
  await expect(
    owner.getByText(/Owner status does not permit self-approval/),
  ).toBeVisible();
  expect(
    await owner
      .getByRole("button", { name: "Approve internal record", exact: true })
      .count(),
  ).toBe(0);
  expect(
    (
      await http(
        owner,
        ownerToken,
        "POST",
        `${base}/approvals/${first.approval}/decision`,
        { expectedRevision: 0, decision: "APPROVE" },
      )
    ).status,
  ).toBe(403);
  report(
    "inbox discovery, immutable exact intent and accessible responsive review",
  );
  await reviewer.goto(`/organizations/${org}`);
  await reviewer
    .getByRole("link", { name: "Open approvals", exact: true })
    .click();
  await expect(
    reviewer.getByRole("heading", { name: "Approval inbox", exact: true }),
  ).toBeVisible();
  await expect(
    reviewer.getByText("Exact protected record", { exact: true }),
  ).toBeVisible();
  await reviewer.screenshot({
    path: resolve("backend/target/browser-checks/approval-inbox-desktop.png"),
    fullPage: true,
  });
  await reviewer
    .getByRole("link", {
      name: "Review the exact protected internal record",
      exact: true,
    })
    .click();
  await expect(
    reviewer.getByRole("link", {
      name: "Governed internal record · published version 1",
      exact: true,
    }),
  ).toBeVisible();
  await expect(reviewer.getByLabel("Exact proposed arguments")).toContainText(
    "Exact protected record",
  );
  await expect(
    reviewer.getByText("APPROVAL REQUESTED", { exact: true }),
  ).toBeVisible();
  await reviewer.screenshot({
    path: resolve("backend/target/browser-checks/approval-detail-desktop.png"),
    fullPage: true,
  });
  await reviewer.getByRole("button", { name: "Switch to dark theme" }).click();
  await reviewer.screenshot({
    path: resolve("backend/target/browser-checks/approval-detail-dark.png"),
    fullPage: true,
  });
  await reviewer.getByRole("button", { name: "Switch to light theme" }).click();
  await reviewer.setViewportSize({ width: 390, height: 844 });
  await reviewer.screenshot({
    path: resolve("backend/target/browser-checks/approval-detail-mobile.png"),
    fullPage: true,
  });
  expect(
    await reviewer.evaluate(() => document.documentElement.scrollWidth <= 390),
  ).toBe(true);
  await reviewer.setViewportSize({ width: 1440, height: 1000 });
  await reviewer
    .getByRole("button", { name: "Approve internal record", exact: true })
    .focus();
  await expect(
    reviewer.getByRole("button", {
      name: "Approve internal record",
      exact: true,
    }),
  ).toBeFocused();
  const stale = await reviewerContext.newPage();
  await stale.goto(`/organizations/${org}/approvals/${first.approval}`);
  await state(stale, "PENDING");
  report(
    "distinct approval, repeated stale decision rejection and persisted resumption",
  );
  await decide(first, "APPROVE");
  expect(
    (
      await http(
        stale,
        reviewerToken,
        "POST",
        `${base}/approvals/${first.approval}/decision`,
        { expectedRevision: 0, decision: "REJECT" },
      )
    ).status,
  ).toBe(409);
  await stale.reload();
  await state(stale, "APPROVED");
  expect(
    await stale
      .getByRole("button", { name: "Approve internal record", exact: true })
      .count(),
  ).toBe(0);
  await inspect(first, "SUCCEEDED");
  await expect(
    owner.getByText('"recorded": true', { exact: false }),
  ).toHaveCount(2);
  await owner.reload();
  await state(owner, "SUCCEEDED");
  await expect(
    owner.getByText('"recorded": true', { exact: false }),
  ).toHaveCount(2);
  await owner.screenshot({
    path: resolve(
      "backend/target/browser-checks/approval-execution-desktop.png",
    ),
    fullPage: true,
  });
  await stale.close();
  report("approval permissions and cross-tenant concealment");
  await reviewer.goto(`/organizations/${org}/approval-policy`);
  await expect(reviewer.getByText(/Policy changes require/)).toBeVisible();
  expect(
    await reviewer
      .getByRole("button", { name: "Save approval policy", exact: true })
      .count(),
  ).toBe(0);
  expect(
    (
      await http(reviewer, reviewerToken, "PUT", `${base}/approval-policy`, {
        expectedRevision: 0,
        requireRecordApproval: true,
        lifetimeSeconds: 60,
      })
    ).status,
  ).toBe(403);
  expect(
    (
      await http(
        reviewer,
        reviewerToken,
        "GET",
        `/api/v1/organizations/${foreign}/approvals/${first.approval}`,
      )
    ).status,
  ).toBe(404);
  await reviewer.goto(`/organizations/${foreign}/approvals/${first.approval}`);
  await expect(reviewer.getByRole("alert")).toBeVisible();
  expect(
    await reviewer.getByText("Exact protected record", { exact: true }).count(),
  ).toBe(0);
  report("rejection persists failure and honest partial outcomes");
  const rejected = await run(workflow);
  await decide(rejected, "REJECT");
  const failed = await inspect(rejected, "FAILED");
  expect(failed.errorCode).toBe("APPROVAL_REJECTED");
  expect(failed.retryEligible).toBe(false);
  await expect(
    owner.getByText('"recorded": true', { exact: false }),
  ).toHaveCount(1);
  await reviewer.goto(`/organizations/${org}/approvals/${rejected.approval}`);
  await state(reviewer, "REJECTED");
  await expect(
    reviewer.getByText("Do not apply this proposed record."),
  ).toBeVisible();
  report("bounded policy expiry uses controlled time without real waiting");
  await policy(false, 60);
  const expired = await run(workflow);
  const advanced = await ownerContext.request.post("/__test/approval-clock", {
    headers: { "X-Test-Control": control },
    data: { seconds: 61 },
  });
  expect(advanced.status()).toBe(204);
  await inspect(expired, "FAILED");
  await reviewer.goto(`/organizations/${org}/approvals/${expired.approval}`);
  await state(reviewer, "EXPIRED");
  expect(
    await reviewer
      .getByRole("button", { name: "Approve internal record", exact: true })
      .count(),
  ).toBe(0);
  report("policy version change invalidates pending intent");
  const invalidated = await run(workflow);
  await policy(true, 600);
  const invalid = await inspect(invalidated, "FAILED");
  expect(invalid.errorCode).toBe("POLICY_CHANGED");
  await reviewer.goto(
    `/organizations/${org}/approvals/${invalidated.approval}`,
  );
  await state(reviewer, "INVALIDATED");
  report("omitting a graph gate cannot bypass central record policy");
  const plain = {
    schemaVersion: 1,
    nodes: [
      { id: "end", type: "end", typeVersion: 1, config: {} },
      {
        id: "action",
        type: "syntheticAction",
        typeVersion: 1,
        config: {
          operation: "record",
          message: "Policy-protected plain record",
        },
      },
      { id: "start", type: "manualTrigger", typeVersion: 1, config: {} },
    ],
    edges: [
      { source: "start", target: "action" },
      { source: "action", target: "end" },
    ],
  };
  const plainWorkflow = await publish("Policy protected without node", plain);
  const central = await run(plainWorkflow);
  const waitingSteps = await http(
    owner,
    ownerToken,
    "GET",
    `${base}/executions/${central.id}/steps`,
  );
  expect(
    waitingSteps.body.find((s) => s.nodeId === "action").output,
  ).toBeNull();
  await decide(central, "APPROVE");
  await inspect(central, "SUCCEEDED");
  report("cancellation prevents a late decision and terminal resumption");
  await policy(false, 3600);
  const cancelled = await run(workflow);
  await owner
    .getByRole("button", { name: "Cancel execution", exact: true })
    .click();
  await owner.getByLabel("Reason").fill("Cancel pending human intent");
  await owner
    .getByRole("button", { name: "Confirm cancellation", exact: true })
    .click();
  await state(owner, "CANCELLED");
  expect(
    (
      await http(
        reviewer,
        reviewerToken,
        "POST",
        `${base}/approvals/${cancelled.approval}/decision`,
        { expectedRevision: 0, decision: "APPROVE" },
      )
    ).status,
  ).toBe(409);
  await reviewer.goto(`/organizations/${org}/approvals/${cancelled.approval}`);
  await state(reviewer, "CANCELLED");
  await expect
    .poll(
      async () => {
        const r = await http(
          owner,
          ownerToken,
          "GET",
          `${base}/approvals/${cancelled.approval}`,
        );
        return r.body.events.filter(
          (e) => e.code === "APPROVAL_SIGNAL_CONSUMED",
        ).length;
      },
      { timeout: 10000 },
    )
    .toBe(1);
  for (const page of [owner, reviewer])
    expect(
      await page.evaluate(() => localStorage.length + sessionStorage.length),
    ).toBe(0);
  console.log(
    "PASS: real distinct approval, exact intent, durable resumption, stale/rejected/expired/invalidated/cancelled history, central policy, tenant denial, responsive captures; synthetic accounts only.",
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
  const location = String(error?.stack).match(/approvals\.mjs:\d+:\d+/)?.[0];
  if (location) console.error("AT: " + location);
  process.exitCode = 1;
} finally {
  await browser.close();
}
