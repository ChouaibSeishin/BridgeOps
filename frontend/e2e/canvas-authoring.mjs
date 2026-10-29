import { expect } from "@playwright/test";
import { resolve } from "node:path";
import { canvasContrastChecks } from "./ux.mjs";

/** Every definition change here uses visible canvas controls; no graph JSON/API write shortcut. */
export async function authorTriage(page, org, report) {
  report("canvas: create a fresh draft and explicitly convert its schema");
  await page.goto(`/organizations/${org}/workflows`);
  await page
    .getByRole("textbox", { name: "Workflow name", exact: true })
    .fill("Canvas-authored ticket triage");
  await page
    .getByRole("button", { name: "Create workflow", exact: true })
    .click();
  await page.waitForURL((url) =>
    url.pathname.startsWith(`/organizations/${org}/workflows/`),
  );
  const workflow = new URL(page.url()).pathname.split("/").at(-1);
  await page
    .getByRole("button", { name: "Convert draft to schema v2", exact: true })
    .click();
  await page
    .getByRole("button", { name: "Confirm schema v2 conversion", exact: true })
    .click();
  const inspect = page.getByRole("combobox", {
      name: "Inspect node",
      exact: true,
    }),
    ids = { start: "start", finish: "finish" };
  const select = async (id) => inspect.selectOption(id);
  async function add(type, key) {
    await page
      .getByRole("combobox", { name: "Canvas node type", exact: true })
      .selectOption(type);
    await page
      .getByRole("button", { name: "Add canvas node", exact: true })
      .click();
    ids[key] = await inspect.inputValue();
  }
  for (const [type, key] of [
    ["capabilityAction", "read"],
    ["capabilityAction", "resolve"],
    ["condition", "hasWork"],
    ["approval", "review"],
    ["capabilityAction", "assign"],
    ["notification", "notify"],
    ["transform", "noWork"],
  ])
    await add(type, key);
  report("canvas: connect a selected-path DAG with a safe no-work branch");
  for (const [source, target] of [
    ["start", "read"],
    ["read", "resolve"],
    ["resolve", "hasWork"],
    ["review", "assign"],
    ["assign", "notify"],
    ["notify", "finish"],
    ["noWork", "finish"],
  ]) {
    await select(ids[source]);
    await page
      .getByRole("combobox", { name: "Canvas next node", exact: true })
      .selectOption(ids[target]);
  }
  await select(ids.hasWork);
  await page
    .getByRole("combobox", { name: "True branch target", exact: true })
    .selectOption(ids.review);
  await page
    .getByRole("combobox", { name: "False branch target", exact: true })
    .selectOption(ids.noWork);
  const apply = () =>
    page
      .getByRole("button", { name: "Apply node properties", exact: true })
      .click();
  async function from(label, source, path) {
    await page
      .getByRole("combobox", { name: `${label} mapping`, exact: true })
      .selectOption("source");
    await page
      .getByRole("combobox", { name: `${label} source`, exact: true })
      .selectOption(ids[source]);
    await page
      .getByRole("combobox", { name: `${label} output field`, exact: true })
      .selectOption(path);
  }
  async function operation(key, connection, op) {
    await select(ids[key]);
    await page
      .getByRole("combobox", { name: "Registered connection", exact: true })
      .selectOption({ label: connection });
    if (op) {
      const operation = page.getByRole("combobox", {
        name: "Registered operation",
        exact: true,
      });
      await expect(
        operation.getByRole("option", { name: new RegExp(`^${op} ·`) }),
      ).toBeAttached();
      await operation.selectOption(op);
    }
  }
  report("canvas: declare typed inputs and bind registered read operations");
  await select(ids.start);
  await page
    .getByRole("textbox", { name: "New trigger field name", exact: true })
    .fill("limit");
  await page
    .getByRole("button", { name: "Add trigger field", exact: true })
    .click();
  await page
    .getByRole("combobox", { name: "limit trigger type", exact: true })
    .selectOption("INTEGER");
  await apply();
  await operation("read", "Example triage support", "list");
  await from("Input limit", "start", "limit");
  await page
    .getByRole("listbox", { name: "Ticket priorities", exact: true })
    .selectOption(["HIGH", "URGENT"]);
  await page
    .getByRole("listbox", { name: "Ticket statuses", exact: true })
    .selectOption(["OPEN", "IN_PROGRESS"]);
  await apply();
  await operation("resolve", "Example triage teams", "resolve");
  await from("Input tickets", "read", "tickets");
  await apply();
  await select(ids.hasWork);
  await from("Input value", "resolve", "count");
  await apply();
  report(
    "canvas: author exact approval, assignment and bounded notification mappings",
  );
  await select(ids.review);
  await page
    .getByRole("textbox", { name: "Approval review prompt", exact: true })
    .fill(
      "Review the exact ticket IDs, expected revisions, prior assignments and target teams before assignment.",
    );
  await apply();
  await operation("assign", "Example triage support", "assignBatch");
  await from("Input assignments", "resolve", "assignments");
  await apply();
  await operation("notify", "Example triage internal delivery");
  await page
    .getByRole("button", {
      name: "Prepare internal notification fields",
      exact: true,
    })
    .click();
  await page
    .getByRole("combobox", { name: "Input message mapping", exact: true })
    .selectOption("parts");
  await page
    .getByRole("textbox", {
      name: "Input message part 1 literal value",
      exact: true,
    })
    .fill("Assigned ");
  await page
    .getByRole("button", { name: "Add Input message text part", exact: true })
    .click();
  await from("Input message part 2", "assign", "applied");
  await page
    .getByRole("button", { name: "Add Input message text part", exact: true })
    .click();
  await page
    .getByRole("textbox", {
      name: "Input message part 3 literal value",
      exact: true,
    })
    .fill(" tickets");
  await apply();
  await select(ids.noWork);
  await page
    .getByRole("textbox", { name: "New transform field name", exact: true })
    .fill("result");
  await page
    .getByRole("button", { name: "Add transform field", exact: true })
    .click();
  await page
    .getByRole("textbox", { name: "Input result literal value", exact: true })
    .fill("No assignment needed");
  await apply();
  report("canvas: save, validate and immutably publish the authored workflow");
  await select(ids.read);
  await page
    .getByRole("button", { name: "Reset layout and fit", exact: true })
    .click();
  await canvasContrastChecks(page);
  await page.screenshot({
    path: resolve("backend/target/browser-checks/canvas-editor-desktop.png"),
    fullPage: true,
  });
  const saving = page.waitForResponse(
    (r) =>
      r.request().method() === "PATCH" &&
      r.url().endsWith(`/workflows/${workflow}`),
  );
  await page.getByRole("button", { name: "Save draft", exact: true }).click();
  expect((await saving).status()).toBe(200);
  const validating = page.waitForResponse(
    (r) => r.request().method() === "POST" && r.url().endsWith("/validate"),
  );
  await page
    .getByRole("button", { name: "Validate saved draft", exact: true })
    .click();
  expect((await (await validating).json()).valid).toBe(true);
  await page
    .getByRole("button", { name: "Publish draft", exact: true })
    .click();
  const publishing = page.waitForResponse(
    (r) => r.request().method() === "POST" && r.url().endsWith("/publish"),
  );
  await page
    .getByRole("button", { name: "Confirm publication", exact: true })
    .click();
  const published = await publishing;
  expect(published.status()).toBe(201);
  const snapshot = await published.json();
  expect(snapshot.version.schemaVersion).toBe(2);
  await page.reload();
  await expect(
    page.getByRole("button", { name: "Run published version", exact: true }),
  ).toBeEnabled();
  return { workflow, ids, snapshot };
}
