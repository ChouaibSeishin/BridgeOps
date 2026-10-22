import { useMemo, useState } from "react";
import {
  Background,
  Controls,
  Handle,
  MarkerType,
  Position,
  ReactFlow,
  type Node,
  type NodeProps,
  type ReactFlowInstance,
  type XYPosition,
} from "@xyflow/react";
import "@xyflow/react/dist/style.css";
import type { ValidationError } from "./model";
import {
  addCanvasNode,
  canConnect,
  canvasLabels,
  canvasTypes,
  connectCanvas,
  initialPositions,
  removeCanvasNode,
  type CanvasGraph,
} from "./canvasModel";
import "./canvas.css";

type DiagramNode = Node<
  { label: string; kind: string; invalid: boolean },
  "workflow"
>;
function WorkflowNode({ data }: NodeProps<DiagramNode>) {
  return (
    <div className={`canvas-node${data.invalid ? " canvas-node-invalid" : ""}`}>
      {!data.kind.endsWith("Trigger") && (
        <Handle type="target" position={Position.Left} />
      )}
      <span className="canvas-node-kind">
        {Object.hasOwn(canvasTypes, data.kind)
          ? canvasTypes[data.kind]
          : "Unsupported node"}
      </span>
      <strong>{data.label}</strong>
      {data.invalid && <span>Validation needs attention</span>}
      {data.kind === "condition" ? (
        <>
          <Handle
            id="true"
            type="source"
            position={Position.Right}
            style={{ top: "30%" }}
            aria-label="True branch"
          />
          <Handle
            id="false"
            type="source"
            position={Position.Right}
            style={{ top: "75%" }}
            aria-label="False branch"
          />
        </>
      ) : (
        data.kind !== "end" && (
          <Handle type="source" position={Position.Right} />
        )
      )}
    </div>
  );
}
const nodeTypes = { workflow: WorkflowNode };

