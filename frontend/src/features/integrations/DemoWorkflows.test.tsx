import { render, screen, fireEvent, waitFor } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { MemoryRouter } from "react-router-dom";
import { beforeEach, afterEach, it, expect, vi } from "vitest";
import { DemoWorkflows } from "./DemoWorkflows";
import { demoWorkflowApi, type DemoArtifacts } from "./demoWorkflowApi";
const artifacts: DemoArtifacts = {
  support: { id: "support", revision: 0 },
  teams: { id: "teams", revision: 0 },
  notifications: { id: "internal", revision: 0 },
  manual: {
    workflowId: "manual",
    versionId: "manual-v1",
    definitionHash: "a".repeat(64),
  },
  webhook: {
    workflowId: "webhook",
    versionId: "webhook-v1",
    definitionHash: "b".repeat(64),
  },
  createdAt: "2026-10-22T00:00:00Z",
  provenance: "SEEDED_EXAMPLE",
};
beforeEach(() => {
  vi.spyOn(demoWorkflowApi, "get").mockResolvedValue({
    created: false,
    artifacts: null,
  });
  vi.spyOn(demoWorkflowApi, "install").mockResolvedValue({
    created: true,
    artifacts,
  });
});
afterEach(() => vi.restoreAllMocks());
function show(canInstall = true) {
  render(
    <QueryClientProvider
      client={
        new QueryClient({ defaultOptions: { queries: { retry: false } } })
      }
    >
      <MemoryRouter>
        <DemoWorkflows user="viewer" org="org" canInstall={canInstall} />
      </MemoryRouter>
    </QueryClientProvider>,
  );
}
it("requires explicit confirmation and explains the precise non-executing setup", async () => {
  show();
  fireEvent.click(
    await screen.findByRole("button", { name: "Set up triage workflows" }),
  );
  expect(demoWorkflowApi.install).not.toHaveBeenCalled();
  expect(
    screen.getByText(
      /creates no execution, automation principal, webhook endpoint or signing key/,
    ),
  ).toBeVisible();
  fireEvent.click(
    screen.getByRole("button", { name: "Keep existing workflows only" }),
  );
  expect(demoWorkflowApi.install).not.toHaveBeenCalled();
});
it("renders the committed immutable references and invalidates business data after installation", async () => {
  vi.mocked(demoWorkflowApi.get)
    .mockResolvedValueOnce({ created: false, artifacts: null })
    .mockResolvedValue({ created: false, artifacts });
  show();
  fireEvent.click(
    await screen.findByRole("button", { name: "Set up triage workflows" }),
  );
  fireEvent.click(
    screen.getByRole("button", { name: "Confirm setup triage workflows" }),
  );
  expect(
    await screen.findByRole("link", { name: "Open manual triage example" }),
  ).toHaveAttribute("href", "/organizations/org/workflows/manual");
  expect(
    screen.getByRole("link", { name: "Inspect webhook triage example" }),
  ).toHaveAttribute(
    "href",
    "/organizations/org/workflows/webhook/versions/webhook-v1",
  );
  expect(demoWorkflowApi.install).toHaveBeenCalledExactlyOnceWith("org");
  await waitFor(() => expect(demoWorkflowApi.get).toHaveBeenCalledTimes(2));
  expect(
    screen.getByText(/no automation authority or signing key was created/),
  ).toBeVisible();
});
it("shows existing examples to readers without offering setup or claiming callbacks are enabled", async () => {
  vi.mocked(demoWorkflowApi.get).mockResolvedValue({
    created: false,
    artifacts,
  });
  show(false);
  await screen.findByRole("link", { name: "Open manual triage example" });
  expect(
    screen.queryByRole("button", { name: "Set up triage workflows" }),
  ).not.toBeInTheDocument();
  expect(
    screen.getByText(/publishing it does not enable callbacks/),
  ).toBeVisible();
  expect(
    screen.getByText(
      /setup does not repair later connection or workflow edits/,
    ),
  ).toBeVisible();
});
it("requires the existing permission intersection when no examples exist", async () => {
  show(false);
  expect(
    await screen.findByText(/Installation requires integration.manage/),
  ).toBeVisible();
  expect(demoWorkflowApi.install).not.toHaveBeenCalled();
});
it("fails closed when example references cannot be loaded and supports explicit reload", async () => {
  vi.mocked(demoWorkflowApi.get).mockRejectedValueOnce(
    new Error("Unavailable"),
  );
  show();
  await screen.findByRole("alert");
  expect(
    screen.queryByRole("button", { name: "Set up triage workflows" }),
  ).not.toBeInTheDocument();
  fireEvent.click(screen.getByRole("button", { name: /try again|retry/i }));
  await screen.findByRole("button", { name: "Set up triage workflows" });
  expect(demoWorkflowApi.install).not.toHaveBeenCalled();
});
it("keeps the confirmation available after a failed installation without fabricating references", async () => {
  vi.mocked(demoWorkflowApi.install).mockRejectedValue(
    new Error("Unavailable"),
  );
  show();
  fireEvent.click(
    await screen.findByRole("button", { name: "Set up triage workflows" }),
  );
  fireEvent.click(
    screen.getByRole("button", { name: "Confirm setup triage workflows" }),
  );
  await screen.findByRole("alert");
  expect(
    screen.queryByRole("link", { name: "Open manual triage example" }),
  ).not.toBeInTheDocument();
  expect(
    screen.getByRole("button", { name: "Confirm setup triage workflows" }),
  ).toBeEnabled();
});
