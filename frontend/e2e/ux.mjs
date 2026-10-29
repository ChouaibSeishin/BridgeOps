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
        fullPage: false,
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

export async function canvasWorkbenchChecks(page) {
  const originalViewport = page.viewportSize();
  const workbench = page.locator(".canvas-workbench");
  for (const width of [1440, 1280, 768, 390]) {
    console.log(`STEP: canvas responsive geometry and visible nodes ${width}`);
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
    await expect
      .poll(() =>
        workbench.evaluate((element) => {
          const area = element
            .querySelector(".canvas-diagram")
            .getBoundingClientRect();
          return [...element.querySelectorAll(".canvas-node")].filter(
            (node) => {
              const box = node.getBoundingClientRect();
              return (
                box.width > 0 &&
                box.right > area.left &&
                box.left < area.right &&
                box.bottom > area.top &&
                box.top < area.bottom
              );
            },
          ).length;
        }),
      )
      .toBeGreaterThan(0);
    const geometry = await workbench.evaluate((element) => {
      const box = (selector) => {
        const r = element.querySelector(selector).getBoundingClientRect();
        return {
          left: r.left,
          right: r.right,
          top: r.top,
          bottom: r.bottom,
          width: r.width,
        };
      };
      return {
        palette: box(".canvas-palette"),
        stage: box(".canvas-stage"),
        inspector: box(".canvas-inspector"),
      };
    });
    if (width >= 1280) {
      expect(geometry.palette.right).toBeLessThanOrEqual(
        geometry.stage.left + 1,
      );
      expect(geometry.stage.right).toBeLessThanOrEqual(
        geometry.inspector.left + 1,
      );
    } else {
      expect(geometry.inspector.top).toBeGreaterThanOrEqual(
        geometry.stage.bottom - 1,
      );
      if (width === 390)
        expect(geometry.stage.top).toBeGreaterThanOrEqual(
          geometry.palette.bottom - 1,
        );
    }
    await page
      .getByRole("button", { name: "Focus selected node", exact: true })
      .click();
    try {
      await expect(
        page.locator(".react-flow__node.selected .canvas-node"),
      ).toBeInViewport();
    } catch (error) {
      await page.screenshot({
        path: resolve(
          `backend/target/browser-checks/ux-canvas-focus-failure-${width}.png`,
        ),
      });
      console.log(
        "CANVAS_FOCUS_GEOMETRY: " +
          JSON.stringify(
            await workbench.evaluate((element) => {
              const bounds = (selector) => {
                const rect = element
                  .querySelector(selector)
                  ?.getBoundingClientRect();
                return rect
                  ? {
                      x: rect.x,
                      y: rect.y,
                      width: rect.width,
                      height: rect.height,
                    }
                  : null;
              };
              return {
                canvas: bounds(".canvas-diagram"),
                node: bounds(".react-flow__node.selected"),
                viewport: { width: innerWidth, height: innerHeight },
              };
            }),
          ),
      );
      throw error;
    }
    await page
      .getByRole("combobox", { name: "Inspect node", exact: true })
      .focus();
    await expect(
      page.getByRole("combobox", { name: "Inspect node", exact: true }),
    ).toBeFocused();
    await page.evaluate(() => {
      window.scrollTo(0, 0);
      const inspector = document.querySelector(".canvas-inspector");
      if (inspector) inspector.scrollTop = 0;
    });
    await page.screenshot({
      path: resolve(`backend/target/browser-checks/ux-canvas-${width}.png`),
      fullPage: true,
    });
  }
  await page.setViewportSize(originalViewport);
}

/** Read-only inspection of actual disposable operational fixtures at each target width. */
export async function operationalVisualChecks(page, name) {
  const original = page.viewportSize();
  for (const width of [1440, 1280, 768, 390]) {
    console.log(`STEP: ${name} responsive records and contrast ${width}`);
    await page.setViewportSize({ width, height: width === 390 ? 844 : 1024 });
    await expect
      .poll(() =>
        page.evaluate(
          () => document.documentElement.scrollWidth <= innerWidth + 1,
        ),
      )
      .toBe(true);
    for (const region of await page.locator(".table-scroll").all()) {
      await region.focus();
      await expect(region).toBeFocused();
      if (
        await region.evaluate(
          (element) => element.scrollWidth > element.clientWidth + 1,
        )
      ) {
        await region.evaluate((element) => {
          element.scrollLeft = 0;
        });
        await page.keyboard.press("ArrowRight");
        await expect
          .poll(() => region.evaluate((element) => element.scrollLeft))
          .toBeGreaterThan(0);
        await region.evaluate((element) => {
          element.scrollLeft = 0;
        });
      }
    }
    const badges = await page.locator(".status-badge").evaluateAll((elements) =>
      elements
        .filter((element) => element.getClientRects().length)
        .map((element) => ({
          text: getComputedStyle(element).color,
          background: getComputedStyle(element).backgroundColor,
        })),
    );
    for (const badge of badges)
      expect(contrast(badge.text, badge.background)).toBeGreaterThanOrEqual(
        4.5,
      );
    const control = page.locator(".button:visible:not(:disabled)").first();
    if (await control.count()) {
      await page.keyboard.press("Tab");
      await control.focus();
      await expect(control).toBeFocused();
      const colors = await control.evaluate((element) => {
        const style = getComputedStyle(element);
        let parent = element.parentElement;
        while (
          parent &&
          getComputedStyle(parent).backgroundColor === "rgba(0, 0, 0, 0)"
        )
          parent = parent.parentElement;
        return {
          outline: style.outlineColor,
          width: parseFloat(style.outlineWidth),
          style: style.outlineStyle,
          surface: getComputedStyle(parent ?? document.body).backgroundColor,
          height: element.getBoundingClientRect().height,
        };
      });
      expect(colors.width).toBeGreaterThanOrEqual(2);
      expect(colors.style).not.toBe("none");
      expect(contrast(colors.outline, colors.surface)).toBeGreaterThanOrEqual(
        3,
      );
      expect(colors.height).toBeGreaterThanOrEqual(width <= 640 ? 44 : 40);
    }
    await page.evaluate(() => window.scrollTo(0, 0));
    await page.screenshot({
      path: resolve(`backend/target/browser-checks/ux-${name}-${width}.png`),
      fullPage: true,
      mask: [page.locator('input[type="password"],input[name="token"]')],
    });
  }
  await page.setViewportSize(original);
}
