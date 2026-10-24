import { chromium, expect } from "@playwright/test";
import { createHmac } from "node:crypto";
import { resolve } from "node:path";
const {
  BRIDGEOPS_TEST_ORIGIN: origin,
  BRIDGEOPS_TEST_ORG: org,
  BRIDGEOPS_TEST_FOREIGN_ORG: foreign,
  BRIDGEOPS_TEST_PASSWORD: password,
} = process.env;
if (!origin || !org || !foreign || !password)
  throw new Error("Disposable webhook browser inputs missing");
const browser = await chromium.launch({ headless: true });
const ownerContext = await browser.newContext({
    baseURL: origin,
    viewport: { width: 1440, height: 1000 },
  }),
  reviewerContext = await browser.newContext({
    baseURL: origin,
    viewport: { width: 1440, height: 1000 },
  });
const owner = await ownerContext.newPage(),
  reviewer = await reviewerContext.newPage(),
  base = `/api/v1/organizations/${org}`;
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
async function signed(endpoint, secret, delivery, body, valid = true) {
  const timestamp = String(Math.floor(Date.now() / 1000)),
    key = Buffer.from(secret, "base64url");
  let signature;
  try {
    signature = createHmac("sha256", key)
      .update(`v1\n${timestamp}\n${delivery}\n`)
      .update(body)
      .digest("hex");
  } finally {
    key.fill(0);
  }
  const response = await fetch(`${origin}/api/v1/webhooks/${endpoint}`, {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
      "X-BridgeOps-Timestamp": timestamp,
      "X-BridgeOps-Delivery": delivery,
      "X-BridgeOps-Signature": valid ? signature : "0".repeat(64),
    },
    body,
  });
  return {
    status: response.status,
    body: await response.json(),
    location: response.headers.get("Location"),
  };
}
async function state(page, value) {
  await expect(
    page.getByRole("status").filter({ hasText: new RegExp(`^${value}$`) }),
  ).toBeVisible({ timeout: 20000 });
}
try {
  report("independent configuring human and reviewer");
  const ownerToken = await login(owner, "owner@example.invalid"),
    reviewerToken = await login(reviewer, "approver@example.invalid");
  report("explicit published examples through browser confirmation");
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
  report("pin a scoped principal from the published snapshot in the UI");
  await owner.goto(`/organizations/${org}/automations`);
  await owner
    .getByText("Create a scoped automation principal", { exact: true })
    .click();
  await owner
    .getByLabel("Principal name", { exact: true })
    .fill("Signed triage principal");
  report("select the persisted webhook workflow option");
  await owner
    .getByRole("combobox", { name: "Workflow to pin", exact: true })
    .selectOption(artifacts.webhook.workflowId);
  report("inspect the pinned published snapshot");
  await expect(owner.getByText(/Pin published version/)).toBeVisible();
  await owner.getByLabel(/Grant only this immutable workflow/).check();
  const creating = owner.waitForResponse(
    (r) => r.request().method() === "POST" && r.url().endsWith("/automations"),
  );
  await owner
    .getByRole("button", { name: "Create automation principal", exact: true })
    .click();
  const created = await creating;
  expect(created.status()).toBe(201);
  const principal = await created.json();
  expect(principal.versionId).toBe(artifacts.webhook.versionId);
  report(
    "create endpoint and consume the one-time key without persistent storage",
  );
  await owner
    .getByLabel("Endpoint name", { exact: true })
    .fill("Signed triage endpoint");
  await owner
    .getByRole("combobox", { name: "Active automation principal", exact: true })
    .selectOption(principal.id);
  await owner
    .getByLabel("Create this signed endpoint and reveal its key once.", {
      exact: true,
    })
    .check();
  const endpointPending = owner.waitForResponse(
    (r) =>
      r.request().method() === "POST" && r.url().endsWith("/webhook-endpoints"),
  );
  await owner
    .getByRole("button", { name: "Create webhook endpoint", exact: true })
    .click();
  const endpointResponse = await endpointPending;
  expect(endpointResponse.status()).toBe(201);
  const endpoint = (await endpointResponse.json()).endpoint;
  let key = await owner.getByLabel("Signing key", { exact: true }).inputValue();
  expect(key.length).toBeGreaterThan(40);
  await owner
    .getByRole("button", { name: "Dismiss signing key", exact: true })
    .click();
  await expect(owner.getByLabel("Signing key", { exact: true })).toHaveCount(0);
  expect(
    await owner.evaluate(() => localStorage.length + sessionStorage.length),
  ).toBe(0);
  report("raw HMAC acceptance, replay and conflicting delivery");
  const body = JSON.stringify({ input: { limit: 50 } });
  expect((await signed(endpoint.id, key, "invalid", body, false)).status).toBe(
    401,
  );
  const accepted = await signed(endpoint.id, key, "signed-triage", body);
  expect(accepted.status).toBe(202);
  const execution = accepted.body.executionId;
  expect(accepted.location).toBe(`${base}/executions/${execution}`);
  const replay = await signed(endpoint.id, key, "signed-triage", body);
  expect(replay.status).toBe(200);
  expect(replay.body.executionId).toBe(execution);
  expect(replay.body.acceptedAt).toBe(accepted.body.acceptedAt);
  expect(
    (
      await signed(
        endpoint.id,
        key,
        "signed-triage",
        JSON.stringify({ input: { limit: 20 } }),
      )
    ).status,
  ).toBe(409);
  report("automated exact batch waits for an independent human");
  await owner.goto(`/organizations/${org}/executions/${execution}`);
  await state(owner, "WAITING FOR APPROVAL");
  await expect(
    owner.getByText(/Requester: automation principal/),
  ).toBeVisible();
  const waiting = await http(
    owner,
    ownerToken,
    "GET",
    `${base}/executions/${execution}`,
  );
  expect(waiting.body.actor.type).toBe("AUTOMATION");
  expect(waiting.body.requestedBy).toBeNull();
  expect(waiting.body.origin.endpointRevision).toBe(0);
  expect(waiting.body.definitionHash).toBe(artifacts.webhook.definitionHash);
  const request = await http(
    owner,
    ownerToken,
    "GET",
    `${base}/approvals/${waiting.body.approvalId}`,
  );
  expect(request.body.arguments.assignments).toHaveLength(2);
  expect(request.body.actionable).toBe(false);
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
  await reviewer.goto(`/organizations/${org}/approvals/${request.body.id}`);
  await state(reviewer, "PENDING");
  expect(
    JSON.parse(
      await reviewer.getByLabel("Exact proposed arguments").innerText(),
    ),
  ).toEqual(request.body.arguments);
  await reviewer
    .getByRole("button", { name: "Approve exact operation", exact: true })
    .click();
  await reviewer
    .getByRole("button", { name: "Confirm approval", exact: true })
    .click();
  await state(reviewer, "APPROVED");
  await state(owner, "SUCCEEDED");
  report("committed internal effects and automation history survive refresh");
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
  const notifications = await http(
    owner,
    ownerToken,
    "GET",
    `${base}/notifications?page=0&size=10`,
  );
  expect(
    notifications.body.items.filter(
      (n) =>
        n.provenance === "LIVE_EXECUTION" && n.status === "DELIVERED_INTERNAL",
    ),
  ).toHaveLength(1);
  await owner.reload();
  await state(owner, "SUCCEEDED");
  await owner.goto(`/organizations/${org}/automations`);
  await owner
    .getByRole("button", {
      name: "Delivery history for Signed triage endpoint",
      exact: true,
    })
    .click();
  await expect(
    owner.getByRole("link", { name: "signed-triage", exact: true }),
  ).toHaveAttribute("href", `/organizations/${org}/executions/${execution}`);
  report("rotation invalidates old key and preserves accepted origin");
  await owner
    .getByRole("button", {
      name: "Rotate key for Signed triage endpoint",
      exact: true,
    })
    .click();
  await owner
    .getByRole("button", { name: "Confirm key rotation", exact: true })
    .click();
  const rotated = await owner
    .getByLabel("Signing key", { exact: true })
    .inputValue();
  expect(rotated).not.toBe(key);
  await owner
    .getByRole("button", { name: "Dismiss signing key", exact: true })
    .click();
  expect((await signed(endpoint.id, key, "after-rotation", body)).status).toBe(
    401,
  );
  key = rotated;
  const oldDelivery = await signed(endpoint.id, key, "signed-triage", body);
  expect(oldDelivery.status).toBe(200);
  expect(oldDelivery.body.executionId).toBe(execution);
  const original = await http(
    owner,
    ownerToken,
    "GET",
    `${base}/executions/${execution}`,
  );
  expect(original.body.origin.endpointRevision).toBe(0);
  report("fresh signed delivery follows the no-work branch");
  const second = await signed(endpoint.id, key, "no-work", body);
  expect(second.status).toBe(202);
  await owner.goto(
    `/organizations/${org}/executions/${second.body.executionId}`,
  );
  await state(owner, "SUCCEEDED");
  const later = await http(
    owner,
    ownerToken,
    "GET",
    `${base}/executions/${second.body.executionId}`,
  );
  expect(later.body.origin.endpointRevision).toBe(1);
  expect(later.body.approvalId).toBeNull();
  expect(
    (await http(owner, ownerToken, "GET", `${base}/executions?page=0&size=10`))
      .body.totalElements,
  ).toBe(2);
  report(
    "cross-tenant denial, persisted history and terminal endpoint revocation",
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
        `/api/v1/organizations/${foreign}/webhook-endpoints/${endpoint.id}/deliveries`,
      )
    ).status,
  ).toBe(404);
  await owner.goto(`/organizations/${org}/automations`);
  await owner
    .getByRole("button", {
      name: "Revoke endpoint Signed triage endpoint",
      exact: true,
    })
    .click();
  await owner
    .getByRole("button", { name: "Confirm revocation", exact: true })
    .click();
  await expect(
    owner.getByText("Authority revoked; history retained.", { exact: true }),
  ).toBeVisible();
  expect((await signed(endpoint.id, key, "revoked", body)).status).toBe(401);
  key = "";
  await owner
    .getByRole("button", {
      name: "Delivery history for Signed triage endpoint",
      exact: true,
    })
    .click();
  await expect(
    owner.getByRole("link", { name: "signed-triage", exact: true }),
  ).toBeVisible();
  await expect(
    owner.getByRole("link", { name: "no-work", exact: true }),
  ).toBeVisible();
  await owner.screenshot({
    path: resolve("backend/target/browser-checks/webhook-triage-desktop.png"),
    fullPage: true,
  });
  for (const page of [owner, reviewer])
    expect(
      await page.evaluate(() => localStorage.length + sessionStorage.length),
    ).toBe(0);
  console.log(
    "PASS: browser-configured scoped automation, one-time keys, exact raw HMAC, durable replay/conflict, distinct-human triage, actual internal effects, rotation, no-work branch, tenant isolation, revocation and persisted history; disposable fixtures only.",
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
  if (step === "select the persisted webhook workflow option") {
    const options = await owner
      .getByRole("combobox", { name: "Workflow to pin", exact: true })
      .locator("option")
      .allTextContents()
      .catch(() => []);
    console.error("STATE: workflow option labels=" + JSON.stringify(options));
  }
  const location = String(error?.stack).match(/webhook\.mjs:\d+:\d+/)?.[0];
  if (location) console.error("AT: " + location);
  process.exitCode = 1;
} finally {
  await browser.close();
}
