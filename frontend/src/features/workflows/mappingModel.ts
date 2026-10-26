import type { CanvasGraph, CanvasNode } from "./canvasModel";

export const fieldName = /^[A-Za-z][A-Za-z0-9_]{0,63}$/;
export const scalarTypes = ["STRING", "INTEGER", "BOOLEAN"] as const;
export const internalOutputs: Record<string, string[]> = {
  "support.list": ["tickets", "count"],
  "support.get": ["ticket"],
  "support.assignBatch": ["items", "applied", "count"],
  "support.addNote": ["noteId", "ticketId", "revision"],
  "teams.list": ["teams", "count"],
  "teams.get": ["team"],
  "teams.resolve": ["assignments", "unmatched", "count"],
};
export function outputFields(node: CanvasNode): string[] {
  switch (node.type) {
    case "manualTrigger":
    case "webhookTrigger":
      return [
        "$",
        ...Object.keys(object(node.config.inputFields) ?? {}).filter((k) =>
          fieldName.test(k),
        ),
      ];
    case "transform":
      return [
        "$",
        ...Object.keys(node.inputs ?? {}).filter((k) => fieldName.test(k)),
      ];
    case "syntheticAction":
      return ["message", "recorded"];
    case "capabilityAction":
      return Object.hasOwn(internalOutputs, String(node.config.operation))
        ? internalOutputs[String(node.config.operation)]!
        : [];
    case "condition":
      return ["matched"];
    case "delay":
      return ["resumedAt"];
    case "notification":
      return ["notificationId", "status"];
    default:
      return [];
  }
}
export function object(value: unknown): Record<string, unknown> | undefined {
  return value !== null && typeof value === "object" && !Array.isArray(value)
    ? (value as Record<string, unknown>)
    : undefined;
}
/** Suggestions require a connected acyclic graph and dominance, not just presentation order. */
export function mappingSources(
  graph: CanvasGraph,
  target: string,
): CanvasNode[] {
  const nodes = new Map(graph.nodes.map((n) => [n.id, n]));
  const triggers = graph.nodes.filter((n) => n.type.endsWith("Trigger"));
  if (
    nodes.size !== graph.nodes.length ||
    triggers.length !== 1 ||
    !nodes.has(target)
  )
    return [];
  const parents = new Map(graph.nodes.map((n) => [n.id, [] as string[]]));
  const children = new Map(graph.nodes.map((n) => [n.id, [] as string[]]));
  for (const edge of graph.edges) {
    if (!nodes.has(edge.source) || !nodes.has(edge.target)) return [];
    parents.get(edge.target)!.push(edge.source);
    children.get(edge.source)!.push(edge.target);
  }
  const remaining = new Map(
    [...parents].map(([id, values]) => [id, values.length]),
  );
  const ready = [...remaining].filter(([, n]) => n === 0).map(([id]) => id);
  if (ready.length !== 1 || ready[0] !== triggers[0]!.id) return [];
  const dominators = new Map<string, Set<string>>();
  while (ready.length) {
    const id = ready.shift()!,
      previous = parents.get(id)!;
    let common = previous.length
      ? new Set(dominators.get(previous[0]!) ?? [])
      : new Set<string>();
    for (const parent of previous.slice(1))
      common = new Set(
        [...common].filter((p) => dominators.get(parent)?.has(p)),
      );
    common.add(id);
    dominators.set(id, common);
    for (const child of children.get(id)!) {
      const count = remaining.get(child)! - 1;
      remaining.set(child, count);
      if (count === 0) ready.push(child);
    }
  }
  if (dominators.size !== nodes.size) return [];
  const guaranteed = dominators.get(target)!;
  return graph.nodes.filter(
    (n) =>
      n.id !== target &&
      guaranteed.has(n.id) &&
      (outputFields(n).length > 0 || n.type === "configuredHttp"),
  );
}
export function literalDefault(type: string): unknown {
  return type === "INTEGER"
    ? 0
    : type === "BOOLEAN"
      ? false
      : type === "ARRAY"
        ? []
        : type === "OBJECT"
          ? {}
          : "";
}
