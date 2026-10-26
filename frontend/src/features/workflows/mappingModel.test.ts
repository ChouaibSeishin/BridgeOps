import { expect, it } from "vitest";
import { mappingSources, outputFields } from "./mappingModel";
import type { CanvasGraph } from "./canvasModel";

function graph(): CanvasGraph {
  return {
    schemaVersion: 2,
    nodes: [
      { id: "end", type: "end", typeVersion: 1, config: {}, inputs: {} },
      {
        id: "left",
        type: "transform",
        typeVersion: 1,
        config: {},
        inputs: { left: { literal: "yes" } },
      },
      {
        id: "start",
        type: "manualTrigger",
        typeVersion: 1,
        config: { inputFields: { limit: "INTEGER" } },
        inputs: {},
      },
      {
        id: "merge",
        type: "transform",
        typeVersion: 1,
        config: {},
        inputs: { result: { literal: "done" } },
      },
      {
        id: "right",
        type: "transform",
        typeVersion: 1,
        config: {},
        inputs: { right: { literal: "no" } },
      },
      {
        id: "choose",
        type: "condition",
        typeVersion: 1,
        config: { operator: "GT", compare: 0 },
        inputs: { value: { from: "start", path: "limit" } },
      },
    ],
    edges: [
      { source: "start", target: "choose" },
      { source: "choose", target: "left", branch: "true" },
      { source: "choose", target: "right", branch: "false" },
      { source: "left", target: "merge" },
      { source: "right", target: "merge" },
      { source: "merge", target: "end" },
    ],
  };
}
it("suggests dominating outputs by edges and excludes branch-local values at a merge regardless of array order", () => {
  expect(
    mappingSources(graph(), "merge")
      .map((n) => n.id)
      .sort(),
  ).toEqual(["choose", "start"]);
  expect(
    mappingSources(graph(), "left")
      .map((n) => n.id)
      .sort(),
  ).toEqual(["choose", "start"]);
  expect(
    mappingSources(graph(), "end")
      .map((n) => n.id)
      .sort(),
  ).toEqual(["choose", "merge", "start"]);
  const reordered = graph();
  reordered.nodes.reverse();
  reordered.edges.reverse();
  expect(
    mappingSources(reordered, "merge")
      .map((n) => n.id)
      .sort(),
  ).toEqual(["choose", "start"]);
});
it("fails closed for cycles, disconnected graphs, duplicate nodes and foreign edge references", () => {
  for (const mutate of [
    (g: CanvasGraph) => g.edges.push({ source: "merge", target: "choose" }),
    (g: CanvasGraph) => g.edges.splice(0, 1),
    (g: CanvasGraph) => g.nodes.push(g.nodes[0]!),
    (g: CanvasGraph) => g.edges.push({ source: "foreign", target: "end" }),
  ]) {
    const invalid = graph();
    mutate(invalid);
    expect(mappingSources(invalid, "merge")).toEqual([]);
  }
});
it("exposes whole objects only for declared trigger and transform outputs and never invents REST response fields", () => {
  const g = graph();
  expect(outputFields(g.nodes.find((n) => n.id === "start")!)).toEqual([
    "$",
    "limit",
  ]);
  expect(outputFields(g.nodes.find((n) => n.id === "merge")!)).toEqual([
    "$",
    "result",
  ]);
  expect(
    outputFields({
      id: "http",
      type: "configuredHttp",
      typeVersion: 1,
      config: {},
    }),
  ).toEqual([]);
  expect(
    outputFields({
      id: "read",
      type: "capabilityAction",
      typeVersion: 1,
      config: { operation: "support.list" },
    }),
  ).toEqual(["tickets", "count"]);
  expect(
    outputFields({
      id: "bad",
      type: "capabilityAction",
      typeVersion: 1,
      config: { operation: "toString" },
    }),
  ).toEqual([]);
});
