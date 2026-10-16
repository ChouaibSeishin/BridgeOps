import { chromium, expect } from "@playwright/test";
import { mkdir } from "node:fs/promises";
import { resolve } from "node:path";
const origin = process.env.BRIDGEOPS_TEST_ORIGIN;
const control = process.env.BRIDGEOPS_TEST_CONTROL;
const ownerPassword = "browser-disposable-password";
const invitedPassword = "browser-invitation-password";
const resetPassword = "browser-reset-password";
const changedPassword = "browser-changed-password";
let step = "launch",
  browser,
  page;
const observed = new WeakSet();
function observe(target) {
  if (observed.has(target)) return;
  observed.add(target);
  target.on("response", (response) =>
    console.log(
      "HTTP: " + response.request().method() + " " +
      new URL(response.url()).pathname + " " + response.status(),
    ),
  );
}
async function mail(recipient, subject) {
  for (let i = 0; i < 30; i++) {
    const response = await fetch(origin + "/__test/mail", {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        "X-Test-Control": control,
      },
      body: JSON.stringify({ recipient, subject }),
    });
    const message = await response.json();
    if (message.token) return message.token;
    await new Promise((resolve) => setTimeout(resolve, 300));
  }
  throw new Error("Test mail capture timed out");
}
async function signIn(target, email, password) {
  observe(target);
  await target.goto("/sign-in");
  await expect(
    target.getByRole("heading", { name: "Sign in to your workspace" }),
  ).toBeVisible();
  await target.getByLabel("Email", { exact: true }).fill(email);
  await target.getByLabel("Password", { exact: true }).fill(password);
  const pending = target.waitForResponse((r) =>
    r.url().endsWith("/api/v1/auth/login"),
  );
  await target.getByLabel("Password", { exact: true }).press("Enter");
  const response = await pending;
  expect(response.status()).toBe(200);
  expect(response.headers()["cache-control"]).toContain("no-store");
  const body = await response.json();
  await expect(
    target.getByRole("heading", { name: "Organizations", exact: true }),
  ).toBeVisible();
  return body.accessToken;
}
try {
  expect(process.version).toBe("v24.22.0");
  browser = await chromium.launch({ headless: true });
  const context = await browser.newContext({
    baseURL: origin,
    viewport: { width: 1440, height: 1000 },
  });
  page = await context.newPage();
  const urls = [];
  observe(page);
  page.on("request", (request) => urls.push(request.url()));
  step = "invalid credentials";
  console.log("STEP: " + step);
  await page.goto("/sign-in");
  await page.getByLabel("Email", { exact: true }).fill("owner@example.invalid");
  await page.getByLabel("Password", { exact: true }).fill("incorrect-password");
  await page.getByRole("button", { name: "Sign in", exact: true }).click();
  await expect(page.getByRole("alert")).toBeVisible();
  await expect(page.getByLabel("Password", { exact: true })).toHaveValue("");
  step = "provisioned account sign-in";
  console.log("STEP: " + step);
  const ownerAccess = await signIn(
    page,
    "owner@example.invalid",
    ownerPassword,
  );
  await expect(page.getByText("Your workspace starts here")).toBeVisible();
  step = "organization creation";
  console.log("STEP: " + step);
  await page.getByLabel("Organization name").fill("Dispatch Operations");
  await page.getByLabel("Organization slug").fill("browser-dispatch");
  await page.getByRole("button", { name: "Create organization" }).click();
  await expect(
    page.getByRole("heading", { name: "Dispatch Operations", exact: true }),
  ).toBeVisible();
  const organizationUrl = page.url();
  await expect(
    page.getByText("Alex Morgan", { exact: true }).last(),
  ).toBeVisible();
  await expect(
    page.getByRole("heading", { name: "Members", exact: true }),
  ).toBeVisible();
  step = "concurrent tab session restoration";
  console.log("STEP: " + step);
  const tab = await context.newPage();
  await Promise.all([page.reload(), tab.goto(organizationUrl)]);
  await expect(
    page.getByRole("heading", { name: "Dispatch Operations", exact: true }),
  ).toBeVisible();
  await expect(
    tab.getByRole("heading", { name: "Dispatch Operations", exact: true }),
  ).toBeVisible();
  await tab.close();
  step = "cookie and memory storage boundary";
  console.log("STEP: " + step);
  expect(
    await page.evaluate(() => localStorage.length + sessionStorage.length),
  ).toBe(0);
  expect(
    await page.evaluate(() =>
      document.cookie.includes("bridgeops-dev-refresh="),
    ),
  ).toBe(false);
  const refreshCookie = (await context.cookies()).find(
    (cookie) => cookie.name === "bridgeops-dev-refresh",
  );
  expect(refreshCookie?.httpOnly).toBe(true);
  expect(refreshCookie?.sameSite).toBe("Lax");
  step = "invitation issue and revoke";
  console.log("STEP: " + step);
  await page.getByLabel("Recipient email").fill("revoked@example.invalid");
  await page.getByRole("checkbox", { name: "VIEWER", exact: true }).check();
  let issued = page.waitForResponse(
    (r) => r.url().endsWith("/invitations") && r.request().method() === "POST",
  );
  await page.getByRole("button", { name: "Send invitation" }).click();
  expect((await issued).status()).toBe(201);
  const revoked = page
    .getByRole("row")
    .filter({ hasText: "revoked@example.invalid" });
  await expect(revoked).toBeVisible();
  await revoked.getByRole("button", { name: "Revoke invitation" }).click();
  await expect(
    revoked.getByRole("cell", { name: "REVOKED", exact: true }),
  ).toBeVisible();
  step = "new recipient invitation";
  console.log("STEP: " + step);
  await page.getByLabel("Recipient email").fill("jordan@example.invalid");
  issued = page.waitForResponse(
    (r) => r.url().endsWith("/invitations") && r.request().method() === "POST",
  );
  await page.getByRole("button", { name: "Send invitation" }).click();
  expect((await issued).status()).toBe(201);
  const invitation = await mail(
    "jordan@example.invalid",
    "BridgeOps invitation",
  );
  step = "invitation-only enrollment without auto-login";
  console.log("STEP: " + step);
  const guestContext = await browser.newContext({ baseURL: origin });
  const guest = await guestContext.newPage();
  await guest.goto("/invitations/enroll");
  await guest.getByLabel("Invitation token").fill(invitation);
  await guest
    .getByLabel("Email", { exact: true })
    .fill("jordan@example.invalid");
  await guest.getByLabel("Display name").fill("Jordan Lee");
  await guest.getByLabel("Password", { exact: true }).fill(invitedPassword);
  await guest.getByRole("button", { name: "Create invited account" }).click();
  await expect(
    guest.getByRole("link", { name: "Continue to sign in" }),
  ).toBeVisible();
  expect((await guestContext.request.get("/api/v1/auth/me")).status()).toBe(
    401,
  );
  step = "invited account membership and denied creation";
  console.log("STEP: " + step);
  await signIn(guest, "jordan@example.invalid", invitedPassword);
  await expect(
    guest.getByRole("button", { name: "Create organization" }),
  ).toHaveCount(0);
  await guest.getByRole("link", { name: /Dispatch Operations/ }).click();
  await expect(
    guest.getByRole("heading", { name: "Members", exact: true }),
  ).toBeVisible();
  await expect(
    guest.getByRole("button", { name: "Send invitation" }),
  ).toHaveCount(0);
  const denied = await guest.evaluate(async () =>
    navigator.locks.request("bridgeops-session", async () => {
      const csrf = await (
        await fetch("/api/v1/auth/csrf", { cache: "no-store" })
      ).json();
      const rotated = await (
        await fetch("/api/v1/auth/refresh", {
          method: "POST",
          headers: { "X-CSRF-TOKEN": csrf.token },
        })
      ).json();
      const fresh = await (
        await fetch("/api/v1/auth/csrf", { cache: "no-store" })
      ).json();
      return (
        await fetch("/api/v1/organizations", {
          method: "POST",
          headers: {
            Authorization: `Bearer ${rotated.accessToken}`,
            "X-CSRF-TOKEN": fresh.token,
            "Content-Type": "application/json",
          },
          body: JSON.stringify({
            name: "Denied attempt",
            slug: "denied-browser-create",
          }),
        })
      ).status;
    }),
  );
  expect(denied).toBe(403);
  step = "foreign tenant concealment";
  console.log("STEP: " + step);
  await guest.goto("/organizations/" + process.env.BRIDGEOPS_TEST_FOREIGN_ORG);
  await expect(guest.getByRole("alert")).toContainText(
    "unavailable to your account",
  );
  await expect(
    guest.getByRole("heading", { name: "Outside tenant" }),
  ).toHaveCount(0);
  step = "revision-checked member management";
  console.log("STEP: " + step);
  await page.getByRole("button", { name: "Reload access & data" }).click();
  const member = page
    .locator("article.member-row")
    .filter({ hasText: "Jordan Lee" });
  await expect(member).toBeVisible();
  await member.getByRole("button", { name: "Edit member" }).click();
  await member.getByRole("checkbox", { name: "VIEWER", exact: true }).uncheck();
  await member.getByRole("checkbox", { name: "OPERATOR", exact: true }).check();
  await member.getByRole("button", { name: "Save membership" }).click();
  await expect(member).toContainText("OPERATOR");
  await expect(member).toContainText("Revision 1");
  step = "desktop mobile and dark render captures";
  console.log("STEP: " + step);
  await page.evaluate(() => window.scrollTo(0, 0));
  await mkdir(resolve("backend/target/browser-checks"), { recursive: true });
  await page.screenshot({
    path: resolve("backend/target/browser-checks/organizations-desktop.png"),
    fullPage: true,
    mask: [page.locator('input[type="password"],input[name="token"]')],
  });
  await page.getByRole("button", { name: "Switch to dark theme" }).click();
  await page.screenshot({
    path: resolve("backend/target/browser-checks/organizations-dark.png"),
    fullPage: true,
    mask: [page.locator('input[type="password"],input[name="token"]')],
  });
  await page.setViewportSize({ width: 390, height: 844 });
  expect(
    await page.evaluate(
      () => document.documentElement.scrollWidth <= window.innerWidth,
    ),
  ).toBe(true);
  expect(
    await page
      .locator(".button,.icon-button,.check-label")
      .evaluateAll((elements) =>
        elements.every(
          (element) => element.getBoundingClientRect().height >= 44,
        ),
      ),
  ).toBe(true);
  const history = page.getByRole("region", {
    name: "Invitation history",
    exact: true,
  });
  await history.focus();
  await expect(history).toBeFocused();
  await page.keyboard.press("ArrowRight");
  await expect
    .poll(() => history.evaluate((element) => element.scrollLeft))
    .toBeGreaterThan(0);
  await history.evaluate((element) => {
    element.scrollLeft = 0;
  });
  await page.evaluate(() => window.scrollTo(0, 0));
  await page.screenshot({
    path: resolve("backend/target/browser-checks/organizations-mobile.png"),
    fullPage: true,
    mask: [page.locator('input[type="password"],input[name="token"]')],
  });
  step = "sign-out and denied protected navigation";
  console.log("STEP: " + step);
  await guest.getByRole("link", { name: "Organizations", exact: true }).click();
  await guest.getByRole("button", { name: "Sign out", exact: true }).click();
  await expect(
    guest.getByRole("heading", { name: "Sign in to your workspace" }),
  ).toBeVisible();
  await guest.goto("/account");
  await expect(
    guest.getByRole("heading", { name: "Sign in to your workspace" }),
  ).toBeVisible();
  step = "recovery invalidates an independent live session";
  console.log("STEP: " + step);
  const otherContext = await browser.newContext({ baseURL: origin });
  const other = await otherContext.newPage();
  const otherAccess = await signIn(
    other,
    "jordan@example.invalid",
    invitedPassword,
  );
  step = "recovery request acknowledgement";
  console.log("STEP: " + step);
  await guest.getByRole("link", { name: "Forgot your password?" }).click();
  // Both routes have Email fields. Wait for the destination before filling one.
  await expect(
    guest.getByRole("heading", { name: "Recover your account", exact: true }),
  ).toBeVisible();
  await guest
    .getByLabel("Email", { exact: true })
    .fill("jordan@example.invalid");
  await guest.getByRole("button", { name: "Request recovery" }).click();
  await expect(guest.getByText(/If recovery is available/)).toContainText(
    "Delivery is not guaranteed",
  );
  step = "recovery test mail capture";
  console.log("STEP: " + step);
  const proof = await mail(
    "jordan@example.invalid",
    "BridgeOps account recovery",
  );
  step = "recovery proof submission";
  console.log("STEP: " + step);
  await guest.getByRole("link", { name: "I have a recovery token" }).click();
  await guest.getByLabel("Recovery token").fill(proof);
  await guest.getByLabel("New password", { exact: true }).fill(resetPassword);
  await guest.getByLabel("Confirm new password").fill(resetPassword);
  await guest.getByRole("button", { name: "Reset password" }).click();
  await expect(
    guest.getByRole("heading", { name: "Sign in to your workspace" }),
  ).toBeVisible();
  step = "recovery independent session revocation";
  console.log("STEP: " + step);
  expect(
    (
      await otherContext.request.get("/api/v1/auth/me", {
        headers: { Authorization: `Bearer ${otherAccess}` },
      })
    ).status(),
  ).toBe(401);
  await other.reload();
  await expect(
    other.getByRole("heading", { name: "Sign in to your workspace" }),
  ).toBeVisible();
  step = "authenticated password change";
  console.log("STEP: " + step);
  const beforeChange = await signIn(
    guest,
    "jordan@example.invalid",
    resetPassword,
  );
  await guest.getByRole("link", { name: "Account & sessions" }).click();
  await guest.getByLabel("Current password").fill(resetPassword);
  await guest.getByLabel("New password", { exact: true }).fill(changedPassword);
  await guest.getByLabel("Confirm new password").fill(changedPassword);
  await guest
    .getByRole("button", { name: "Change password", exact: true })
    .click();
  await expect(
    guest.getByRole("heading", { name: "Sign in to your workspace" }),
  ).toBeVisible();
  expect(
    (
      await guestContext.request.get("/api/v1/auth/me", {
        headers: { Authorization: `Bearer ${beforeChange}` },
      })
    ).status(),
  ).toBe(401);
  step = "logout-all and original owner sign-out";
  console.log("STEP: " + step);
  const beforeLogout = await signIn(
    guest,
    "jordan@example.invalid",
    changedPassword,
  );
  await guest.getByRole("link", { name: "Account & sessions" }).click();
  await guest.getByRole("button", { name: "Sign out all sessions" }).click();
  await expect(
    guest.getByRole("heading", { name: "Sign in to your workspace" }),
  ).toBeVisible();
  expect(
    (
      await guestContext.request.get("/api/v1/auth/me", {
        headers: { Authorization: `Bearer ${beforeLogout}` },
      })
    ).status(),
  ).toBe(401);
  await page.getByRole("button", { name: "Sign out", exact: true }).click();
  await expect(
    page.getByRole("heading", { name: "Sign in to your workspace" }),
  ).toBeVisible();
  expect(
    (
      await context.request.get("/api/v1/auth/me", {
        headers: { Authorization: `Bearer ${ownerAccess}` },
      })
    ).status(),
  ).toBe(401);
  step = "URL and persistent-cache exclusion";
  console.log("STEP: " + step);
  expect(
    urls.some((url) => /[?#].*(token|password|accessToken)=/i.test(url)),
  ).toBe(false);
  expect(
    await guest.evaluate(() => localStorage.length + sessionStorage.length),
  ).toBe(0);
  console.log(
    "PASS: real browser sign-in, concurrent tabs, organization/member/invitation flows, isolation, logout, recovery, password change and responsive renders.",
  );
} catch (failure) {
  console.error("FAIL: browser journey at step: " + step);
  // Emit source locations only: assertion messages can contain credential values.
  console.error("FAIL: source " + ((failure.stack ?? "").match(/journey\.mjs:\d+:\d+/g) ?? []).join(", "));
  if(page)console.error("LAYOUT: "+JSON.stringify(await page.evaluate(()=>({viewport:innerWidth,document:document.documentElement.scrollWidth,overflow:[...document.querySelectorAll("body *")].filter(e=>e.getBoundingClientRect().right>innerWidth+1).slice(0,8).map(e=>({tag:e.tagName,css:e.className,right:e.getBoundingClientRect().right}))})).catch(()=>({}))));
  if (page)
    console.error(
      (
        await page
          .locator("main")
          .innerText()
          .catch(() => "")
      ).slice(0, 4000),
    );
  process.exitCode = 1;
} finally {
  await browser?.close();
}
