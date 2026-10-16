import { describe, it, expect } from "vitest";
import { emptyGraph, addNode, removeNode, isGraph, nodeSchema } from "./model";

describe("definition editor operations", () => {
  it("inserts stable nodes into the linear path without mutating the saved graph", () => {
    const original = emptyGraph();
    const graph = addNode(
      addNode(
        addNode(original, "syntheticAction", "prepare"),
        "approval",
        "review",
      ),
      "syntheticAction",
      "record",
    );
    expect(original.nodes).toHaveLength(2);
    expect(graph.nodes.map((node) => node.id)).toEqual([
      "start",
      "prepare",
      "review",
      "record",
      "finish",
    ]);
    expect(graph.edges).toEqual([
      { source: "start", target: "prepare" },
      { source: "prepare", target: "review" },
      { source: "review", target: "record" },
      { source: "record", target: "finish" },
    ]);
    expect(graph.nodes.find((node) => node.id === "prepare")?.config).toEqual({
      operation: "record",
      message: "",
    });
  });
  it("removes a linear node, reconnects neighbors and removes dangling references", () => {
    const graph = addNode(emptyGraph(), "approval", "review");
    expect(removeNode(graph, "review")).toEqual(emptyGraph());
    expect(removeNode(graph, "finish").edges).toEqual([
      { source: "start", target: "review" },
    ]);
    expect(graph.nodes).toHaveLength(3);
  });
  it("does not choose arbitrary rewiring for ambiguous draft connections", () => {
    const graph = addNode(emptyGraph(), "approval", "review");
    graph.edges.push({ source: "finish", target: "review" });
    expect(removeNode(graph, "review").edges).toEqual([]);
  });
  it("enforces the UI node limit and rejects unknown or prototype-derived types", () => {
    const graph = emptyGraph();
    expect(addNode(graph, "__proto__", "bad")).toBe(graph);
    expect(nodeSchema("constructor")).toBeUndefined();
    expect(addNode(graph, "approval", "start")).toBe(graph);
    while (graph.nodes.length < 50)
      graph.nodes.push({
        id: `n${graph.nodes.length}`,
        type: "end",
        typeVersion: 1,
        config: {},
      });
    expect(addNode(graph, "end", "too-many")).toBe(graph);
  });
  it("recognizes editable shape without silently replacing unknown saved semantics", () => {
    expect(isGraph(emptyGraph())).toBe(true);
    expect(isGraph(null)).toBe(false);
    expect(isGraph({ schemaVersion: 1, nodes: [null], edges: [] })).toBe(false);
    const graph = emptyGraph();
    graph.nodes[0] = {
      id: "start",
      type: "unsupported",
      typeVersion: 7,
      config: { unknown: true },
    };
    expect(isGraph(graph)).toBe(true);
    expect(nodeSchema(graph.nodes[0].type)).toBeUndefined();
  });
});
