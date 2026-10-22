import { it, expect } from "vitest";
import { emptyGraph } from "./model";
import {
  addCanvasNode,
  canConnect,
  canvasLabels,
  connectCanvas,
  convertV1,
  displayOrder,
  initialPositions,
  isCanvasGraph,
  newV2Graph,
  removeCanvasNode,
} from "./canvasModel";

it("explicit conversion preserves original node IDs/configuration and never changes the v1 source", () => {
  const old = emptyGraph();
  old.nodes.splice(1, 0, {
    id: "review",
    type: "approval",
    typeVersion: 1,
    config: { prompt: "Preserve review", futureField: { retained: true } },
  });
  const bytes = JSON.stringify(old);
  const next = convertV1(old);
  expect(next.schemaVersion).toBe(2);
  expect(next.nodes[1]?.config).toEqual(old.nodes[1]?.config);
  expect(next.nodes[1]?.id).toBe("review");
  expect(next.nodes.every((n) => n.inputs !== undefined)).toBe(true);
  next.nodes[1]!.config.prompt = "Changed new draft";
  expect(JSON.stringify(old)).toBe(bytes);
});
it("keeps labels friendly and distinct for empty/duplicate configurations and hidden technical IDs", () => {
  let graph = newV2Graph();
  graph = addCanvasNode(graph, "syntheticAction", "generated_a");
  graph = addCanvasNode(graph, "syntheticAction", "generated_b");
  graph.nodes[3]!.config.message = "  Count high tickets  ";
  const labels = canvasLabels(graph);
  expect(labels.get("start")).toBe("Manual trigger");
  expect(labels.get("generated_a")).toBe("Synthetic action 1");
  expect(labels.get("generated_b")).toBe(
    "Synthetic action 2 — Count high tickets",
  );
  expect([...labels.values()].join(" ")).not.toContain("generated_");
});
it("branch editing preserves the other named output and never stores presentation coordinates", () => {
  let graph = addCanvasNode(newV2Graph(), "condition", "choose");
  graph = addCanvasNode(graph, "transform", "left");
  graph = addCanvasNode(graph, "transform", "right");
  graph = connectCanvas(graph, "start", "choose");
  graph = connectCanvas(graph, "choose", "left", "true");
  graph = connectCanvas(graph, "choose", "right", "false");
  graph = connectCanvas(graph, "choose", "finish", "true");
  expect(graph.edges).toContainEqual({
    source: "choose",
    target: "right",
    branch: "false",
  });
  expect(graph.edges).not.toContainEqual({
    source: "choose",
    target: "left",
    branch: "true",
  });
  expect(initialPositions(graph).size).toBe(graph.nodes.length);
  expect(JSON.stringify(graph)).not.toMatch(/position|viewport|selected/);
});
it("rejects cycles self-links trigger targets ambiguous branches and end exits without mutating the graph", () => {
  let graph = addCanvasNode(newV2Graph(), "transform", "middle");
  graph = connectCanvas(graph, "start", "middle");
  graph = connectCanvas(graph, "middle", "finish");
  const before = JSON.stringify(graph);
  for (const [source, target] of [
    ["middle", "middle"],
    ["middle", "start"],
    ["finish", "middle"],
    ["missing", "middle"],
  ])
    expect(canConnect(graph, source!, target!)).toBe(false);
  expect(connectCanvas(graph, "middle", "middle")).toBe(graph);
  expect(JSON.stringify(graph)).toBe(before);
  expect(canConnect(graph, "middle", "finish", "true")).toBe(false);
  graph = addCanvasNode(graph, "condition", "choose");
  expect(canConnect(graph, "choose", "middle")).toBe(false);
});
it("preserves unknown saved node/configuration fields and provides stable invalid-graph fallback", () => {
  const graph = newV2Graph();
  graph.nodes.push({
    id: "future",
    type: "futureVersion",
    typeVersion: 9,
    config: { doNotDrop: true },
    inputs: {},
  });
  graph.edges.push({ source: "future", target: "future" });
  expect(isCanvasGraph(graph)).toBe(true);
  expect(displayOrder(graph)).toHaveLength(3);
  expect(canvasLabels(graph).get("future")).toContain("Unsupported node");
  expect(
    removeCanvasNode(graph, "finish").nodes.find((n) => n.id === "future")
      ?.config,
  ).toEqual({ doNotDrop: true });
});
it("keeps graph limits and schema-v1 type/entry semantics", () => {
  const old = emptyGraph();
  expect(addCanvasNode(old, "condition", "new_condition")).toBe(old);
  let graph = newV2Graph();
  for (let i = 0; i < 48; i++)
    graph = addCanvasNode(graph, "transform", `step_${i}`);
  expect(addCanvasNode(graph, "delay", "too_many")).toBe(graph);
  expect(graph.nodes).toHaveLength(50);
  expect(newV2Graph("webhookTrigger").nodes[0]?.type).toBe("webhookTrigger");
  expect(isCanvasGraph({ ...graph, schemaVersion: 99 })).toBe(false);
});
