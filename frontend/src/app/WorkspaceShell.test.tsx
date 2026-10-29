import { fireEvent, render, screen, within } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { MemoryRouter } from "react-router-dom";
import { afterEach, expect, it, vi } from "vitest";
import { WorkspaceShell } from "./WorkspaceShell";
import { session } from "../lib/session";

afterEach(() => vi.restoreAllMocks());
function show(permissions: string[], path = "/organizations/org/workflows") {
  const request = vi
    .spyOn(session, "request")
    .mockImplementation(async (url) => {
      if (url.endsWith("/access"))
        return { membershipId: "member", permissions } as never;
      if (url === "/api/v1/organizations/org")
        return {
          id: "org",
          name: "Operations with a deliberately long organization name",
          slug: "operations",
        } as never;
      throw new Error("Unexpected request");
    });
  render(
    <QueryClientProvider
      client={
        new QueryClient({
          defaultOptions: { queries: { retry: false, gcTime: 0 } },
        })
      }
    >
      <MemoryRouter initialEntries={[path]}>
        <WorkspaceShell
          user={{
            id: "actor",
            displayName: "Operator",
            email: "fixture@example.invalid",
          }}
          dark={false}
          theme={vi.fn()}
          logout={vi.fn()}
        >
          <h1>Current page</h1>
        </WorkspaceShell>
      </MemoryRouter>
    </QueryClientProvider>,
  );
  return request;
}
it("shows only currently permitted organization destinations and keeps the organization context", async () => {
  show(["workflow.read", "execution.read"]);
  const navigation = screen.getByRole("navigation", {
    name: "Primary navigation",
  });
  expect(
    await within(navigation).findByRole("link", { name: "Executions" }),
  ).toHaveAttribute("href", "/organizations/org/executions");
  expect(
    within(navigation).queryByRole("link", { name: "Approvals" }),
  ).not.toBeInTheDocument();
  expect(
    within(navigation).queryByRole("link", { name: "Integrations" }),
  ).not.toBeInTheDocument();
  expect(
    within(navigation).getByRole("link", { name: "Workflows" }),
  ).toHaveAttribute("aria-current", "page");
  expect(
    await within(
      screen.getByRole("complementary", { name: "Workspace navigation" }),
    ).findByTitle("Operations with a deliberately long organization name"),
  ).toBeVisible();
});
it("does not request organization data outside an organization route", () => {
  const request = show([], "/account");
  expect(request).not.toHaveBeenCalled();
  expect(
    screen.getByRole("navigation", { name: "Breadcrumb" }),
  ).toHaveTextContent("Account & sessions");
});
it("opens a named navigation dialog, follows actual links and returns focus", async () => {
  show(["approval.read"]);
  const trigger = screen.getByRole("button", { name: "Open navigation" });
  trigger.focus();
  fireEvent.click(trigger);
  const drawer = screen.getByRole("dialog", { name: "Workspace navigation" });
  expect(drawer).toHaveFocus();
  expect(
    await within(drawer).findByRole("link", { name: "Approvals" }),
  ).toHaveAttribute("href", "/organizations/org/approvals");
  fireEvent.click(
    within(drawer).getByRole("button", { name: "Close navigation" }),
  );
  expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
  expect(trigger).toHaveFocus();
});
