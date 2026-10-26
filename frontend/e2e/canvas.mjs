import { authorTriage } from "./canvas-authoring.mjs";
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
async function http(page, token, method, path, body, raw) {
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
            input.raw ??
            (input.body === undefined ? undefined : JSON.stringify(input.body)),
        });
        return {
          status: response.status,
          body: response.status === 204 ? null : await response.json(),
        };
      }),
    { token, method, path, body, raw },
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
  report(
    "explicit browser setup of persisted example connections and published workflows",
  );
  await owner.goto(`/organizations/${org}/business`);
  await owner
    .getByRole("button", { name: "Set up triage workflows", exact: true })
    .click();
  const installing = owner.waitForResponse(
    (r) =>
      r.request().method() === "POST" && r.url().endsWith("/demo-workflows"),
  );
  await owner
    .getByRole("button", {
      name: "Confirm setup triage workflows",
      exact: true,
    })
    .click();
  const installed = await installing;
  expect(installed.status()).toBe(201);
  const artifacts = (await installed.json()).artifacts;
  await expect(
    owner.getByRole("link", {
      name: "Open manual triage example",
      exact: true,
    }),
  ).toBeVisible();
  expect(artifacts.provenance).toBe("SEEDED_EXAMPLE");
  const replay = await http(
    owner,
    ownerToken,
    "POST",
    `${base}/demo-workflows`,
    { confirmed: true },
  );
  expect(replay.status).toBe(200);
  expect(replay.body.artifacts).toEqual(artifacts);
  let workflow = artifacts.manual.workflowId,
    expectedVersion = artifacts.manual.versionId,
    expectedHash = artifacts.manual.definitionHash;
  const published = await http(
    owner,
    ownerToken,
    "GET",
    `${base}/workflows/${workflow}/versions/${artifacts.manual.versionId}`,
  );
  expect(published.status).toBe(200);
  expect(published.body.version.definitionHash).toBe(
    artifacts.manual.definitionHash,
  );
  const webhookExample = await http(
    owner,
    ownerToken,
    "GET",
    `${base}/workflows/${artifacts.webhook.workflowId}/versions/${artifacts.webhook.versionId}`,
  );
  expect(webhookExample.status).toBe(200);
  expect(
    webhookExample.body.definition.nodes.some(
      (n) => n.type === "webhookTrigger",
    ),
  ).toBe(true);
  const authored = await authorTriage(owner, org, report);
  workflow = authored.workflow;
  expectedVersion = authored.snapshot.version.id;
  expectedHash = authored.snapshot.version.definitionHash;
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
  expect(waiting.body.versionId).toBe(expectedVersion);
  expect(waiting.body.definitionHash).toBe(expectedHash);
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
    path: resolve("backend/target/browser-checks/canvas-triage-desktop.png"),
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
  expect(
    secondSteps.body.find((s) => s.nodeId === authored.ids.noWork).status,
  ).toBe("SUCCEEDED");
  for (const id of [
    authored.ids.review,
    authored.ids.assign,
    authored.ids.notify,
  ])
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
  report(
    "exact API integers remain readable without browser rounding or an unsafe draft save",
  );
  const currentDraft = await http(
    owner,
    ownerToken,
    "GET",
    `${base}/workflows/${workflow}`,
  );
  expect(currentDraft.status).toBe(200);
  const exactDefinition = currentDraft.body.definitionJson.replace(
    '"compare":0',
    '"compare":9223372036854775807',
  );
  expect(exactDefinition).toContain('"compare":9223372036854775807');
  const rawBody =
    JSON.stringify({
      expectedRevision: currentDraft.body.workflow.revision,
      name: currentDraft.body.workflow.name,
      description: currentDraft.body.workflow.description,
    }).slice(0, -1) +
    ',"definition":' +
    exactDefinition +
    "}";
  expect(
    (
      await http(
        owner,
        ownerToken,
        "PATCH",
        `${base}/workflows/${workflow}`,
        undefined,
        rawBody,
      )
    ).status,
  ).toBe(200);
  await owner.goto(`/organizations/${org}/workflows/${workflow}`);
  await expect(
    owner.getByRole("heading", { name: "Saved draft definition", exact: true }),
  ).toBeVisible();
  await expect(
    owner.getByRole("button", { name: "Save draft", exact: true }),
  ).toBeDisabled();
  await owner.getByText("Technical definition JSON", { exact: true }).click();
  await expect(
    owner.getByLabel("Saved draft definition JSON", { exact: true }),
  ).toContainText("9223372036854775807");
  const originalVersion = await http(
    owner,
    ownerToken,
    "GET",
    `${base}/workflows/${workflow}/versions/${expectedVersion}`,
  );
  expect(originalVersion.status).toBe(200);
  expect(originalVersion.body.version.definitionHash).toBe(expectedHash);
  expect(originalVersion.body.definitionJson).toContain('"compare":0');
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
  const location = String(error?.stack).match(
    /(?:canvas|canvas-authoring)\.mjs:\d+:\d+/,
  )?.[0];
  if (location) console.error("AT: " + location);
  process.exitCode = 1;
} finally {
  await browser.close();
}
