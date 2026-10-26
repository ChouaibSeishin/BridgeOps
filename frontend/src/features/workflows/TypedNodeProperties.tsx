import { useState, type Ref, type ReactNode } from "react";
import type { CanvasGraph, CanvasNode } from "./canvasModel";
import { canvasLabels } from "./canvasModel";
import { fieldName, mappingSources, object, scalarTypes } from "./mappingModel";
import { MappingField, type MappingScope } from "./MappingField";
import { ConnectionProperties } from "./ConnectionProperties";

export interface TypedPropertiesProps {
  node: CanvasNode;
  graph: CanvasGraph;
  config: Record<string, unknown>;
  inputs: Record<string, unknown>;
  change: (
    config: Record<string, unknown>,
    inputs: Record<string, unknown>,
  ) => void;
  pending: () => void;
  formRef: Ref<HTMLFormElement>;
  scope?: MappingScope;
}
export function TypedNodeProperties(props: TypedPropertiesProps) {
  const { node, graph, config, inputs, change, pending, formRef, scope } =
    props;
  const sources = mappingSources(graph, node.id),
    names = canvasLabels(graph);
  const configField = (key: string, value: unknown) => {
    pending();
    change({ ...config, [key]: value }, inputs);
  };
  const mapping = (name: string, type?: string) => (
    <MappingField
      key={name}
      label={`Input ${name}`}
      value={inputs[name]}
      change={(value) => change(config, { ...inputs, [name]: value })}
      pending={pending}
      sources={sources}
      names={names}
      expectedType={type}
      scope={scope}
    />
  );
  return (
    <form
      ref={formRef}
      className="typed-node-properties"
      onSubmit={(e) => e.preventDefault()}
    >
      <h4>Node settings</h4>
      {(node.type === "manualTrigger" || node.type === "webhookTrigger") &&
        graph.schemaVersion === 2 && (
          <>
            <p>
              Declare at most 32 flat trigger fields. Changes affect this draft;
              published inputs remain frozen.
            </p>
            <NamedFields
              label="Trigger"
              entries={object(config.inputFields) ?? {}}
              add={(name) =>
                configField("inputFields", {
                  ...object(config.inputFields),
                  [name]: "STRING",
                })
              }
              remove={(name) =>
                configField(
                  "inputFields",
                  Object.fromEntries(
                    Object.entries(object(config.inputFields) ?? {}).filter(
                      ([key]) => key !== name,
                    ),
                  ),
                )
              }
            >
              {(name, value) => (
                <label>
                  {name} trigger type
                  <select
                    value={String(value)}
                    onChange={(e) =>
                      configField("inputFields", {
                        ...object(config.inputFields),
                        [name]: e.target.value,
                      })
                    }
                  >
                    {!scalarTypes.includes(
                      value as (typeof scalarTypes)[number],
                    ) && (
                      <option value={String(value)}>
                        Unsupported saved type
                      </option>
                    )}
                    {scalarTypes.map((type) => (
                      <option key={type}>{type}</option>
                    ))}
                  </select>
                </label>
              )}
            </NamedFields>
          </>
        )}
      {node.type === "syntheticAction" && (
        <>
          <p>
            Registered operation: internal record. This does not send email or
            contact a provider.
          </p>
          <label>
            Record message
            <input
              required
              maxLength={500}
              value={String(config.message ?? "")}
              onChange={(e) => configField("message", e.target.value)}
            />
          </label>
        </>
      )}
      {node.type === "approval" && (
        <label>
          Approval review prompt
          <textarea
            required
            rows={3}
            maxLength={500}
            value={String(config.prompt ?? "")}
            onChange={(e) => configField("prompt", e.target.value)}
          />
        </label>
      )}
      {node.type === "delay" && (
        <label>
          Delay seconds
          <input
            required
            type="number"
            min={0}
            max={3600}
            step={1}
            value={typeof config.seconds === "number" ? config.seconds : ""}
            onChange={(e) =>
              configField(
                "seconds",
                e.target.value === "" ? null : Number(e.target.value),
              )
            }
          />
        </label>
      )}
      {node.type === "condition" && (
        <>
          <label>
            Comparison operator
            <select
              value={String(config.operator ?? "")}
              onChange={(e) => {
                pending();
                const op = e.target.value;
                change(
                  ["EMPTY", "NOT_EMPTY"].includes(op)
                    ? { operator: op }
                    : {
                        ...config,
                        operator: op,
                        compare: Object.hasOwn(config, "compare")
                          ? config.compare
                          : 0,
                      },
                  inputs,
                );
              }}
            >
              {["EQ", "NE", "GT", "GTE", "LT", "LTE", "EMPTY", "NOT_EMPTY"].map(
                (op) => (
                  <option key={op}>{op}</option>
                ),
              )}
            </select>
          </label>
          {!["EMPTY", "NOT_EMPTY"].includes(String(config.operator)) && (
            <MappingField
              literalOnly
              label="Compare against"
              value={{ literal: config.compare }}
              expectedType={
                ["GT", "GTE", "LT", "LTE"].includes(String(config.operator))
                  ? "INTEGER"
                  : undefined
              }
              sources={[]}
              names={names}
              pending={pending}
              parts={false}
              change={(value) => {
                const literal = object(value);
                if (literal && Object.hasOwn(literal, "literal"))
                  configField("compare", literal.literal);
              }}
            />
          )}
          {mapping("value")}
        </>
      )}
      {node.type === "transform" && (
        <>
          <p>
            Construct at most 32 named fields from literals or guaranteed
            earlier outputs. Other steps can select one field or the complete
            bounded object.
          </p>
          <NamedFields
            label="Transform"
            entries={inputs}
            add={(name) => {
              pending();
              change(config, { ...inputs, [name]: { literal: "" } });
            }}
            remove={(name) => {
              pending();
              change(
                config,
                Object.fromEntries(
                  Object.entries(inputs).filter(([key]) => key !== name),
                ),
              );
            }}
          >
            {(name) => mapping(name)}
          </NamedFields>
        </>
      )}
      {["capabilityAction", "configuredHttp", "notification"].includes(
        node.type,
      ) &&
        (scope ? (
          <ConnectionProperties
            {...props}
            scope={scope}
            sources={sources}
            names={names}
          />
        ) : (
          <p>
            Registered connection choices require organization context. Advanced
            JSON remains available; the server verifies all references.
          </p>
        ))}
      {(node.type === "end" ||
        (node.type.endsWith("Trigger") && graph.schemaVersion === 1)) && (
        <p>This node has no configurable settings.</p>
      )}
    </form>
  );
}
function NamedFields({
  label,
  entries,
  add,
  remove,
  children,
}: {
  label: string;
  entries: Record<string, unknown>;
  add: (name: string) => void;
  remove: (name: string) => void;
  children: (name: string, value: unknown) => ReactNode;
}) {
  const [name, setName] = useState("");
  const valid =
    fieldName.test(name) &&
    !Object.hasOwn(entries, name) &&
    Object.keys(entries).length < 32;
  return (
    <>
      {Object.entries(entries).map(([key, value]) => (
        <div className="typed-field-row" key={key}>
          {children(key, value)}
          <button
            type="button"
            className="button danger"
            onClick={() => remove(key)}
          >
            Remove {label.toLowerCase()} field {key}
          </button>
        </div>
      ))}
      <label>
        New {label.toLowerCase()} field name
        <input
          value={name}
          maxLength={64}
          onChange={(e) => setName(e.target.value)}
        />
      </label>
      {name && !valid && (
        <p role="alert">
          Choose a unique field name beginning with a letter, using only
          letters, numbers and underscores. At most 32 fields are supported.
        </p>
      )}
      <button
        type="button"
        className="button"
        disabled={!valid}
        onClick={() => {
          add(name);
          setName("");
        }}
      >
        Add {label.toLowerCase()} field
      </button>
    </>
  );
}
