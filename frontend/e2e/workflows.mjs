import { expect } from "@playwright/test";
import { mkdir } from "node:fs/promises";
import { resolve } from "node:path";

/** Real definition lifecycle. Credentials remain in this test process/closure, never output. */
export async function workflowJourney({
  page,
  guest,
  organizationUrl,
  access,
  report,
}) {
  const organization = new URL(organizationUrl).pathname.split("/").at(-1);
  const base = `/api/v1/organizations/${organization}/workflows`;
  const requested = [];
  const observe = (request) => requested.push(new URL(request.url()).pathname);
  page.on("request", observe);
  async function http(method, path, body) {
    return page.evaluate(
      async (input) =>
        navigator.locks.request("bridgeops-session", async () => {
          const headers = {
            Authorization: `Bearer ${input.access}`,
            "Content-Type": "application/json",
          };
          if (input.method !== "GET") {
            const csrf = await (
              await fetch("/api/v1/auth/csrf", { cache: "no-store" })
            ).json();
            headers["X-CSRF-TOKEN"] = csrf.token;
          }
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
      { method, path, body, access },
    );
  }
  async function save() {
    await expect(
      page.getByText("Unsaved changes", { exact: true }),
    ).toBeVisible();
    const pending = page.waitForResponse(
      (response) =>
        response.request().method() === "PATCH" &&
        response.url().includes(base + "/"),
    );
    await page.getByRole("button", { name: "Save draft", exact: true }).click();
    const response = await pending;
    expect(response.status()).toBe(200);
    await expect(
      page.getByText("All changes saved", { exact: true }),
    ).toBeVisible();
    return response.json();
  }
  async function publish(expectedStatus) {
    const pending = page.waitForResponse(
      (response) =>
        response.request().method() === "POST" &&
        response.url().endsWith("/publish"),
    );
    await page
      .getByRole("button", { name: "Publish draft", exact: true })
      .click();
    await expect(page.getByRole("dialog")).toBeVisible();
    await page
      .getByRole("button", { name: "Confirm publication", exact: true })
      .click();
    const response = await pending;
    expect(response.status()).toBe(expectedStatus);
    return response;
  }
  async function add(type) {
    await page.getByRole("combobox", { name: /^Node type/ }).selectOption(type);
    await page
      .getByRole("button", { name: "Add node before end", exact: true })
      .click();
  }
  try {
    report("workflow create and invalid publication");
    await page
      .getByRole("link", { name: "Open workflows", exact: true })
      .click();
    await expect(
      page.getByRole("heading", {
        name: "Organization workflows",
        exact: true,
      }),
    ).toBeVisible();
    await expect(
      page.getByRole("heading", { name: "No workflows yet", exact: true }),
    ).toBeVisible();
    await page
      .getByLabel("Workflow name", { exact: true })
      .fill("Ticket Dispatch");
    await page
      .getByRole("textbox", { name: /^Description/ })
      .fill("Manual review definition");
    await page
      .getByRole("button", { name: "Create workflow", exact: true })
      .click();
    await expect(
      page.getByRole("heading", { name: "Ticket Dispatch", exact: true }),
    ).toBeVisible();
    const workflowUrl = page.url();
    const workflowId = new URL(workflowUrl).pathname.split("/").at(-1);
    const item = `${base}/${workflowId}`;
    await page
      .getByRole("group", { name: /^End$/ })
      .getByRole("button", { name: "Remove node", exact: true })
      .click();
    await save();
    await publish(422);
    await expect(
      page.getByRole("heading", {
        name: "Saved definition needs changes",
        exact: true,
      }),
    ).toBeVisible();
    await expect(page.getByText(/Provide exactly one end node/)).toBeVisible();
    expect((await http("GET", `${item}/versions`)).body.totalElements).toBe(0);

    report("workflow graph correction save and reload");
    await add("end");
    const endId = (
      await page.getByRole("group", { name: /^End$/ }).getAttribute("id")
    ).slice(5);
    await page
      .getByRole("group", { name: /^Manual trigger$/ })
      .getByRole("combobox", { name: /^Next node/ })
      .selectOption(endId);
    await add("syntheticAction");
    await page.getByLabel("Message", { exact: true }).fill("Prepare plan");
    await add("approval");
    await page
      .getByLabel("Review prompt", { exact: true })
      .fill("Review proposed plan");
    await add("syntheticAction");
    await page
      .getByLabel("Message", { exact: true })
      .nth(1)
      .fill("Record approved plan");
    const saved = await save();
    expect(saved.definition.nodes).toHaveLength(5);
    expect(saved.definition.edges).toHaveLength(4);
    await page.reload();
    await expect(
      page.getByRole("heading", { name: "Ticket Dispatch", exact: true }),
    ).toBeVisible();
    await expect(
      page.getByLabel("Message", { exact: true }).nth(0),
    ).toHaveValue("Prepare plan");
    await expect(page.getByLabel("Review prompt", { exact: true })).toHaveValue(
      "Review proposed plan",
    );
    expect((await http("GET", item)).body.definition).toEqual(saved.definition);
    await page
      .getByRole("button", { name: "Validate saved draft", exact: true })
      .click();
    await expect(
      page.getByRole("heading", {
        name: "Saved definition is valid",
        exact: true,
      }),
    ).toBeVisible();

    report("workflow publication and immutable exact history");
    const firstResponse = await publish(201);
    const first = await firstResponse.json();
    await expect(
      page.getByText("Published version 1. This definition has not executed.", {
        exact: true,
      }),
    ).toBeVisible();
    await page
      .getByRole("link", { name: "Inspect version 1", exact: true })
      .click();
    await expect(
      page.getByRole("heading", { name: "Published version 1", exact: true }),
    ).toBeVisible();
    await expect(page.getByLabel("Published definition JSON")).toContainText(
      "Prepare plan",
    );
    await expect(
      page.getByRole("button", { name: "Save draft", exact: true }),
    ).toHaveCount(0);
    await expect(
      page.getByRole("button", { name: "Publish draft", exact: true }),
    ).toHaveCount(0);
    expect(
      (await http("GET", `${item}/versions/${first.version.id}`)).body,
    ).toEqual(first);
    await page
      .getByRole("link", { name: "Return to draft", exact: true })
      .click();
    await expect(
      page.getByRole("heading", { name: "Ticket Dispatch", exact: true }),
    ).toBeVisible();
    await page
      .getByLabel("Message", { exact: true })
      .nth(0)
      .fill("Revised prepare plan");
    await save();
    expect(
      (await http("GET", `${item}/versions/${first.version.id}`)).body,
    ).toEqual(first);
    await page
      .getByRole("link", { name: "Current published version 1", exact: true })
      .click();
    await expect(
      page.getByRole("heading", { name: "Published version 1", exact: true }),
    ).toBeVisible();
    await expect(
      page.getByLabel("Published definition JSON"),
    ).not.toContainText("Revised prepare plan");
    await page
      .getByRole("link", { name: "Return to draft", exact: true })
      .click();
    await expect(
      page.getByRole("heading", { name: "Ticket Dispatch", exact: true }),
    ).toBeVisible();
    const second = await (await publish(201)).json();
    expect(second.version.versionNumber).toBe(2);
    expect(second.version.definitionHash).not.toBe(
      first.version.definitionHash,
    );
    await expect(
      page.getByRole("link", { name: "Inspect version 2", exact: true }),
    ).toBeVisible();

    report("workflow responsive light and dark renders");
    await mkdir(resolve("backend/target/browser-checks"), { recursive: true });
    await page.evaluate(() => window.scrollTo(0, 0));
    await page.screenshot({
      path: resolve("backend/target/browser-checks/workflow-desktop.png"),
      fullPage: true,
    });
    await page
      .getByRole("button", { name: "Switch to dark theme", exact: true })
      .click();
    await page.evaluate(() => window.scrollTo(0, 0));
    await page.screenshot({
      path: resolve("backend/target/browser-checks/workflow-dark.png"),
      fullPage: true,
    });
    await page.setViewportSize({ width: 390, height: 844 });
    expect(
      await page.evaluate(
        () => document.documentElement.scrollWidth <= innerWidth,
      ),
    ).toBe(true);
    expect(
      await page
        .locator(".button,.icon-button")
        .evaluateAll((elements) =>
          elements.every(
            (element) => element.getBoundingClientRect().height >= 44,
          ),
        ),
    ).toBe(true);
    await page.screenshot({
      path: resolve("backend/target/browser-checks/workflow-mobile.png"),
      fullPage: true,
    });
    await page.setViewportSize({ width: 1440, height: 1000 });
    await page
      .getByRole("button", { name: "Switch to light theme", exact: true })
      .click();

    report("workflow stale edit preserves local changes");
    const current = (await http("GET", item)).body;
    const changed = await http("PATCH", item, {
      expectedRevision: current.workflow.revision,
      name: "Ticket Dispatch",
      description: "Concurrent stored description",
      definition: current.definition,
    });
    expect(changed.status).toBe(200);
    await page
      .getByRole("textbox", { name: /^Description/ })
      .fill("Local conflicting edit");
    const conflict = page.waitForResponse(
      (response) =>
        response.request().method() === "PATCH" &&
        response.url().endsWith(item),
    );
    await page.getByRole("button", { name: "Save draft", exact: true }).click();
    expect((await conflict).status()).toBe(409);
    await expect(page.getByRole("alert")).toContainText("record changed");
    await expect(
      page.getByRole("textbox", { name: /^Description/ }),
    ).toHaveValue("Local conflicting edit");
    await page
      .getByRole("button", { name: "Reload saved draft", exact: true })
      .click();
    await page
      .getByRole("button", { name: "Discard edits and reload", exact: true })
      .click();
    await expect(
      page.getByRole("textbox", { name: /^Description/ }),
    ).toHaveValue("Concurrent stored description");

    report("workflow cross tenant and read only denial");
    const foreignPath = `/api/v1/organizations/${process.env.BRIDGEOPS_TEST_FOREIGN_ORG}/workflows/${workflowId}/publish`;
    expect(
      (
        await http("POST", foreignPath, {
          expectedRevision: changed.body.workflow.revision,
        })
      ).status,
    ).toBe(404);
    await page.goto(
      `/organizations/${process.env.BRIDGEOPS_TEST_FOREIGN_ORG}/workflows/${workflowId}`,
    );
    await expect(page.getByRole("alert")).toContainText(
      "unavailable to your account",
    );
    await expect(
      page.getByRole("heading", { name: "Ticket Dispatch", exact: true }),
    ).toHaveCount(0);
    await page.goto(workflowUrl);
    await expect(
      page.getByRole("heading", { name: "Ticket Dispatch", exact: true }),
    ).toBeVisible();
    await guest.goto(new URL(workflowUrl).pathname);
    await expect(
      guest.getByRole("heading", { name: "Ticket Dispatch", exact: true }),
    ).toBeVisible();
    await expect(
      guest.getByLabel("Workflow name", { exact: true }),
    ).toBeDisabled();
    await expect(
      guest.getByRole("button", { name: "Publish draft", exact: true }),
    ).toHaveCount(0);
    const forbidden = await guest.evaluate(
      async (input) =>
        navigator.locks.request("bridgeops-session", async () => {
          const csrf = await (
            await fetch("/api/v1/auth/csrf", { cache: "no-store" })
          ).json();
          const session = await (
            await fetch("/api/v1/auth/refresh", {
              method: "POST",
              headers: { "X-CSRF-TOKEN": csrf.token },
            })
          ).json();
          const fresh = await (
            await fetch("/api/v1/auth/csrf", { cache: "no-store" })
          ).json();
          return (
            await fetch(input.path, {
              method: "POST",
              headers: {
                Authorization: `Bearer ${session.accessToken}`,
                "X-CSRF-TOKEN": fresh.token,
                "Content-Type": "application/json",
              },
              body: JSON.stringify({ expectedRevision: input.revision }),
            })
          ).status;
        }),
      { path: `${item}/publish`, revision: changed.body.workflow.revision },
    );
    expect(forbidden).toBe(403);

    report("workflow independent duplicate and archived history");
    await page
      .getByRole("button", { name: "Duplicate saved draft", exact: true })
      .click();
    await expect(
      page.getByRole("heading", {
        name: "Copy of Ticket Dispatch",
        exact: true,
      }),
    ).toBeVisible();
    await expect(
      page.getByText("Saved revision 0", { exact: true }),
    ).toBeVisible();
    await page
      .getByLabel("Workflow name", { exact: true })
      .fill("Independent dispatch draft");
    await save();
    expect((await http("GET", item)).body.workflow.name).toBe(
      "Ticket Dispatch",
    );
    await page.goto(workflowUrl);
    await expect(
      page.getByRole("heading", { name: "Ticket Dispatch", exact: true }),
    ).toBeVisible();
    await page
      .getByRole("button", { name: "Archive workflow", exact: true })
      .click();
    await page
      .getByRole("button", { name: "Confirm archive", exact: true })
      .click();
    await expect(
      page.getByText("Archived. Published history is preserved.", {
        exact: true,
      }),
    ).toBeVisible();
    await expect(
      page.getByLabel("Workflow name", { exact: true }),
    ).toBeDisabled();
    await expect(
      page.getByRole("link", { name: "Inspect version 1", exact: true }),
    ).toBeVisible();
    expect(
      (await http("GET", `${item}/versions/${first.version.id}`)).body,
    ).toEqual(first);
    expect(
      requested.some((path) =>
        /\/(executions|test-runs|action-requests|approvals|integrations)(\/|$)/.test(
          path,
        ),
      ),
    ).toBe(false);
    await page.goto(organizationUrl);
    await expect(
      page.getByRole("heading", { name: "Dispatch Operations", exact: true }),
    ).toBeVisible();
  } finally {
    page.off("request", observe);
  }
}
