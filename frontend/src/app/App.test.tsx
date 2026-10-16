import { render, screen, fireEvent } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { MemoryRouter } from "react-router-dom";
import { afterEach, it, expect, vi } from "vitest";
import { App } from "./App";
import { session } from "../lib/session";
afterEach(() => {
  session.clear();
  vi.restoreAllMocks();
});
function show(path = "/") {
  render(
    <QueryClientProvider
      client={
        new QueryClient({
          defaultOptions: { queries: { retry: false, gcTime: 0 } },
        })
      }
    >
      <MemoryRouter initialEntries={[path]}>
        <App />
      </MemoryRouter>
    </QueryClientProvider>,
  );
}
it("guards private routes and renders usable sign-in fields", async () => {
  vi.spyOn(session, "initialize").mockResolvedValue();
  session.clear();
  show("/account");
  expect(
    await screen.findByRole("heading", { name: "Sign in to your workspace" }),
  ).toBeInTheDocument();
  expect(screen.getByLabelText("Password")).toHaveAttribute("type", "password");
});
it("shows safe login failures and clears the password field", async () => {
  vi.spyOn(session, "initialize").mockResolvedValue();
  session.clear();
  vi.spyOn(session, "login").mockRejectedValue(
    new Error("private provider detail"),
  );
  show();
  fireEvent.change(screen.getByLabelText("Email"), {
    target: { value: "person@example.com" },
  });
  fireEvent.change(screen.getByLabelText("Password"), {
    target: { value: "synthetic-password" },
  });
  fireEvent.click(screen.getByRole("button", { name: "Sign in" }));
  expect(await screen.findByRole("alert")).not.toHaveTextContent(
    "private provider detail",
  );
  expect(screen.getByLabelText("Password")).toHaveValue("");
});
