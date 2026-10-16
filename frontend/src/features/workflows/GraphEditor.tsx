import { useState } from "react";
import {
  addNode,
  removeNode,
  registry,
  nodeSchema,
  isGraph,
  emptyGraph,
} from "./model";
import type { Graph, ValidationError } from "./model";

export function GraphEditor({
  definition,
  change,
  disabled,
  errors,
}: {
  definition: unknown;
  change: (graph: Graph) => void;
  disabled: boolean;
  errors: ValidationError[];
}) {
  const [type, setType] = useState("syntheticAction");
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
        These definitions do not run actions or process approvals.
      </p>
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
          disabled={disabled || graph.nodes.length >= 50}
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
            >
              <legend>
                {schema?.label ?? "Unknown type"} ·{" "}
                <span className="mono">{node.id}</span>
              </legend>
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
                              String(node.config[field.key]),
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
                          {nodeSchema(target.type)?.label ?? target.type} ·{" "}
                          {target.id}
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

export function ReadOnlyDefinition({ definition }: { definition: unknown }) {
  return (
    <section className="data-panel">
      <h2>Immutable definition</h2>
      <p>
        This snapshot is read-only. Editing its separate draft cannot change
        these bytes.
      </p>
      {isGraph(definition) && (
        <>
          <h3>Connections</h3>
          <ul>
            {definition.edges.map((edge, index) => (
              <li key={index} className="mono">
                {edge.source} → {edge.target}
              </li>
            ))}
          </ul>
          <h3>Registered nodes</h3>
          <dl>
            {definition.nodes.map((node) => (
              <div key={node.id}>
                <dt>
                  {nodeSchema(node.type)?.label ?? node.type} · {node.id} · v
                  {node.typeVersion}
                </dt>
                <dd>
                  {Object.entries(node.config).map(([key, value]) => (
                    <p key={key}>
                      {key}: {String(value)}
                    </p>
                  ))}
                </dd>
              </div>
            ))}
          </dl>
        </>
      )}
      <pre
        className="definition-json"
        tabIndex={0}
        aria-label="Published definition JSON"
      >
        {JSON.stringify(definition, null, 2)}
      </pre>
    </section>
  );
}