/** Positions and viewport are presentation state and never enter workflow JSON. */
export function WorkflowCanvas({
  graph,
  change,
  disabled = false,
  errors = [],
  pendingChange,
}: {
  graph: CanvasGraph;
  change?: (graph: CanvasGraph) => void;
  disabled?: boolean;
  errors?: ValidationError[];
  pendingChange?: (pending: boolean) => void;
}) {
  const [selected, setSelected] = useState<string>();
  const [positions, setPositions] = useState<Record<string, XYPosition>>({});
  const [flow, setFlow] = useState<ReactFlowInstance<DiagramNode>>();
  const [palette, setPalette] = useState("syntheticAction");
  const [pending, setPending] = useState(false);
  const editable = Boolean(change) && !disabled;
  const labels = canvasLabels(graph),
    layout = useMemo(() => initialPositions(graph), [graph]);
  const nodes: DiagramNode[] = graph.nodes.map((n) => ({
    id: n.id,
    type: "workflow",
    position: positions[n.id] ?? layout.get(n.id) ?? { x: 0, y: 0 },
    selected: selected === n.id,
    ariaLabel: labels.get(n.id),
    width: 220,
    height: 100,
    data: {
      label: labels.get(n.id) ?? "Unsupported node",
      kind: n.type,
      invalid: errors.some((e) => e.nodeId === n.id),
    },
  }));
  const edges = graph.edges.map((e, i) => ({
    id: `edge-${i}`,
    source: e.source,
    target: e.target,
    sourceHandle: e.branch,
    label: e.branch,
    ariaLabel: `${labels.get(e.source)} to ${labels.get(e.target)}${e.branch ? `, ${e.branch} branch` : ""}`,
    markerEnd: { type: MarkerType.ArrowClosed },
    type: "smoothstep",
    deletable: editable && !pending,
  }));
  const current = graph.nodes.find((n) => n.id === selected);
  function select(id: string) {
    if (!pending) setSelected(id);
  }
  function reportPending(value: boolean) {
    setPending(value);
    pendingChange?.(value);
  }
  return (
    <section
      className="workflow-canvas"
      aria-label={
        change ? "Visual workflow editor" : "Published workflow canvas"
      }
    >
      <div className="section-heading">
        <div>
          <h2>{change ? "Workflow canvas" : "Published graph"}</h2>
          <p>
            Schema v{graph.schemaVersion} · {graph.nodes.length} / 50 nodes ·{" "}
            {graph.edges.length} / 100 connections
          </p>
        </div>
        <button
          type="button"
          className="button"
          onClick={() => {
            setPositions({});
            requestAnimationFrame(() => void flow?.fitView({ padding: 0.2 }));
          }}
        >
          Reset layout and fit
        </button>
      </div>
      <p>
        Connections control execution. Editing and publication do not run
        actions. Select a node to inspect its properties; connection selectors
        also support keyboard editing.
      </p>
      {change && (
        <div className="action-row">
          <label>
            Canvas node type
            <select
              value={palette}
              disabled={!editable || pending}
              onChange={(e) => setPalette(e.target.value)}
            >
              {Object.entries(canvasTypes)
                .filter(
                  ([type]) =>
                    graph.schemaVersion === 2 ||
                    [
                      "manualTrigger",
                      "syntheticAction",
                      "approval",
                      "end",
                    ].includes(type),
                )
                .map(([type, label]) => (
                  <option key={type} value={type}>
                    {label}
                  </option>
                ))}
            </select>
          </label>
          <button
            type="button"
            className="button"
            disabled={!editable || pending || graph.nodes.length >= 50}
            onClick={() => {
              const id = `n${crypto.randomUUID().replaceAll("-", "")}`;
              change(addCanvasNode(graph, palette, id));
              setSelected(id);
            }}
          >
            Add canvas node
          </button>
        </div>
      )}
      <div className="canvas-diagram">
        <ReactFlow<DiagramNode>
          nodes={nodes}
          edges={edges}
          nodeTypes={nodeTypes}
          onInit={setFlow}
          onNodeClick={(_, n) => select(n.id)}
          onNodesChange={(changes) => {
            const moved = changes.filter((c) => c.type === "position");
            if (moved.length)
              setPositions((old) => {
                const next = { ...old };
                for (const c of moved) if (c.position) next[c.id] = c.position;
                return next;
              });
            for (const c of changes)
              if (c.type === "select" && c.selected) select(c.id);
          }}
          onConnect={(c) => {
            if (editable && !pending && c.source && c.target)
              change?.(
                connectCanvas(
                  graph,
                  c.source,
                  c.target,
                  c.sourceHandle === "true" || c.sourceHandle === "false"
                    ? c.sourceHandle
                    : undefined,
                ),
              );
          }}
          isValidConnection={(c) =>
            Boolean(
              editable &&
              !pending &&
              c.source &&
              c.target &&
              canConnect(
                graph,
                c.source,
                c.target,
                c.sourceHandle === "true" || c.sourceHandle === "false"
                  ? c.sourceHandle
                  : undefined,
              ),
            )
          }
          onEdgesChange={(changes) => {
            const removed = new Set(
              changes.filter((c) => c.type === "remove").map((c) => c.id),
            );
            if (editable && !pending && removed.size)
              change?.({
                ...graph,
                edges: graph.edges.filter((_, i) => !removed.has(`edge-${i}`)),
              });
          }}
          nodesDraggable={true}
          nodesConnectable={editable && !pending}
          edgesReconnectable={false}
          deleteKeyCode={null}
          fitView
          minZoom={0.15}
          maxZoom={1.8}
          colorMode="system"
        >
          <Background />
          <Controls showInteractive={false} />
        </ReactFlow>
      </div>
      <div className="canvas-inspector">
        <label>
          Inspect node
          <select
            value={current?.id ?? ""}
            disabled={pending}
            onChange={(e) => select(e.target.value)}
          >
            <option value="">Choose a node</option>
            {graph.nodes.map((n) => (
              <option key={n.id} value={n.id}>
                {labels.get(n.id)}
              </option>
            ))}
          </select>
        </label>
        {current && (
          <>
            <h3>{labels.get(current.id)}</h3>
            <details>
              <summary>Technical node details</summary>
              <p>
                Stable node ID: <code>{current.id}</code> · {current.type} v
                {current.typeVersion}
              </p>
            </details>
            {errors
              .filter((e) => e.nodeId === current.id)
              .map((e, i) => (
                <p className="form-error" role="alert" key={i}>
                  {e.path}: {e.message}
                </p>
              ))}
            {editable ? (
              <JsonProperties
                key={`${current.id}/${JSON.stringify(current.config)}/${JSON.stringify(current.inputs)}`}
                config={current.config}
                inputs={current.inputs}
                v2={graph.schemaVersion === 2}
                pendingChange={reportPending}
                apply={(config, inputs) => {
                  reportPending(false);
                  change?.({
                    ...graph,
                    nodes: graph.nodes.map((n) =>
                      n.id === current.id
                        ? {
                            ...n,
                            config,
                            ...(graph.schemaVersion === 2 ? { inputs } : {}),
                          }
                        : n,
                    ),
                  });
                }}
              />
            ) : (
              <pre
                className="definition-json"
                tabIndex={0}
                aria-label="Node properties"
              >
                {JSON.stringify(
                  {
                    config: current.config,
                    ...(current.inputs ? { inputs: current.inputs } : {}),
                  },
                  null,
                  2,
                )}
              </pre>
            )}
            {current.type !== "end" &&
              (current.type === "condition"
                ? (["true", "false"] as const)
                : [undefined]
              ).map((branch) => (
                <label key={branch ?? "next"}>
                  {branch
                    ? `${branch === "true" ? "True" : "False"} branch target`
                    : "Canvas next node"}
                  <select
                    disabled={!editable || pending}
                    value={
                      graph.edges.find(
                        (e) => e.source === current.id && e.branch === branch,
                      )?.target ?? ""
                    }
                    onChange={(e) => {
                      if (!e.target.value)
                        change?.({
                          ...graph,
                          edges: graph.edges.filter(
                            (edge) =>
                              edge.source !== current.id ||
                              edge.branch !== branch,
                          ),
                        });
                      else
                        change?.(
                          connectCanvas(
                            graph,
                            current.id,
                            e.target.value,
                            branch,
                          ),
                        );
                    }}
                  >
                    <option value="">No connection</option>
                    {graph.nodes
                      .filter((n) =>
                        canConnect(graph, current.id, n.id, branch),
                      )
                      .map((n) => (
                        <option key={n.id} value={n.id}>
                          {labels.get(n.id)}
                        </option>
                      ))}
                  </select>
                </label>
              ))}
            {change && (
              <button
                type="button"
                className="button danger"
                disabled={!editable || pending}
                onClick={() => {
                  change(removeCanvasNode(graph, current.id));
                  setSelected(undefined);
                }}
              >
                Remove selected node
              </button>
            )}
          </>
        )}
      </div>
    </section>
  );
}

