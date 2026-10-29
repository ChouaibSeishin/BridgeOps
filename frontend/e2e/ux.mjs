import { expect } from "@playwright/test";
import { resolve } from "node:path";

function contrast(a, b) {
  function luminance(rgb) {
    const channels = rgb
      .match(/[\d.]+/g)
      .slice(0, 3)
      .map(Number)
      .map((v) => {
        const c = v / 255;
        return c <= 0.04045 ? c / 12.92 : ((c + 0.055) / 1.055) ** 2.4;
      });
    return 0.2126 * channels[0] + 0.7152 * channels[1] + 0.0722 * channels[2];
  }
  const x = luminance(a),
    y = luminance(b);
  return (Math.max(x, y) + 0.05) / (Math.min(x, y) + 0.05);
}

/** Runs on actual authenticated disposable fixtures, never a user's installation. */
export async function workspaceVisualChecks(page) {
  const viewport = page.viewportSize();
  const navigation = page.getByRole("navigation", {
    name: "Primary navigation",
  });
  await expect(
    navigation.getByRole("link", { name: "Workflows", exact: true }),
  ).toBeVisible();
  for (const width of [1440, 1280, 768, 390]) {
    console.log(
      `STEP: workspace responsive keyboard and overflow checks ${width}`,
    );
    await page.setViewportSize({
      width,
      height: width === 390 ? 844 : width === 768 ? 1024 : 960,
    });
    await expect
      .poll(() =>
        page.evaluate(
          () => document.documentElement.scrollWidth <= innerWidth + 1,
        ),
      )
      .toBe(true);
    const toggle = page.getByRole("button", {
      name: "Open navigation",
      exact: true,
    });
    if (width < 1000) {
      await toggle.click();
      const drawer = page.getByRole("dialog", { name: "Workspace navigation" });
      await expect(drawer).toBeVisible();
      await expect(drawer).toBeFocused();
      for (let i = 0; i < 12; i++) {
        await page.keyboard.press(i < 6 ? "Tab" : "Shift+Tab");
        expect(
          await drawer.evaluate((element) =>
            element.contains(document.activeElement),
          ),
        ).toBe(true);
      }
      await page.screenshot({
        path: resolve(
          `backend/target/browser-checks/ux-navigation-${width}.png`,
        ),
        fullPage: true,
        mask: [page.locator('input[type="password"],input[name="token"]')],
      });
      await page.keyboard.press("Escape");
      await expect(drawer).not.toBeVisible();
      await expect(toggle).toBeFocused();
    } else {
      await expect(toggle).not.toBeVisible();
      await expect(navigation).toBeVisible();
    }
    await page.screenshot({
      path: resolve(`backend/target/browser-checks/ux-workspace-${width}.png`),
      fullPage: true,
      mask: [page.locator('input[type="password"],input[name="token"]')],
    });
  }
  await page.setViewportSize(viewport);
  const colors = await page.evaluate(() => ({
    text: getComputedStyle(document.body).color,
    background: getComputedStyle(document.body).backgroundColor,
  }));
  expect(contrast(colors.text, colors.background)).toBeGreaterThanOrEqual(4.5);
}

export async function canvasContrastChecks(page) {
  const canvas = page.locator(".canvas-diagram .react-flow");
  await expect(canvas).toBeVisible();
  const colors = await canvas.evaluate((element) => {
    const node = element.querySelector(".canvas-node"),
      label = element.querySelector(".canvas-node-kind");
    return {
      background: getComputedStyle(element).backgroundColor,
      node: getComputedStyle(node).backgroundColor,
      text: getComputedStyle(node).color,
      muted: getComputedStyle(label).color,
      body: getComputedStyle(document.body).backgroundColor,
    };
  });
  expect(colors.background).not.toBe(colors.body);
  expect(contrast(colors.text, colors.node)).toBeGreaterThanOrEqual(4.5);
  expect(contrast(colors.muted, colors.node)).toBeGreaterThanOrEqual(4.5);
}
