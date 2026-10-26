import { chromium, expect } from "@playwright/test";
import { resolve } from "node:path";
import { authorRest } from "./rest-authoring.mjs";
const {
  BRIDGEOPS_TEST_ORIGIN: origin,
  BRIDGEOPS_TEST_ORG: org,
  BRIDGEOPS_TEST_WORKFLOW: workflow,
  BRIDGEOPS_TEST_PASSWORD: password,
} = process.env;
if (!origin || !org || !workflow || !password)
  throw new Error("Disposable REST browser inputs missing");
const browser = await chromium.launch({ headless: true }),
  ownerContext = await browser.newContext({
    baseURL: origin,
    viewport: { width: 1440, height: 1000 },
  }),
  reviewerContext = await browser.newContext({
    baseURL: origin,
    viewport: { width: 1440, height: 1000 },
  });
const owner = await ownerContext.newPage(),
  reviewer = await reviewerContext.newPage();
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
async function http(page, token, path) {
  return page.evaluate(
    async (input) => {
      const response = await fetch(input.path, {
        headers: { Authorization: `Bearer ${input.token}` },
        cache: "no-store",
      });
      return { status: response.status, body: await response.json() };
    },
    { token, path },
  );
}
async function state(page, value) {
  await expect(
    page.getByRole("status").filter({ hasText: new RegExp(`^${value}$`) }),
  ).toBeVisible({ timeout: 20000 });
}
try {
  report("independent requester and approver");
  const ownerToken = await login(owner, "owner@example.invalid");
  await login(reviewer, "approver@example.invalid");
  await owner.goto(`/organizations/${org}/workflows/${workflow}`);
  await authorRest(owner, org, workflow, report);
  report("explicit external confirmation and intent-change acknowledgement");
  await owner
    .getByRole("button", { name: "Run published version", exact: true })
    .click();
  await expect(
    owner.getByRole("heading", {
      name: "Start external workflow execution?",
      exact: true,
    }),
  ).toBeVisible();
  const confirm = owner.getByRole("button", {
      name: "Confirm external execution",
      exact: true,
    }),
    ack = owner.getByLabel(
      "I acknowledge that these configured operations may contact an external provider and create effects.",
      { exact: true },
    );
  await owner
    .getByRole("textbox", { name: "message (STRING)", exact: true })
    .fill("Approved browser payload");
  await expect(confirm).toBeDisabled();
  await ack.check();
  await expect(confirm).toBeEnabled();
  await owner.getByLabel("Start delay in seconds", { exact: true }).fill("1");
  await expect(ack).not.toBeChecked();
  await expect(confirm).toBeDisabled();
  await owner.getByLabel("Start delay in seconds", { exact: true }).fill("0");
  await ack.check();
  const submitting = owner.waitForResponse(
    (r) =>
      r.request().method() === "POST" &&
      r.url().endsWith(`/workflows/${workflow}/executions`),
  );
  await confirm.click();
  const submitted = await submitting;
  if (submitted.status() !== 202) {
    const problem = await submitted.json();
    console.log("SUBMISSION_STATUS: " + submitted.status());
    if (typeof problem.code === "string" && /^[A-Z_]{1,64}$/.test(problem.code))
      console.log("SUBMISSION_CODE: " + problem.code);
  }
  expect(submitted.status()).toBe(202);
  await owner.waitForURL((url) =>
    url.pathname.startsWith(`/organizations/${org}/executions/`),
  );
  const execution = new URL(owner.url()).pathname.split("/").at(-1);
  report("no provider dispatch until exact independent approval");
  await state(owner, "WAITING FOR APPROVAL");
  const waiting = await http(
    owner,
    ownerToken,
    `/api/v1/organizations/${org}/executions/${execution}`,
  );
  expect(waiting.status).toBe(200);
  await reviewer.goto(
    `/organizations/${org}/approvals/${waiting.body.approvalId}`,
  );
  await state(reviewer, "PENDING");
  const args = JSON.parse(
    await reviewer
      .getByLabel("Exact proposed arguments", { exact: true })
      .innerText(),
  );
  expect(args).toEqual({
    fields: { message: "Approved browser payload" },
    pathParameters: {},
  });
  expect(await reviewer.content()).not.toContain(
    "disposable-browser-provider-credential",
  );
  await reviewer
    .getByRole("button", { name: "Approve exact operation", exact: true })
    .click();
  await reviewer
    .getByRole("button", { name: "Confirm approval", exact: true })
    .click();
  await state(reviewer, "APPROVED");
  await state(owner, "SUCCEEDED");
  report("projected provider result and durable timeline survive navigation");
  await expect(
    owner
      .getByRole("listitem")
      .filter({ hasText: /^EXTERNAL RESULT COMMITTED ·/ }),
  ).toBeVisible();
  const steps = await http(
    owner,
    ownerToken,
    `/api/v1/organizations/${org}/executions/${execution}/steps`,
  );
  expect(steps.status).toBe(200);
  const operation = steps.body.find((s) => s.nodeId === "http");
  expect(operation.status).toBe("SUCCEEDED");
  expect(operation.logicalOperationId).toMatch(/^[0-9a-f-]{36}$/);
  expect(operation.output).toEqual({ response: { message: "ok" } });
  await owner.reload();
  await state(owner, "SUCCEEDED");
  expect(await owner.content()).not.toContain(
    "disposable-browser-provider-credential",
  );
  await owner.screenshot({
    path: resolve("backend/target/browser-checks/rest-workflow-desktop.png"),
    fullPage: true,
  });
  for (const page of [owner, reviewer])
    expect(
      await page.evaluate(() => localStorage.length + sessionStorage.length),
    ).toBe(0);
  console.log(
    "PASS: real browser external acknowledgement, exact independent approval, one TLS provider request, projected receipt/result, durable timeline, refresh and no persistent credentials; disposable fixtures only.",
  );
} catch (error) {
  await owner.screenshot({
    path: resolve("backend/target/browser-checks/rest-failure.png"),
    fullPage: true,
  });
  console.log(
    "FAIL_DIAGNOSTIC: runButtons=" +
      (await owner
        .getByRole("button", { name: "Run published version", exact: true })
        .count()) +
      " dirtyMarkers=" +
      (await owner.getByText("Unsaved changes", { exact: true }).count()) +
      " dialogs=" +
      (await owner.getByRole("dialog").count()),
  );
  console.error(
    "FAIL: " +
      step +
      " · " +
      (String(error?.name).includes("Timeout")
        ? "TIMEOUT"
        : "ASSERTION_OR_TRANSPORT"),
  );
  const location = String(error?.stack).match(
    /(?:rest|rest-authoring)\.mjs:\d+:\d+/,
  )?.[0];
  if (location) console.error("AT: " + location);
  process.exitCode = 1;
} finally {
  await browser.close();
}
