import { fireEvent, render, screen } from "@testing-library/react";
import { expect, it } from "vitest";
import { ValidationResults } from "./Feedback";
import { addNode, emptyGraph } from "./model";

it("uses a friendly validation anchor while retaining the full ID in collapsed technical details", () => {
  const id = "long_generated_identifier_for_reconciliation";
  render(
    <ValidationResults
      definition={addNode(emptyGraph(), "syntheticAction", id)}
      result={{
        valid: false,
        errors: [
          {
            nodeId: id,
            path: "config.message",
            code: "REQUIRED",
            message: "A message is required.",
          },
        ],
      }}
    />,
  );
  expect(
    screen.getByRole("link", { name: "Synthetic action 1" }),
  ).toHaveAttribute("href", `#node-${id}`);
  const details = screen
    .getByText("Validation technical details")
    .closest("details")!;
  expect(details.open).toBe(false);
  fireEvent.click(screen.getByText("Validation technical details"));
  expect(screen.getByText(id)).toBeVisible();
});
