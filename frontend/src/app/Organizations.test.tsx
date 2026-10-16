import { render, screen, fireEvent } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { MemoryRouter } from "react-router-dom";
import { afterEach, it, expect, vi } from "vitest";
import { Organizations, InvitationJourney } from "./Organizations";
import { session, HttpFailure } from "../lib/session";
afterEach(() => vi.restoreAllMocks());
function show(element: React.ReactNode) {
  render(
    <QueryClientProvider
      client={
        new QueryClient({
          defaultOptions: { queries: { retry: false, gcTime: 0 } },
        })
      }
    >
      <MemoryRouter>{element}</MemoryRouter>
    </QueryClientProvider>,
  );
}
it("uses the live account capability for creation and real pagination totals", async () => {
  vi.spyOn(session, "request").mockResolvedValue({
    items: [],
    page: 0,
    size: 10,
    totalElements: 0,
    totalPages: 0,
  });
  show(
    <Organizations
      user={{
        id: "fixture",
        email: "fixture@example.invalid",
        displayName: "Fixture",
        canCreateOrganizations: false,
      }}
    />,
  );
  expect(
    await screen.findByText("Your workspace starts here"),
  ).toBeInTheDocument();
  expect(
    screen.queryByRole("button", { name: "Create organization" }),
  ).not.toBeInTheDocument();
  expect(screen.getByText("0 total")).toBeInTheDocument();
});
it("shows denied organization reads as failures without fabricated data", async () => {
  vi.spyOn(session, "request").mockRejectedValue(
    new HttpFailure(403, "DENIED"),
  );
  show(
    <Organizations
      user={{
        id: "fixture",
        email: "fixture@example.invalid",
        displayName: "Fixture",
      }}
    />,
  );
  expect(await screen.findByRole("alert")).toHaveTextContent("not permitted");
});
it("enrollment sends the token only in the body and requires explicit sign-in afterward", async () => {
  const send = vi.spyOn(session, "publicRequest").mockResolvedValue({});
  show(<InvitationJourney enroll />);
  fireEvent.change(screen.getByLabelText("Invitation token"), {
    target: { value: "a".repeat(43) },
  });
  fireEvent.change(screen.getByLabelText("Email"), {
    target: { value: "new@example.invalid" },
  });
  fireEvent.change(screen.getByLabelText("Display name"), {
    target: { value: "New Person" },
  });
  fireEvent.change(screen.getByLabelText("Password"), {
    target: { value: "synthetic-password" },
  });
  fireEvent.click(
    screen.getByRole("button", { name: "Create invited account" }),
  );
  expect(
    await screen.findByRole("link", { name: "Continue to sign in" }),
  ).toHaveAttribute("href", "/sign-in");
  expect(send).toHaveBeenCalledWith(
    "/api/v1/invitations/enroll",
    expect.objectContaining({ token: "a".repeat(43) }),
  );
});
