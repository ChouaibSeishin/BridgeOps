import { useState } from "react";
import { fireEvent, render, screen } from "@testing-library/react";
import { expect, it, vi } from "vitest";
import { ConfirmationDialog, EmptyState, PageHeader, StatusBadge } from "./ui";

it("keeps persisted state text and distinguishes failure from success without color alone", () => {
  render(
    <>
      <StatusBadge value="WAITING_FOR_APPROVAL" live />
      <StatusBadge value="FAILED" />
      <StatusBadge value="SUCCEEDED" />
    </>,
  );
  expect(screen.getByRole("status")).toHaveTextContent("WAITING FOR APPROVAL");
  expect(screen.getByText("FAILED").closest(".status-badge")).toHaveClass(
    "badge-danger",
  );
  expect(screen.getByText("SUCCEEDED").closest(".status-badge")).toHaveClass(
    "badge-success",
  );
});
it("renders honest empty state actions and a semantic page heading", () => {
  const action = vi.fn();
  render(
    <>
      <PageHeader title="Operations" description="Persisted records" />
      <EmptyState title="No records">
        <span>Nothing has run.</span>
      </EmptyState>
      <button onClick={action}>Create draft</button>
    </>,
  );
  expect(screen.getByRole("heading", { level: 1 })).toHaveTextContent(
    "Operations",
  );
  expect(screen.getByText("Nothing has run.")).toBeVisible();
  fireEvent.click(screen.getByRole("button", { name: "Create draft" }));
  expect(action).toHaveBeenCalledOnce();
});
it("moves focus into a confirmation and restores its trigger on cancellation", () => {
  function Harness() {
    const [open, setOpen] = useState(false);
    return (
      <>
        <button onClick={() => setOpen(true)}>Publish</button>
        {open && (
          <ConfirmationDialog
            label="Publish revision 3"
            onCancel={() => setOpen(false)}
          >
            <button onClick={() => setOpen(false)}>Keep editing</button>
          </ConfirmationDialog>
        )}
      </>
    );
  }
  render(<Harness />);
  const trigger = screen.getByRole("button", { name: "Publish" });
  trigger.focus();
  fireEvent.click(trigger);
  expect(
    screen.getByRole("dialog", { name: "Publish revision 3" }),
  ).toHaveFocus();
  fireEvent.click(screen.getByRole("button", { name: "Keep editing" }));
  expect(trigger).toHaveFocus();
  expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
});
it("does not dismiss an in-flight confirmation on the native cancel event", () => {
  const cancel = vi.fn();
  render(
    <ConfirmationDialog label="Saving" busy onCancel={cancel}>
      Saving the decision…
    </ConfirmationDialog>,
  );
  fireEvent(
    screen.getByRole("dialog"),
    new Event("cancel", { bubbles: true, cancelable: true }),
  );
  expect(cancel).not.toHaveBeenCalled();
});
