import { render, screen } from "@testing-library/react";
import { expect, it } from "vitest";
import { requiresExactNumbers } from "./precision";
import { ReadOnlyDefinition } from "./GraphEditor";

it("detects unsafe integers in nested mappings without changing safe values or numeric strings", () => {
  expect(
    requiresExactNumbers({
      fields: [{ literal: 9007199254740991 }, "9223372036854775807"],
    }),
  ).toBe(false);
  for (const value of [9007199254740992, -9007199254740992, Infinity, NaN])
    expect(requiresExactNumbers({ fields: [{ literal: value }] })).toBe(true);
});
it("inspects exact published JSON without showing a rounded graph and fails closed without exact text", () => {
  const source =
      '{"schemaVersion":2,"nodes":[],"edges":[],"value":9223372036854775807}',
    definition: unknown = JSON.parse(source);
  const view = render(
    <ReadOnlyDefinition definition={definition} definitionJson={source} />,
  );
  expect(screen.getByLabelText("Published definition JSON").textContent).toBe(
    source,
  );
  expect(screen.queryByText("Visual published graph")).not.toBeInTheDocument();
  view.rerender(<ReadOnlyDefinition definition={definition} />);
  expect(screen.getByLabelText("Published definition JSON")).toHaveTextContent(
    "Exact source is unavailable",
  );
});
