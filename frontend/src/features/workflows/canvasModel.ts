import { isGraph, nodeLabels, type Graph, type NodeDefinition } from "./model";

export interface CanvasNode extends NodeDefinition {
  inputs?: Record<string, unknown>;
}
export interface CanvasEdge {
  source: string;
  target: string;
  branch?: "true" | "false";
}
export interface CanvasGraph {
  schemaVersion: number;
  nodes: CanvasNode[];
  edges: CanvasEdge[];
}
export const canvasTypes: Record<string, string> = {
  manualTrigger: "Manual trigger",
  webhookTrigger: "Webhook trigger",
  syntheticAction: "Synthetic action",
  capabilityAction: "Internal capability",
  configuredHttp: "Configured HTTP",
  condition: "Condition",
  approval: "Approval gate",
  delay: "Scheduled delay",
  transform: "Transform",
  notification: "Internal notification",
  end: "End",
};
const object = (value: unknown): value is Record<string, unknown> =>
  Boolean(value) && typeof value === "object" && !Array.isArray(value);
export function isCanvasGraph(value: unknown): value is CanvasGraph {
  if (
    !object(value) ||
    ![1, 2].includes(value.schemaVersion as number) ||
    !Array.isArray(value.nodes) ||
    !Array.isArray(value.edges)
  )
    return false;
  return (
    value.nodes.length <= 50 &&
    value.edges.length <= 100 &&
    value.nodes.every(
      (n) =>
        object(n) &&
        typeof n.id === "string" &&
        typeof n.type === "string" &&
        typeof n.typeVersion === "number" &&
        object(n.config) &&
        (n.inputs === undefined || object(n.inputs)),
    ) &&
    value.edges.every(
      (e) =>
        object(e) &&
        typeof e.source === "string" &&
        typeof e.target === "string" &&
        (e.branch === undefined || e.branch === "true" || e.branch === "false"),
    )
  );
}
/** Topological display order only; invalid saved graphs retain all nodes in a stable fallback. */
export function displayOrder(graph: CanvasGraph): CanvasNode[] {
  const nodes = new Map(graph.nodes.map((n) => [n.id, n]));
  const incoming = new Map(graph.nodes.map((n) => [n.id, 0]));
  for (const edge of graph.edges)
    if (nodes.has(edge.source) && nodes.has(edge.target))
      incoming.set(edge.target, (incoming.get(edge.target) ?? 0) + 1);
  const ready = [...incoming]
    .filter(([, degree]) => degree === 0)
    .map(([id]) => id)
    .sort();
  const result: CanvasNode[] = [];
  const visited = new Set<string>();
  while (ready.length) {
    const id = ready.shift()!;
    if (visited.has(id)) continue;
    visited.add(id);
    const node = nodes.get(id);
    if (node) result.push(node);
    for (const edge of graph.edges.filter((e) => e.source === id)) {
      const degree = (incoming.get(edge.target) ?? 0) - 1;
      incoming.set(edge.target, degree);
      if (degree === 0) {
        ready.push(edge.target);
        ready.sort();
      }
    }
  }
  return [
    ...result,
    ...graph.nodes
      .filter((n) => !visited.has(n.id))
      .sort((a, b) => a.id.localeCompare(b.id)),
  ];
}
export function canvasLabels(graph: CanvasGraph): Map<string, string> {
  if (graph.schemaVersion === 1 && isGraph(graph))
    return nodeLabels(graph as Graph);
  const counts = new Map<string, number>();
  const totals = new Map<string, number>();
  graph.nodes.forEach((n) => totals.set(n.type, (totals.get(n.type) ?? 0) + 1));
  return new Map(
    displayOrder(graph).map((node) => {
      const number = (counts.get(node.type) ?? 0) + 1;
      counts.set(node.type, number);
      const numbered =
        !["manualTrigger", "webhookTrigger", "end"].includes(node.type) ||
        (totals.get(node.type) ?? 0) > 1;
      const value =
        node.type === "approval"
          ? node.config.prompt
          : (node.config.message ?? node.config.operation);
      const clean =
        typeof value === "string" ? value.trim().replace(/\s+/g, " ") : "";
      const preview = clean.length > 60 ? clean.slice(0, 57) + "…" : clean;
      return [
        node.id,
        (Object.hasOwn(canvasTypes, node.type)
          ? canvasTypes[node.type]
          : "Unsupported node") +
          (numbered ? ` ${number}` : "") +
          (preview ? ` — ${preview}` : ""),
      ];
    }),
  );
}
export function newV2Graph(
  trigger: "manualTrigger" | "webhookTrigger" = "manualTrigger",
): CanvasGraph {
  return {
    schemaVersion: 2,
    nodes: [
      {
        id: "start",
        type: trigger,
        typeVersion: 1,
        config: { inputFields: {} },
        inputs: {},
      },
      { id: "finish", type: "end", typeVersion: 1, config: {}, inputs: {} },
    ],
    edges: [{ source: "start", target: "finish" }],
  };
}
/** Called only after explicit draft conversion confirmation; historical graphs are never mutated. */
export function convertV1(graph: Graph): CanvasGraph {
  if (!isGraph(graph))
    throw new Error("Select a supported v1 definition first.");
  return {
    ...structuredClone(graph),
    schemaVersion: 2,
    nodes: graph.nodes.map((n) => ({
      ...structuredClone(n),
      config:
        n.type === "manualTrigger"
          ? { ...structuredClone(n.config), inputFields: {} }
          : structuredClone(n.config),
      inputs: {},
    })),
  };
}
export function canvasDefaults(type: string): Record<string, unknown> {
  switch (type) {
    case "manualTrigger":
    case "webhookTrigger":
      return { inputFields: {} };
    case "syntheticAction":
      return { operation: "record", message: "" };
    case "approval":
      return { prompt: "" };
    case "delay":
      return { seconds: 0 };
    case "condition":
      return { operator: "GT", compare: 0 };
    case "capabilityAction":
      return { integrationId: "", revision: 0, operation: "support.list" };
    case "configuredHttp":
      return { integrationId: "", revision: 0, operation: "" };
    case "notification":
      return { integrationId: "", revision: 0 };
    default:
      return {};
  }
}
export function addCanvasNode(
  graph: CanvasGraph,
  type: string,
  id: string,
): CanvasGraph {
  if (
    graph.nodes.length >= 50 ||
    graph.nodes.some((n) => n.id === id) ||
    !Object.hasOwn(canvasTypes, type) ||
    !id.match(/^[A-Za-z][A-Za-z0-9_-]{0,63}$/)
  )
    return graph;
  if (
    graph.schemaVersion === 1 &&
    !["manualTrigger", "syntheticAction", "approval", "end"].includes(type)
  )
    return graph;
  const config =
    graph.schemaVersion === 1 && type === "manualTrigger"
      ? {}
      : canvasDefaults(type);
  return {
    ...graph,
    nodes: [
      ...graph.nodes,
      {
        id,
        type,
        typeVersion: 1,
        config,
        ...(graph.schemaVersion === 2
          ? { inputs: type === "condition" ? { value: { literal: 0 } } : {} }
          : {}),
      },
    ],
  };
}
export function removeCanvasNode(graph: CanvasGraph, id: string): CanvasGraph {
  return {
    ...graph,
    nodes: graph.nodes.filter((n) => n.id !== id),
    edges: graph.edges.filter((e) => e.source !== id && e.target !== id),
  };
}
export function canConnect(
  graph: CanvasGraph,
  source: string,
  target: string,
  branch?: "true" | "false",
): boolean {
  const from = graph.nodes.find((n) => n.id === source),
    to = graph.nodes.find((n) => n.id === target);
  if (
    !from ||
    !to ||
    source === target ||
    from.type === "end" ||
    ["manualTrigger", "webhookTrigger"].includes(to.type)
  )
    return false;
  if (
    from.type === "condition"
      ? graph.schemaVersion !== 2 || !branch
      : branch !== undefined
  )
    return false;
  const replace = (e: CanvasEdge) =>
    e.source === source && (from.type !== "condition" || e.branch === branch);
  const edges = graph.edges.filter((e) => !replace(e));
  if (
    edges.length >= 100 ||
    edges.some((e) => e.source === source && e.target === target)
  )
    return false;
  if (graph.schemaVersion === 1 && edges.some((e) => e.target === target))
    return false;
  const pending = [target],
    visited = new Set<string>();
  while (pending.length) {
    const id = pending.pop()!;
    if (id === source) return false;
    if (visited.has(id)) continue;
    visited.add(id);
    edges.filter((e) => e.source === id).forEach((e) => pending.push(e.target));
  }
  return true;
}
export function connectCanvas(
  graph: CanvasGraph,
  source: string,
  target: string,
  branch?: "true" | "false",
): CanvasGraph {
  if (!canConnect(graph, source, target, branch)) return graph;
  const node = graph.nodes.find((n) => n.id === source)!;
  return {
    ...graph,
    edges: [
      ...graph.edges.filter(
        (e) =>
          e.source !== source ||
          (node.type === "condition" && e.branch !== branch),
      ),
      { source, target, ...(branch ? { branch } : {}) },
    ],
  };
}
export function initialPositions(
  graph: CanvasGraph,
  mode: "horizontal" | "compact" = "horizontal",
): Map<string, { x: number; y: number }> {
  if (mode === "compact")
    return new Map(
      displayOrder(graph).map((node, index) => [
        node.id,
        { x: (index % 2) * 280, y: Math.floor(index / 2) * 160 },
      ]),
    );
  const positions = new Map<string, { x: number; y: number }>(),
    depth = new Map<string, number>(),
    columns = new Map<number, number>();
  for (const node of displayOrder(graph)) {
    const parents = graph.edges
      .filter((e) => e.target === node.id)
      .map((e) => depth.get(e.source) ?? -1);
    const column = parents.length
      ? Math.max(0, ...parents.map((d) => d + 1))
      : 0;
    depth.set(node.id, column);
    const row = columns.get(column) ?? 0;
    columns.set(column, row + 1);
    positions.set(node.id, { x: column * 280, y: row * 160 });
  }
  return positions;
}
