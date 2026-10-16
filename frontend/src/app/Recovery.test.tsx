import { render, screen, fireEvent } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";
import { afterEach, it, expect, vi } from "vitest";
import { Recovery, PasswordSettings } from "./Recovery";
import { session } from "../lib/session";
afterEach(() => vi.restoreAllMocks());
it("acknowledges recovery without claiming email delivery", async () => {
  vi.spyOn(session, "publicRequest").mockResolvedValue({});
  render(
    <MemoryRouter>
      <Recovery onReset={() => {}} />
    </MemoryRouter>,
  );
  fireEvent.change(screen.getByLabelText("Email"), {
    target: { value: "fixture@example.invalid" },
  });
  fireEvent.click(screen.getByRole("button", { name: "Request recovery" }));
  expect(await screen.findByText(/If recovery is available/)).toHaveTextContent(
    "Delivery is not guaranteed",
  );
});
it("validates password confirmation before sending a reset proof", async () => {
  const send = vi.spyOn(session, "publicRequest").mockResolvedValue({});
  render(
    <MemoryRouter>
      <Recovery reset onReset={() => {}} />
    </MemoryRouter>,
  );
  fireEvent.change(screen.getByLabelText("Recovery token"), {
    target: { value: "a".repeat(43) },
  });
  fireEvent.change(screen.getByLabelText("New password"), {
    target: { value: "synthetic-password" },
  });
  fireEvent.change(screen.getByLabelText("Confirm new password"), {
    target: { value: "different-password" },
  });
  fireEvent.click(screen.getByRole("button", { name: "Reset password" }));
  expect(await screen.findByRole("alert")).toHaveTextContent("do not match");
  expect(send).not.toHaveBeenCalled();
  expect(screen.getByLabelText("Recovery token")).toHaveValue("");
});
it("changes credentials with the current password and ends the UI session on success", async () => {
  const send = vi.spyOn(session, "request").mockResolvedValue(undefined);
  const changed = vi.fn();
  render(<PasswordSettings onChanged={changed} />);
  for (const [label, value] of [
    ["Current password", "synthetic-old-password"],
    ["New password", "synthetic-new-password"],
    ["Confirm new password", "synthetic-new-password"],
  ])
    fireEvent.change(screen.getByLabelText(label!), { target: { value } });
  fireEvent.click(screen.getByRole("button", { name: "Change password" }));
  expect(
    await screen.findByText("Completed successfully."),
  ).toBeInTheDocument();
  expect(send).toHaveBeenCalledWith(
    "/api/v1/auth/password/change",
    expect.objectContaining({ method: "POST" }),
  );
  expect(changed).toHaveBeenCalledOnce();
});
