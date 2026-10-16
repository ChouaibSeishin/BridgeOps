export interface NodeDefinition {
  id: string;
  type: string;
  typeVersion: number;
  config: Record<string, unknown>;
}
export interface Graph {
  schemaVersion: number;
  nodes: NodeDefinition[];
  edges: { source: string; target: string }[];
}
interface Field {
  key: string;
  label: string;
  options?: string[];
}
interface NodeSchema {
  label: string;
  fields: Field[];
  defaults: Record<string, string>;
}
/** Definition-only registry mirrors backend schema v1. No dispatcher or external capability. */
export const registry: Record<string, NodeSchema> = {
  manualTrigger: { label: "Manual trigger", fields: [], defaults: {} },
  syntheticAction: {
    label: "Synthetic action",
    fields: [
      { key: "operation", label: "Operation", options: ["record"] },
      { key: "message", label: "Message" },
    ],
    defaults: { operation: "record", message: "" },
  },
  approval: {
    label: "Approval gate",
    fields: [{ key: "prompt", label: "Review prompt" }],
    defaults: { prompt: "" },
  },
  end: { label: "End", fields: [], defaults: {} },
};
export function nodeSchema(type: string): NodeSchema | undefined {
  return Object.hasOwn(registry, type) ? registry[type] : undefined;
}
export function emptyGraph(): Graph {
  return {
    schemaVersion: 1,
    nodes: [
      { id: "start", type: "manualTrigger", typeVersion: 1, config: {} },
      { id: "finish", type: "end", typeVersion: 1, config: {} },
    ],
    edges: [{ source: "start", target: "finish" }],
  };
}
export function isGraph(value: unknown): value is Graph {
  if (!value || typeof value !== "object") return false;
  const graph = value as Graph;
  return (
    graph.schemaVersion === 1 &&
    Array.isArray(graph.nodes) &&
    Array.isArray(graph.edges) &&
    graph.nodes.every(
      (node) =>
        node &&
        typeof node.id === "string" &&
        typeof node.type === "string" &&
        typeof node.typeVersion === "number" &&
        node.config &&
        typeof node.config === "object" &&
        !Array.isArray(node.config),
    ) &&
    graph.edges.every(
      (edge) =>
        edge &&
        typeof edge.source === "string" &&
        typeof edge.target === "string",
    )
  );
}
export function addNode(graph: Graph, type: string, id: string): Graph {
  const schema = nodeSchema(type);
  if (
    !schema ||
    graph.nodes.length >= 50 ||
    graph.nodes.some((node) => node.id === id)
  )
    return graph;
  const end = graph.nodes.find((node) => node.type === "end");
  const incoming = graph.edges.filter((edge) => edge.target === end?.id);
  const index = end ? graph.nodes.indexOf(end) : graph.nodes.length;
  const nodes = [...graph.nodes];
  nodes.splice(index, 0, {
    id,
    type,
    typeVersion: 1,
    config: { ...schema.defaults },
  });
  if (!end || incoming.length !== 1) return { ...graph, nodes };
  return {
    ...graph,
    nodes,
    edges: [
      ...graph.edges.map((edge) =>
        edge === incoming[0] ? { source: edge.source, target: id } : edge,
      ),
      { source: id, target: end.id },
    ],
  };
}
export function removeNode(graph: Graph, id: string): Graph {
  const incoming = graph.edges.filter((edge) => edge.target === id);
  const outgoing = graph.edges.filter((edge) => edge.source === id);
  const edges = graph.edges.filter(
    (edge) => edge.source !== id && edge.target !== id,
  );
  const before = incoming[0],
    after = outgoing[0];
  if (
    incoming.length === 1 &&
    outgoing.length === 1 &&
    before &&
    after &&
    !edges.some(
      (edge) => edge.source === before.source && edge.target === after.target,
    )
  )
    edges.push({ source: before.source, target: after.target });
  return {
    ...graph,
    nodes: graph.nodes.filter((node) => node.id !== id),
    edges,
  };
}
export interface ValidationError {
  nodeId: string | null;
  path: string;
  code: string;
  message: string;
}
export interface Validation {
  valid: boolean;
  errors: ValidationError[];
}
export interface Page<T> {
  items: T[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
}
export interface WorkflowSummary {
  id: string;
  name: string;
  description: string;
  status: "ACTIVE" | "ARCHIVED";
  revision: number;
  currentPublishedVersionId: string | null;
  updatedAt: string;
}
export interface VersionSummary {
  id: string;
  workflowId: string;
  versionNumber: number;
  sourceRevision: number;
  schemaVersion: number;
  canonicalizationVersion: number;
  name: string;
  description: string;
  definitionHash: string;
  publishedBy: string;
  publishedAt: string;
}
export interface WorkflowDetail {
  workflow: WorkflowSummary;
  definition: unknown;
  currentPublishedVersion: VersionSummary | null;
}
export interface Snapshot {
  version: VersionSummary;
  definition: unknown;
}
