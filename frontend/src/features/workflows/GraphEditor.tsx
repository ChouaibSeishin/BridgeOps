import { lazy, Suspense, useState, type ComponentProps } from "react";
import {
  addNode,
  removeNode,
  registry,
  nodeSchema,
  isGraph,
  emptyGraph,
  nodeLabels,
} from "./model";
import type { Graph, ValidationError } from "./model";
import { convertV1, isCanvasGraph } from "./canvasModel";
import { requiresExactNumbers } from "./precision";
import type { MappingScope } from "./MappingField";
import type { WorkflowCanvas as CanvasView } from "./WorkflowCanvas";
import { ConfirmationDialog } from "../../components/ui";
const LazyCanvas = lazy(() =>
  import("./WorkflowCanvas").then((module) => ({
    default: module.WorkflowCanvas,
  })),
);
function WorkflowCanvas(props: ComponentProps<typeof CanvasView>) {
  return (
    <Suspense fallback={<p role="status">Loading workflow canvas…</p>}>
      <LazyCanvas {...props} />
    </Suspense>
  );
}

export function GraphEditor({
  definition,
  change,
  disabled,
  errors,
  pendingChange,
  scope,
}: {
  definition: unknown;
  change: (graph: Graph) => void;
  disabled: boolean;
  errors: ValidationError[];
  pendingChange?: (pending: boolean) => void;
  scope?: MappingScope;
}) {
  const [type, setType] = useState("syntheticAction");
  const [canvasVisible, setCanvasVisible] = useState(false);
  const [convert, setConvert] = useState(false);
  const [pending, setPending] = useState(false);
  function propertiesPending(value: boolean) {
    setPending(value);
    pendingChange?.(value);
  }
  if (isCanvasGraph(definition) && definition.schemaVersion === 2)
    return (
      <section className="data-panel">
        <WorkflowCanvas
          graph={definition}
          change={change}
          disabled={disabled}
          errors={errors}
          pendingChange={propertiesPending}
          scope={scope}
        />
      </section>
    );
  if (!isGraph(definition))
    return (
      <section className="data-panel">
        <h2>Unsupported saved structure</h2>
        <p>
          Inspect the server validation. Replacing this graph explicitly starts
          a new editable definition.
        </p>
        <pre className="definition-json" tabIndex={0}>
          {JSON.stringify(definition, null, 2)}
        </pre>
        <button
          className="button"
          disabled={disabled}
          onClick={() => change(emptyGraph())}
        >
          Replace graph with trigger and end
        </button>
      </section>
    );
  const graph = definition;
  const labels = nodeLabels(graph);
  function config(id: string, field: string, value: string) {
    change({
      ...graph,
      nodes: graph.nodes.map((node) =>
        node.id === id
          ? { ...node, config: { ...node.config, [field]: value } }
          : node,
      ),
    });
  }
  function connection(id: string, target: string) {
    change({
      ...graph,
      edges: [
        ...graph.edges.filter((edge) => edge.source !== id),
        ...(target ? [{ source: id, target }] : []),
      ],
    });
  }
  return (
    <section className="data-panel graph-editor" aria-label="Draft graph">
      <div className="section-heading">
        <div>
          <h2>Definition nodes</h2>
          <p>
            {graph.nodes.length} / 50 nodes · {graph.edges.length} / 100
            connections
          </p>
        </div>
        <span className="status-badge">Schema v{graph.schemaVersion}</span>
      </div>
      <p>
        Connections define the single path. Card order is presentation only.
        Editing these definitions does not run actions or process approvals.
      </p>
      <div className="action-row">
        <button
          type="button"
          className="button"
          disabled={pending}
          aria-expanded={canvasVisible}
          onClick={() => setCanvasVisible(!canvasVisible)}
        >
          {canvasVisible ? "Hide visual canvas" : "Show visual canvas"}
        </button>
        <button
          type="button"
          className="button"
          disabled={disabled || pending}
          onClick={() => setConvert(true)}
        >
          Convert draft to schema v2
        </button>
      </div>
      {convert && (
        <ConfirmationDialog
          label="Convert draft schema"
          onCancel={() => setConvert(false)}
        >
          <p>
            Convert only this editable draft to schema v2 with explicit input
            mappings. Stable node IDs and existing configuration are retained.
            Published versions and their hashes stay immutable. Save and
            validate the converted draft before publication.
          </p>
          <button
            type="button"
            className="button"
            disabled={disabled}
            onClick={() => {
              change(convertV1(graph));
              setConvert(false);
            }}
          >
            Confirm schema v2 conversion
          </button>
          <button
            type="button"
            className="button"
            onClick={() => setConvert(false)}
          >
            Keep schema v1
          </button>
        </ConfirmationDialog>
      )}
      {canvasVisible && (
        <WorkflowCanvas
          graph={graph}
          change={change}
          disabled={disabled}
          errors={errors}
          pendingChange={propertiesPending}
        />
      )}
      <div className="action-row node-palette">
        <label>
          Node type
          <select
            value={type}
            onChange={(event) => setType(event.target.value)}
            disabled={disabled}
          >
            {Object.entries(registry).map(([key, schema]) => (
              <option key={key} value={key}>
                {schema.label} · v1
              </option>
            ))}
          </select>
        </label>
        <button
          className="button"
          disabled={disabled || pending || graph.nodes.length >= 50}
          onClick={() =>
            change(
              addNode(
                graph,
                type,
                `n${crypto.randomUUID().replaceAll("-", "")}`,
              ),
            )
          }
        >
          Add node before end
        </button>
      </div>
      <div className="workflow-nodes">
        {graph.nodes.map((node) => {
          const schema = nodeSchema(node.type);
          const nodeErrors = errors.filter((error) => error.nodeId === node.id);
          const errorDescription = nodeErrors.length
            ? `node-${node.id}-errors`
            : undefined;
          const unknownConfig =
            schema &&
            Object.keys(node.config).some(
              (key) => !schema.fields.some((field) => field.key === key),
            );
          const unsupported = !schema || node.typeVersion !== 1;
          return (
            <fieldset
              className="workflow-node"
              key={node.id}
              id={`node-${node.id}`}
              tabIndex={-1}
              disabled={pending}
            >
              <legend>{labels.get(node.id)}</legend>
              <details>
                <summary>Technical node details</summary>
                <p>
                  Stable node ID: <code>{node.id}</code> · type v
                  {node.typeVersion}
                </p>
              </details>
              <div className="node-fields">
                {unsupported && (
                  <p role="alert">
                    This node type/version is unsupported. Remove it or choose a
                    supported definition.
                  </p>
                )}
                {schema &&
                  !unsupported &&
                  schema.fields.map((field) => {
                    const invalid = nodeErrors.some((error) =>
                      error.path.endsWith(`.config.${field.key}`),
                    );
                    return (
                      <label key={field.key}>
                        {field.label}
                        {field.options ? (
                          <select
                            disabled={disabled}
                            value={
                              typeof node.config[field.key] === "string"
                                ? (node.config[field.key] as string)
                                : ""
                            }
                            aria-invalid={invalid}
                            aria-describedby={errorDescription}
                            onChange={(event) =>
                              config(node.id, field.key, event.target.value)
                            }
                          >
                            {!field.options.includes(
                              typeof node.config[field.key] === "string"
                                ? (node.config[field.key] as string)
                                : "",
                            ) && <option value="">Choose operation</option>}
                            {field.options.map((value) => (
                              <option key={value}>{value}</option>
                            ))}
                          </select>
                        ) : (
                          <input
                            disabled={disabled}
                            value={
                              typeof node.config[field.key] === "string"
                                ? (node.config[field.key] as string)
                                : ""
                            }
                            maxLength={500}
                            aria-invalid={invalid}
                            aria-describedby={errorDescription}
                            onChange={(event) =>
                              config(node.id, field.key, event.target.value)
                            }
                          />
                        )}
                      </label>
                    );
                  })}
                {unknownConfig && (
                  <p role="alert">
                    This saved configuration contains unsupported fields.
                  </p>
                )}
                {schema && (unknownConfig || node.typeVersion !== 1) && (
                  <button
                    className="button"
                    disabled={disabled}
                    onClick={() =>
                      change({
                        ...graph,
                        nodes: graph.nodes.map((value) =>
                          value.id === node.id
                            ? {
                                ...value,
                                typeVersion: 1,
                                config: { ...schema.defaults },
                              }
                            : value,
                        ),
                      })
                    }
                  >
                    Reset node configuration to v1
                  </button>
                )}
                <label>
                  Next node
                  <select
                    disabled={disabled}
                    value={
                      graph.edges.find((edge) => edge.source === node.id)
                        ?.target ?? ""
                    }
                    onChange={(event) =>
                      connection(node.id, event.target.value)
                    }
                  >
                    <option value="">No connection</option>
                    {graph.nodes
                      .filter((target) => target.id !== node.id)
                      .map((target) => (
                        <option key={target.id} value={target.id}>
                          {labels.get(target.id)}
                        </option>
                      ))}
                  </select>
                </label>
              </div>
              {nodeErrors.length > 0 && (
                <ul className="form-error" id={`node-${node.id}-errors`}>
                  {nodeErrors.map((error, index) => (
                    <li key={index}>{error.message}</li>
                  ))}
                </ul>
              )}
              <button
                className="button danger"
                disabled={disabled}
                onClick={() => change(removeNode(graph, node.id))}
              >
                Remove node
              </button>
            </fieldset>
          );
        })}
      </div>
      {graph.nodes.length === 0 && (
        <p>
          No nodes yet. Add a manual trigger, supported definitions and an end.
        </p>
      )}
    </section>
  );
}

