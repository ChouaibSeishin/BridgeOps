import { useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { connectionApi } from "../integrations/connectionApi";
import type { CanvasNode } from "./canvasModel";
import { literalDefault, object, outputFields } from "./mappingModel";
import { requiresExactNumbers } from "./precision";

export interface MappingScope {
  organization: string;
  user: string;
}
interface Props {
  label: string;
  value: unknown;
  change: (value: unknown) => void;
  sources: CanvasNode[];
  names: Map<string, string>;
  scope?: MappingScope;
  expectedType?: string;
  parts?: boolean;
  literalOnly?: boolean;
  pending: () => void;
}
export function MappingField({
  label,
  value,
  change,
  sources,
  names,
  scope,
  expectedType,
  parts = true,
  literalOnly = false,
  pending,
}: Props) {
  const binding = object(value),
    mode =
      binding && Object.hasOwn(binding, "literal")
        ? "literal"
        : binding && Object.hasOwn(binding, "from")
          ? "source"
          : binding && Array.isArray(binding.parts)
            ? "parts"
            : "unknown";
  const source = sources.find((n) => n.id === binding?.from);
  return (
    <fieldset className="mapping-field">
      <legend>{label}</legend>
      <label>
        {label} mapping
        <select
          value={mode}
          onChange={(e) => {
            pending();
            change(
              e.target.value === "literal"
                ? { literal: literalDefault(expectedType ?? "STRING") }
                : e.target.value === "source"
                  ? { from: "", path: "" }
                  : { parts: [{ literal: "" }] },
            );
          }}
        >
          {mode === "unknown" && (
            <option value="unknown">
              Unsupported saved mapping — inspect advanced JSON
            </option>
          )}
          <option value="literal">Literal value</option>
          {!literalOnly && <option value="source">Earlier step output</option>}
          {parts && !literalOnly && (
            <option value="parts">Compose text from parts</option>
          )}
        </select>
      </label>
      {mode === "literal" && (
        <LiteralField
          label={label}
          value={binding!.literal}
          expectedType={expectedType}
          primitivesOnly={!parts}
          pending={pending}
          change={(v) => change({ literal: v })}
        />
      )}
      {mode === "source" && (
        <>
          <label>
            {label} source
            <select
              required
              value={String(binding!.from ?? "")}
              onChange={(e) => {
                pending();
                change({ from: e.target.value, path: "" });
              }}
            >
              <option value="">Choose a guaranteed earlier step</option>
              {Boolean(binding!.from) && !source && (
                <option value={String(binding!.from)}>
                  Unavailable saved source
                </option>
              )}
              {sources.map((n) => (
                <option value={n.id} key={n.id}>
                  {names.get(n.id) ?? "Earlier step"}
                </option>
              ))}
            </select>
          </label>
          {source?.type === "configuredHttp" && scope ? (
            <RestOutputField
              label={label}
              source={source}
              scope={scope}
              value={String(binding!.path ?? "")}
              change={(path) => {
                pending();
                change({ from: source.id, path });
              }}
            />
          ) : (
            <OutputField
              label={label}
              values={
                source
                  ? outputFields(source).filter((path) => parts || path !== "$")
                  : []
              }
              value={String(binding!.path ?? "")}
              change={(path) => {
                pending();
                change({ from: String(binding!.from), path });
              }}
            />
          )}
          {!sources.length && (
            <p>
              Connect an acyclic graph first. Only steps guaranteed to run
              before this step can supply data.
            </p>
          )}
        </>
      )}
      {mode === "parts" && (
        <>
          <p>
            Compose at most 16 primitive parts. Whole objects and arrays cannot
            be interpolated.
          </p>
          {(binding!.parts as unknown[]).map((part, index) => (
            <div key={index}>
              <MappingField
                label={`${label} part ${index + 1}`}
                value={part}
                change={(next) =>
                  change({
                    parts: (binding!.parts as unknown[]).map((p, i) =>
                      i === index ? next : p,
                    ),
                  })
                }
                sources={sources}
                names={names}
                scope={scope}
                parts={false}
                pending={pending}
              />
              <button
                type="button"
                className="button"
                disabled={(binding!.parts as unknown[]).length <= 1}
                onClick={() => {
                  pending();
                  change({
                    parts: (binding!.parts as unknown[]).filter(
                      (_, i) => i !== index,
                    ),
                  });
                }}
              >
                Remove {label} part {index + 1}
              </button>
            </div>
          ))}
          <button
            type="button"
            className="button"
            disabled={(binding!.parts as unknown[]).length >= 16}
            onClick={() => {
              pending();
              change({
                parts: [...(binding!.parts as unknown[]), { literal: "" }],
              });
            }}
          >
            Add {label} text part
          </button>
        </>
      )}
    </fieldset>
  );
}
function LiteralField({
  label,
  value,
  expectedType,
  primitivesOnly,
  pending,
  change,
}: {
  label: string;
  value: unknown;
  expectedType?: string;
  primitivesOnly: boolean;
  pending: () => void;
  change: (value: unknown) => void;
}) {
  const kind =
    typeof value === "string"
      ? "STRING"
      : typeof value === "number" || value === null
        ? "INTEGER"
        : typeof value === "boolean"
          ? "BOOLEAN"
          : Array.isArray(value)
            ? "ARRAY"
            : object(value)
              ? "OBJECT"
              : "UNKNOWN";
  const choices = expectedType
    ? [expectedType]
    : primitivesOnly
      ? ["STRING", "INTEGER", "BOOLEAN"]
      : ["STRING", "INTEGER", "BOOLEAN", "ARRAY", "OBJECT"];
  return (
    <>
      <label>
        {label} literal type
        <select
          value={kind}
          onChange={(e) => {
            pending();
            change(literalDefault(e.target.value));
          }}
        >
          {!choices.includes(kind) && (
            <option value={kind}>Saved {kind} — choose a supported type</option>
          )}
          {choices.map((type) => (
            <option value={type} key={type}>
              {type}
            </option>
          ))}
        </select>
      </label>
      {kind === "STRING" && (
        <label>
          {label} literal value
          <input
            value={value as string}
            maxLength={2000}
            onChange={(e) => {
              pending();
              change(e.target.value);
            }}
          />
        </label>
      )}
      {kind === "INTEGER" && (
        <label>
          {label} literal value
          <input
            type="number"
            required
            step={1}
            min={-Number.MAX_SAFE_INTEGER}
            max={Number.MAX_SAFE_INTEGER}
            value={typeof value === "number" ? value : ""}
            onChange={(e) => {
              pending();
              change(e.target.value === "" ? null : Number(e.target.value));
            }}
          />
        </label>
      )}
      {kind === "BOOLEAN" && (
        <label>
          {label} literal value
          <select
            value={String(value)}
            onChange={(e) => {
              pending();
              change(e.target.value === "true");
            }}
          >
            <option value="true">True</option>
            <option value="false">False</option>
          </select>
        </label>
      )}
      {(kind === "ARRAY" || kind === "OBJECT") && (
        <StructuredLiteral
          label={label}
          value={value}
          kind={kind}
          pending={pending}
          change={change}
        />
      )}
    </>
  );
}
function StructuredLiteral({
  label,
  value,
  kind,
  pending,
  change,
}: {
  label: string;
  value: unknown;
  kind: string;
  pending: () => void;
  change: (value: unknown) => void;
}) {
  const serialized = JSON.stringify(value, null, 2);
  const [draft, setDraft] = useState({
    source: serialized,
    text: serialized,
    invalid: false,
  });
  if (draft.source !== serialized)
    setDraft({ source: serialized, text: serialized, invalid: false });
  return (
    <>
      <label>
        {label} literal JSON
        <textarea
          ref={(element) => {
            if (element)
              element.setCustomValidity(
                draft.invalid
                  ? "Provide a bounded JSON value of the selected type with exact integers."
                  : "",
              );
          }}
          required
          rows={4}
          maxLength={131072}
          value={draft.text}
          onChange={(e) => {
            const text = e.target.value;
            pending();
            try {
              const next: unknown = JSON.parse(text);
              if (
                (kind === "ARRAY" ? !Array.isArray(next) : !object(next)) ||
                requiresExactNumbers(next)
              )
                throw new Error();
              e.currentTarget.setCustomValidity("");
              setDraft({
                source: JSON.stringify(next, null, 2),
                text,
                invalid: false,
              });
              change(next);
            } catch {
              e.currentTarget.setCustomValidity(
                "Provide a bounded JSON value of the selected type with exact integers.",
              );
              setDraft({ source: serialized, text, invalid: true });
            }
          }}
        />
      </label>
      {draft.invalid && (
        <p role="alert">
          Correct the literal JSON before applying properties. Parser details
          and submitted values are omitted.
        </p>
      )}
    </>
  );
}
function OutputField({
  label,
  values,
  value,
  change,
}: {
  label: string;
  values: string[];
  value: string;
  change: (value: string) => void;
}) {
  return (
    <label>
      {label} output field
      <select required value={value} onChange={(e) => change(e.target.value)}>
        <option value="">Choose an output field</option>
        {value && !values.includes(value) && (
          <option value={value}>Unavailable saved output</option>
        )}
        {values.map((path) => (
          <option value={path} key={path}>
            {path === "$" ? "All declared fields (object)" : path}
          </option>
        ))}
      </select>
    </label>
  );
}
function RestOutputField({
  label,
  source,
  scope,
  value,
  change,
}: {
  label: string;
  source: CanvasNode;
  scope: MappingScope;
  value: string;
  change: (value: string) => void;
}) {
  const id = String(source.config.integrationId ?? ""),
    operation = String(source.config.operation ?? "");
  const metadata = useQuery({
    queryKey: [
      scope.user,
      "canvas-rest-output",
      scope.organization,
      id,
      source.config.revision,
      operation,
    ],
    queryFn: async () => {
      const [connection, operations] = await Promise.all([
        connectionApi.get(scope.organization, id),
        connectionApi.operations(scope.organization, id),
      ]);
      if (connection.revision !== source.config.revision)
        throw new Error("Saved connection revision is stale");
      return operations.find((o) => o.id === operation);
    },
    enabled: Boolean(id),
    retry: false,
    gcTime: 0,
    refetchOnWindowFocus: false,
  });
  return (
    <>
      {metadata.isPending && (
        <p role="status">Loading registered response fields…</p>
      )}
      {metadata.isError && (
        <p role="alert">
          Registered response fields are unavailable or the connection revision
          changed.{" "}
          <button
            type="button"
            className="button"
            onClick={() => void metadata.refetch()}
          >
            Reload response fields
          </button>
        </p>
      )}
      <OutputField
        label={label}
        value={value}
        values={
          metadata.data
            ? Object.keys(metadata.data.responseFields).map(
                (field) => `response.${field}`,
              )
            : []
        }
        change={change}
      />
    </>
  );
}
