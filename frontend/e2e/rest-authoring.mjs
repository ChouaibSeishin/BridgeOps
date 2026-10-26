import { expect } from "@playwright/test";
import { resolve } from "node:path";

export async function authorRest(page, org, workflow, report) {
  report(
    "REST canvas: typed literal fields, declared trigger and constructed request object",
  );
  const inspect = page.getByRole("combobox", {
    name: "Inspect node",
    exact: true,
  });
  await inspect.selectOption("http");
  await expect(
    page.getByRole("textbox", { name: "fields.message (STRING)", exact: true }),
  ).toBeVisible();
  await page
    .getByRole("textbox", { name: "fields.message (STRING)", exact: true })
    .fill("Literal draft preview");
  await page
    .getByRole("button", { name: "Apply node properties", exact: true })
    .click();
  await inspect.selectOption("start");
  await page
    .getByRole("textbox", { name: "New trigger field name", exact: true })
    .fill("message");
  await page
    .getByRole("button", { name: "Add trigger field", exact: true })
    .click();
  await page
    .getByRole("button", { name: "Apply node properties", exact: true })
    .click();
  await page
    .getByRole("combobox", { name: "Canvas node type", exact: true })
    .selectOption("transform");
  await page
    .getByRole("button", { name: "Add canvas node", exact: true })
    .click();
  const build = await inspect.inputValue();
  await inspect.selectOption("start");
  await page
    .getByRole("combobox", { name: "Canvas next node", exact: true })
    .selectOption(build);
  await inspect.selectOption(build);
  await page
    .getByRole("combobox", { name: "Canvas next node", exact: true })
    .selectOption("http");
  await page
    .getByRole("textbox", { name: "New transform field name", exact: true })
    .fill("message");
  await page
    .getByRole("button", { name: "Add transform field", exact: true })
    .click();
  await page
    .getByRole("combobox", { name: "Input message mapping", exact: true })
    .selectOption("source");
  await page
    .getByRole("combobox", { name: "Input message source", exact: true })
    .selectOption("start");
  await page
    .getByRole("combobox", { name: "Input message output field", exact: true })
    .selectOption("message");
  await page
    .getByRole("button", { name: "Apply node properties", exact: true })
    .click();
  await inspect.selectOption("http");
  await page
    .getByRole("combobox", { name: "Input fields mapping", exact: true })
    .selectOption("source");
  await page
    .getByRole("combobox", { name: "Input fields source", exact: true })
    .selectOption(build);
  await page
    .getByRole("combobox", { name: "Input fields output field", exact: true })
    .selectOption("$");
  await page
    .getByRole("button", { name: "Apply node properties", exact: true })
    .click();
  await page
    .getByRole("button", { name: "Reset layout and fit", exact: true })
    .click();
  await page.screenshot({
    path: resolve("backend/target/browser-checks/rest-editor-desktop.png"),
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
  const adopted = page.waitForResponse(
    (r) =>
      r.request().method() === "GET" &&
      r.url().endsWith(`/workflows/${workflow}`),
  );
  await page
    .getByRole("button", { name: "Confirm publication", exact: true })
    .click();
  expect((await publishing).status()).toBe(201);
  expect((await adopted).status()).toBe(200);
  await expect(
    page.getByText("All changes saved", { exact: true }),
  ).toBeVisible();
  await expect(
    page.getByRole("button", { name: "Run published version", exact: true }),
  ).toBeEnabled();
}