export function ReadOnlyDefinition({
  definition,
  definitionJson,
  draft = false,
}: {
  definition: unknown;
  definitionJson?: string;
  draft?: boolean;
}) {
  const [canvasVisible, setCanvasVisible] = useState(false);
  const exactOnly = requiresExactNumbers(definition);
  const labels = isGraph(definition)
    ? nodeLabels(definition)
    : new Map<string, string>();
  return (
    <section className="data-panel">
      <h2>{draft ? "Saved draft definition" : "Immutable definition"}</h2>
      <p>
        {draft
          ? "This saved draft is read-only in the browser to preserve its exact numbers. Use the authenticated API for edits outside the browser's supported integer range."
          : "This snapshot is read-only. Editing its separate draft cannot change these bytes."}
      </p>
      {exactOnly && (
        <p role="alert">
          This definition contains integers outside the browser's exact editing
          range. Inspect the original server JSON below; rounded diagram values
          are hidden.
        </p>
      )}
      {!exactOnly && isCanvasGraph(definition) && (
        <details onToggle={(e) => setCanvasVisible(e.currentTarget.open)}>
          <summary>Visual published graph</summary>
          {canvasVisible && <WorkflowCanvas graph={definition} />}
        </details>
      )}
      {!exactOnly && isGraph(definition) && (
        <>
          <h3>Connections</h3>
          <ul>
            {definition.edges.map((edge, index) => (
              <li key={index} className="mono">
                {labels.get(edge.source) ?? "Unknown node"} →{" "}
                {labels.get(edge.target) ?? "Unknown node"}
              </li>
            ))}
          </ul>
          <h3>Registered nodes</h3>
          <dl>
            {definition.nodes.map((node) => (
              <div key={node.id}>
                <dt>{labels.get(node.id)}</dt>
                <dd>
                  <details>
                    <summary>Technical node details</summary>
                    <code>{node.id}</code> · type v{node.typeVersion}
                  </details>
                  {Object.entries(node.config).map(([key, value]) => (
                    <p key={key}>
                      {key}:{" "}
                      {typeof value === "string"
                        ? value
                        : JSON.stringify(value)}
                    </p>
                  ))}
                </dd>
              </div>
            ))}
          </dl>
        </>
      )}
      <details>
        <summary>Technical definition JSON</summary>
        <pre
          className="definition-json"
          tabIndex={0}
          aria-label={
            draft ? "Saved draft definition JSON" : "Published definition JSON"
          }
        >
          {definitionJson ??
            (exactOnly
              ? "Exact source is unavailable. Reload from the current server before inspecting this definition."
              : JSON.stringify(definition, null, 2))}
        </pre>
      </details>
    </section>
  );
}
