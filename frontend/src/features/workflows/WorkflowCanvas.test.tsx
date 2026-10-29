import { useState } from "react";
import {
  cleanup,
  fireEvent,
  render,
  screen,
  within,
  waitFor,
} from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { WorkflowCanvas } from "./WorkflowCanvas";
import { GraphEditor, ReadOnlyDefinition } from "./GraphEditor";
import { newV2Graph, type CanvasGraph } from "./canvasModel";
import { emptyGraph } from "./model";

beforeEach(() =>
  vi.stubGlobal(
    "ResizeObserver",
    class {
      observe() {}
      unobserve() {}
      disconnect() {}
    },
  ),
);
afterEach(() => {
  // React Flow's passive-effect cleanup must finish while its browser fixture still exists.
  cleanup();
  vi.unstubAllGlobals();
});
function graph(): CanvasGraph {
  return {
    ...newV2Graph(),
    nodes: [
      {
        id: "start",
        type: "manualTrigger",
        typeVersion: 1,
        config: { inputFields: {} },
        inputs: {},
      },
      {
        id: "stable_action_identifier",
        type: "syntheticAction",
        typeVersion: 1,
        config: {
          operation: "record",
          message: "First",
          futureField: "preserve",
        },
        inputs: {},
      },
      {
        id: "other_action",
        type: "syntheticAction",
        typeVersion: 1,
        config: { operation: "record", message: "" },
        inputs: {},
      },
      { id: "finish", type: "end", typeVersion: 1, config: {}, inputs: {} },
    ],
    edges: [
      { source: "start", target: "stable_action_identifier" },
      { source: "stable_action_identifier", target: "other_action" },
      { source: "other_action", target: "finish" },
    ],
  };
}
describe("workflow canvas", () => {
  it("changes visual layout without changing the workflow definition", () => {
    const change = vi.fn();
    render(<WorkflowCanvas graph={graph()} change={change} />);
    fireEvent.change(screen.getByRole("combobox", { name: "Canvas layout" }), {
      target: { value: "horizontal" },
    });
    expect(change).not.toHaveBeenCalled();
    expect(screen.getByRole("combobox", { name: "Inspect node" })).toHaveValue(
      "",
    );
  });
  it("provides a categorized palette, graph and inspector while keeping added-node state out of saved coordinates", () => {
    const change = vi.fn();
    render(<WorkflowCanvas graph={graph()} change={change} />);
    const palette = screen.getByRole("complementary", { name: "Node palette" });
    expect(
      within(palette).getByRole("region", { name: "Actions" }),
    ).toBeInTheDocument();
    expect(
      screen.getByRole("complementary", { name: "Node inspector" }),
    ).toHaveTextContent("Choose a node");
    fireEvent.click(
      within(palette).getByRole("button", { name: "Add Approval gate node" }),
    );
    const next = change.mock.calls[0]![0] as CanvasGraph;
    expect(next.nodes).toHaveLength(5);
    expect(next.nodes.at(-1)?.type).toBe("approval");
    expect(next.edges).toEqual(graph().edges);
    expect(JSON.stringify(next)).not.toMatch(/position|viewport|selected/);
  });
  it("keeps advanced JSON secondary and associates rejected values with a safe field error", () => {
    render(<WorkflowCanvas graph={graph()} change={vi.fn()} />);
    fireEvent.change(screen.getByRole("combobox", { name: "Inspect node" }), {
      target: { value: "stable_action_identifier" },
    });
    const details = screen.getByText("Advanced JSON").closest("details")!;
    expect(details.open).toBe(false);
    fireEvent.click(within(details).getByText("Advanced JSON"));
    const input = screen.getByRole("textbox", { name: "Input mapping JSON" });
    fireEvent.change(input, { target: { value: "invalid-private-canary" } });
    fireEvent.click(
      screen.getByRole("button", { name: "Apply node properties" }),
    );
    expect(input).toHaveAttribute("aria-invalid", "true");
    expect(input).toHaveAttribute(
      "aria-describedby",
      screen.getByRole("alert").id,
    );
    expect(screen.getByRole("alert")).not.toHaveTextContent(
      "invalid-private-canary",
    );
  });
  it("navigates a friendly saved-validation issue to the selected node and focuses its inspector", async () => {
    render(
      <WorkflowCanvas
        graph={graph()}
        change={vi.fn()}
        errors={[
          {
            nodeId: "stable_action_identifier",
            code: "REQUIRED",
            path: "nodes[1].config.message",
            message: "Enter a record message.",
          },
        ]}
      />,
    );
    fireEvent.click(
      screen.getByText("Saved validation needs attention · 1 issues"),
    );
    fireEvent.click(
      screen.getByRole("button", { name: "Synthetic action 1 — First" }),
    );
    expect(screen.getByRole("combobox", { name: "Inspect node" })).toHaveValue(
      "stable_action_identifier",
    );
    await waitFor(() =>
      expect(
        screen.getByRole("heading", { name: "Synthetic action 1 — First" }),
      ).toHaveFocus(),
    );
  });
  it("keeps an unsafe integer edit unapplied so JSON parsing cannot silently change its value", () => {
    const change = vi.fn(),
      pending = vi.fn();
    render(
      <WorkflowCanvas
        graph={graph()}
        change={change}
        pendingChange={pending}
      />,
    );
    fireEvent.change(screen.getByRole("combobox", { name: "Inspect node" }), {
      target: { value: "stable_action_identifier" },
    });
    fireEvent.click(screen.getByText("Advanced JSON"));
    fireEvent.change(
      screen.getByRole("textbox", { name: "Input mapping JSON" }),
      { target: { value: '{"value":{"literal":9223372036854775807}}' } },
    );
    fireEvent.click(
      screen.getByRole("button", { name: "Apply node properties" }),
    );
    expect(screen.getByRole("alert")).toHaveTextContent(
      "this edit was not applied",
    );
    expect(change).not.toHaveBeenCalled();
    expect(pending).toHaveBeenLastCalledWith(true);
  });
  it("renders React Flow and friendly duplicate labels with collapsed stable identity", () => {
    const { container } = render(<WorkflowCanvas graph={graph()} />);
    expect(container.querySelector(".react-flow")).toBeInTheDocument();
    const selector = screen.getByRole("combobox", { name: "Inspect node" });
    expect(
      within(selector).getByRole("option", {
        name: "Synthetic action 1 — First",
      }),
    ).toHaveValue("stable_action_identifier");
    expect(
      within(selector).getByRole("option", { name: "Synthetic action 2" }),
    ).toHaveValue("other_action");
    fireEvent.change(selector, {
      target: { value: "stable_action_identifier" },
    });
    const details = screen
      .getByText("Technical node details")
      .closest("details")!;
    expect(details.open).toBe(false);
    expect(
      within(details).getByText("stable_action_identifier"),
    ).toBeInTheDocument();
    fireEvent.click(within(details).getByText("Technical node details"));
    expect(details.open).toBe(true);
    expect(
      screen.queryByRole("button", { name: "Add canvas node" }),
    ).not.toBeInTheDocument();
  });
  it("applies explicit properties without dropping unknown fields or persisting coordinates", () => {
    const change = vi.fn(),
      pending = vi.fn();
    render(
      <WorkflowCanvas
        graph={graph()}
        change={change}
        pendingChange={pending}
      />,
    );
    fireEvent.change(screen.getByRole("combobox", { name: "Inspect node" }), {
      target: { value: "stable_action_identifier" },
    });
    fireEvent.click(screen.getByText("Advanced JSON"));
    fireEvent.change(
      screen.getByRole("textbox", { name: "Node configuration JSON" }),
      {
        target: {
          value:
            '{"operation":"record","message":"Changed","futureField":"preserve"}',
        },
      },
    );
    expect(pending).toHaveBeenLastCalledWith(true);
    expect(change).not.toHaveBeenCalled();
    fireEvent.click(
      screen.getByRole("button", { name: "Apply node properties" }),
    );
    const next = change.mock.calls[0]![0] as CanvasGraph;
    expect(next.nodes[1]!.config).toEqual({
      operation: "record",
      message: "Changed",
      futureField: "preserve",
    });
    expect(next.nodes[1]!.id).toBe("stable_action_identifier");
    expect(next.edges).toEqual(graph().edges);
    expect(JSON.stringify(next)).not.toContain('"position"');
    expect(pending).toHaveBeenLastCalledWith(false);
  });
  it("retains invalid local text until explicit discard and does not echo parser details", () => {
    const change = vi.fn(),
      pending = vi.fn();
    render(
      <WorkflowCanvas
        graph={graph()}
        change={change}
        pendingChange={pending}
      />,
    );
    fireEvent.change(screen.getByRole("combobox", { name: "Inspect node" }), {
      target: { value: "stable_action_identifier" },
    });
    fireEvent.click(screen.getByText("Advanced JSON"));
    fireEvent.change(
      screen.getByRole("textbox", { name: "Input mapping JSON" }),
      { target: { value: "invalid-private-canary" } },
    );
    fireEvent.click(
      screen.getByRole("button", { name: "Apply node properties" }),
    );
    expect(screen.getByRole("alert")).not.toHaveTextContent(
      "invalid-private-canary",
    );
    expect(change).not.toHaveBeenCalled();
    expect(
      screen.getByRole("combobox", { name: "Inspect node" }),
    ).toBeDisabled();
    fireEvent.click(
      screen.getByRole("button", { name: "Discard property edits" }),
    );
    expect(pending).toHaveBeenLastCalledWith(false);
    expect(
      screen.getByRole("combobox", { name: "Inspect node" }),
    ).toBeEnabled();
    expect(
      screen.getByRole("textbox", { name: "Input mapping JSON" }),
    ).toHaveValue("{}");
  });
  it("edits named condition branches using keyboard-accessible selectors", () => {
    function Editor() {
      const initial = graph();
      initial.nodes[1] = {
        ...initial.nodes[1]!,
        type: "condition",
        config: { operator: "GT", compare: 0 },
        inputs: { value: { literal: 1 } },
      };
      initial.edges = [
        { source: "start", target: "stable_action_identifier" },
        {
          source: "stable_action_identifier",
          target: "other_action",
          branch: "true",
        },
        {
          source: "stable_action_identifier",
          target: "finish",
          branch: "false",
        },
        { source: "other_action", target: "finish" },
      ];
      const [value, setValue] = useState(initial);
      return (
        <>
          <WorkflowCanvas graph={value} change={setValue} />
          <output aria-label="Draft JSON">{JSON.stringify(value)}</output>
        </>
      );
    }
    render(<Editor />);
    fireEvent.change(screen.getByRole("combobox", { name: "Inspect node" }), {
      target: { value: "stable_action_identifier" },
    });
    expect(
      screen.getByRole("combobox", { name: "True branch target" }),
    ).toHaveValue("other_action");
    expect(
      screen.getByRole("combobox", { name: "False branch target" }),
    ).toHaveValue("finish");
    fireEvent.change(
      screen.getByRole("combobox", { name: "True branch target" }),
      { target: { value: "" } },
    );
    const result = JSON.parse(
      screen.getByLabelText("Draft JSON").textContent!,
    ) as CanvasGraph;
    expect(result.edges.some((e) => e.branch === "true")).toBe(false);
    expect(
      result.edges.some((e) => e.branch === "false" && e.target === "finish"),
    ).toBe(true);
  });
  it("requires explicit v1 draft conversion and leaves published inspection read-only", async () => {
    const value = emptyGraph(),
      original = structuredClone(value),
      change = vi.fn();
    const view = render(
      <GraphEditor
        definition={value}
        change={change}
        disabled={false}
        errors={[]}
      />,
    );
    expect(
      screen.queryByLabelText("Visual workflow editor"),
    ).not.toBeInTheDocument();
    fireEvent.click(
      screen.getByRole("button", { name: "Convert draft to schema v2" }),
    );
    expect(change).not.toHaveBeenCalled();
    fireEvent.click(
      screen.getByRole("button", { name: "Confirm schema v2 conversion" }),
    );
    expect(change.mock.calls[0]![0].schemaVersion).toBe(2);
    expect(value).toEqual(original);
    view.rerender(<ReadOnlyDefinition definition={graph()} />);
    fireEvent.click(screen.getByText("Visual published graph"));
    expect(
      await screen.findByLabelText("Published workflow canvas"),
    ).toBeInTheDocument();
    expect(
      screen.queryByRole("button", { name: "Apply node properties" }),
    ).not.toBeInTheDocument();
  });
  it("links server validation to friendly node inspection and respects read-only permission", () => {
    render(
      <WorkflowCanvas
        graph={graph()}
        disabled
        change={vi.fn()}
        errors={[
          {
            nodeId: "stable_action_identifier",
            path: "$.nodes[1].config.message",
            code: "INVALID_CONFIG",
            message: "A message is required.",
          },
        ]}
      />,
    );
    expect(
      screen.getByRole("button", { name: "Add canvas node" }),
    ).toBeDisabled();
    fireEvent.change(screen.getByRole("combobox", { name: "Inspect node" }), {
      target: { value: "stable_action_identifier" },
    });
    expect(screen.getByRole("alert")).toHaveTextContent(
      "A message is required.",
    );
    expect(
      screen.queryByRole("textbox", { name: "Node configuration JSON" }),
    ).not.toBeInTheDocument();
  });
});
