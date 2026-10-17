import { render, screen, within, fireEvent } from "@testing-library/react";
import { it, expect } from "vitest";
import { GraphEditor, ReadOnlyDefinition } from "./GraphEditor";
import { addNode, emptyGraph, nodeLabels } from "./model";

it("distinguishes repeated types and uses the same friendly names for headings and connections", () => {
  const graph = addNode(
    addNode(emptyGraph(), "syntheticAction", "n-long-stable-first"),
    "syntheticAction",
    "n-long-stable-second",
  );
  graph.nodes[1]!.config.message = "tasking";
  graph.nodes[2]!.config.message = "";
  const before = JSON.stringify(graph);
  render(
    <GraphEditor
      definition={graph}
      change={() => {}}
      disabled={false}
      errors={[]}
    />,
  );
  const first = screen.getByRole("group", {
    name: "Synthetic action 1 — tasking",
  });
  expect(
    screen.getByRole("group", { name: "Synthetic action 2" }),
  ).toBeInTheDocument();
  const next = within(
    screen.getByRole("group", { name: "Manual trigger" }),
  ).getByRole("combobox", { name: /Next node/ });
  expect(
    within(next).getByRole("option", { name: "Synthetic action 1 — tasking" }),
  ).toHaveValue("n-long-stable-first");
  expect(
    within(next).getByRole("option", { name: "Synthetic action 2" }),
  ).toHaveValue("n-long-stable-second");
  const technical = within(first).getByText("Technical node details");
  expect(technical.closest("details")).not.toHaveAttribute("open");
  fireEvent.click(technical);
  expect(within(first).getByText("n-long-stable-first")).toBeInTheDocument();
  expect(JSON.stringify(graph)).toBe(before);
});
it("handles missing, empty and incorrectly typed config without object conversion", () => {
  const graph = addNode(
    addNode(emptyGraph(), "approval", "gate-a"),
    "approval",
    "gate-b",
  );
  graph.nodes[1]!.config = {};
  graph.nodes[2]!.config.prompt = { toString: "unsafe" };
  expect([...nodeLabels(graph).values()]).toContain("Approval gate 1");
  expect([...nodeLabels(graph).values()]).toContain("Approval gate 2");
  graph.nodes[2]!.config.prompt = " ";
  expect(nodeLabels(graph).get("gate-b")).toBe("Approval gate 2");
});
it("uses readable published connections while keeping exact IDs in collapsed technical details", () => {
  const graph = addNode(emptyGraph(), "syntheticAction", "n-stable-published");
  graph.nodes[1]!.config.message = "record the plan";
  render(<ReadOnlyDefinition definition={graph} />);
  expect(
    screen.getByText("Manual trigger → Synthetic action 1 — record the plan"),
  ).toBeInTheDocument();
  expect(
    screen.getByText("Synthetic action 1 — record the plan → End"),
  ).toBeInTheDocument();
  expect(screen.getByLabelText("Published definition JSON")).toHaveTextContent(
    "n-stable-published",
  );
  expect(
    screen.getByLabelText("Published definition JSON").closest("details"),
  ).not.toHaveAttribute("open");
});

it("keeps friendly ordinals stable when canonical publication reorders node arrays", () => {
  const graph = addNode(
    addNode(emptyGraph(), "syntheticAction", "z-first"),
    "syntheticAction",
    "a-second",
  );
  const labels = nodeLabels(graph);
  const published = {
    ...graph,
    nodes: [...graph.nodes].sort((a, b) => a.id.localeCompare(b.id)),
  };
  expect(nodeLabels(published)).toEqual(labels);
  expect(labels.get("z-first")).toBe("Synthetic action 1");
  expect(labels.get("a-second")).toBe("Synthetic action 2");
});