function JsonProperties({
  config,
  inputs,
  v2,
  apply,
  pendingChange,
}: {
  config: Record<string, unknown>;
  inputs?: Record<string, unknown>;
  v2: boolean;
  apply: (
    config: Record<string, unknown>,
    inputs: Record<string, unknown>,
  ) => void;
  pendingChange: (pending: boolean) => void;
}) {
  const originalConfig = JSON.stringify(config, null, 2),
    originalInputs = JSON.stringify(inputs ?? {}, null, 2);
  const [configText, setConfigText] = useState(originalConfig),
    [inputText, setInputText] = useState(originalInputs),
    [error, setError] = useState("");
  const dirty = configText !== originalConfig || inputText !== originalInputs;
  return (
    <div className="canvas-properties">
      <p>
        Configuration uses registered fields only. Input mappings use literal,
        from/path or bounded parts. Apply properties, then save and validate the
        draft. Server validation remains authoritative.
      </p>
      <label>
        Node configuration JSON
        <textarea
          value={configText}
          rows={6}
          maxLength={131072}
          onChange={(e) => {
            setConfigText(e.target.value);
            pendingChange(
              e.target.value !== originalConfig || inputText !== originalInputs,
            );
          }}
        />
      </label>
      {v2 && (
        <label>
          Input mapping JSON
          <textarea
            value={inputText}
            rows={6}
            maxLength={131072}
            onChange={(e) => {
              setInputText(e.target.value);
              pendingChange(
                configText !== originalConfig ||
                  e.target.value !== originalInputs,
              );
            }}
          />
        </label>
      )}
      {error && <p role="alert">{error}</p>}
      {dirty && (
        <p role="status">
          Unapplied property edits. Save and publish remain blocked until you
          apply or discard them.
        </p>
      )}
      <div className="action-row">
        <button
          type="button"
          className="button"
          disabled={!dirty}
          onClick={() => {
            try {
              const c: unknown = JSON.parse(configText),
                i: unknown = JSON.parse(inputText);
              if (
                !c ||
                typeof c !== "object" ||
                Array.isArray(c) ||
                !i ||
                typeof i !== "object" ||
                Array.isArray(i)
              )
                throw new Error();
              apply(c as Record<string, unknown>, i as Record<string, unknown>);
              setError("");
            } catch {
              setError(
                "Properties must be valid JSON objects. Submitted values are not included in this error.",
              );
            }
          }}
        >
          Apply node properties
        </button>
        <button
          type="button"
          className="button"
          disabled={!dirty}
          onClick={() => {
            setConfigText(originalConfig);
            setInputText(originalInputs);
            setError("");
            pendingChange(false);
          }}
        >
          Discard property edits
        </button>
      </div>
    </div>
  );
}
