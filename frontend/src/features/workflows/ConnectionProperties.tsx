import { useState } from "react";
import { useQuery } from "@tanstack/react-query";
import {
  connectionApi,
  type ConnectionSummary,
  type OperationDescriptor,
} from "../integrations/connectionApi";
import type { TypedPropertiesProps } from "./TypedNodeProperties";
import { MappingField, type MappingScope } from "./MappingField";
import type { CanvasNode } from "./canvasModel";
import { literalDefault, object } from "./mappingModel";

export function ConnectionProperties({
  node,
  config,
  inputs,
  change,
  pending,
  scope,
  sources,
  names,
}: TypedPropertiesProps & {
  scope: MappingScope;
  sources: CanvasNode[];
  names: Map<string, string>;
}) {
  const [page, setPage] = useState(0),
    id = String(config.integrationId ?? ""),
    validId =
      /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/.test(id);
  const list = useQuery({
    queryKey: [scope.user, "canvas-connections", scope.organization, page],
    queryFn: () => connectionApi.list(scope.organization, page),
    retry: false,
    gcTime: 0,
    refetchOnWindowFocus: false,
  });
  const selected = useQuery({
    queryKey: [scope.user, "canvas-connection", scope.organization, id],
    queryFn: () => connectionApi.get(scope.organization, id),
    enabled: validId,
    retry: false,
    gcTime: 0,
    refetchOnWindowFocus: false,
  });
  const operations = useQuery({
    queryKey: [scope.user, "canvas-operations", scope.organization, id],
    queryFn: () => connectionApi.operations(scope.organization, id),
    enabled: validId,
    retry: false,
    gcTime: 0,
    refetchOnWindowFocus: false,
  });
  const allowed = (connection: ConnectionSummary) =>
    node.type === "configuredHttp"
      ? connection.type === "REST"
      : node.type === "notification"
        ? connection.type === "NOTIFICATIONS"
        : ["SUPPORT", "TEAMS"].includes(connection.type);
  const choices = list.data?.items.filter(allowed) ?? [],
    connection = selected.data;
  const prefix =
    connection?.type === "SUPPORT"
      ? "support."
      : connection?.type === "TEAMS"
        ? "teams."
        : "";
  const savedOperation =
    node.type === "notification"
      ? "create"
      : String(config.operation ?? "").replace(prefix, "");
  const operation = operations.data?.find((o) => o.id === savedOperation),
    stale = connection && connection.revision !== config.revision;
  function chooseOperation(value: string) {
    const op = operations.data?.find((o) => o.id === value);
    if (!op) return;
    pending();
    const required =
      node.type === "configuredHttp"
        ? { pathParameters: "OBJECT", fields: "OBJECT" }
        : op.requestFields;
    const next = Object.fromEntries(
      Object.entries(required).map(([key, type]) => [
        key,
        Object.hasOwn(inputs, key)
          ? inputs[key]
          : { literal: defaultValue(op, key, type) },
      ]),
    );
    change(
      {
        ...config,
        ...(node.type === "notification" ? {} : { operation: prefix + value }),
      },
      next,
    );
  }
  return (
    <>
      <p>
        Only registered organization connections and operations are selectable.
        Changing an operation replaces its argument field set; review before
        applying. A saved revision is never updated automatically.
      </p>
      {list.isPending && <p role="status">Loading registered connections…</p>}
      {list.isError && (
        <p role="alert">
          Registered connections are unavailable or permission was denied.{" "}
          <button
            type="button"
            className="button"
            onClick={() => void list.refetch()}
          >
            Reload registered connections
          </button>
        </p>
      )}
      <label>
        Registered connection
        <select
          required
          value={id}
          onChange={(e) => {
            const next = choices.find((c) => c.id === e.target.value);
            if (!next) return;
            pending();
            change(
              {
                ...config,
                integrationId: next.id,
                revision: next.revision,
                ...(node.type === "notification" ? {} : { operation: "" }),
              },
              inputs,
            );
          }}
        >
          <option value="">Choose an enabled connection</option>
          {id && !choices.some((c) => c.id === id) && (
            <option value={id}>
              {connection?.name ?? "Saved connection — inspect availability"}
            </option>
          )}
          {choices.map((c) => (
            <option key={c.id} value={c.id} disabled={!c.enabled}>
              {c.name}
              {!c.enabled ? " (disabled)" : ""}
            </option>
          ))}
        </select>
      </label>
      {list.data && (
        <div className="action-row">
          <button
            type="button"
            className="button"
            disabled={page === 0}
            onClick={() => setPage(page - 1)}
          >
            Previous connections
          </button>
          <span>
            Connection page {page + 1} of {Math.max(1, list.data.totalPages)}
          </span>
          <button
            type="button"
            className="button"
            disabled={page + 1 >= list.data.totalPages}
            onClick={() => setPage(page + 1)}
          >
            Next connections
          </button>
        </div>
      )}
      {selected.isError && (
        <p role="alert">
          The saved connection cannot be inspected. Its reference has been
          retained.
        </p>
      )}
      {connection && !allowed(connection) && (
        <p role="alert">
          This connection type does not support the selected node. Choose a
          compatible connection.
        </p>
      )}
      {connection && !connection.enabled && (
        <p role="alert">This connection is disabled. It cannot dispatch.</p>
      )}
      {stale && (
        <p role="alert">
          Saved revision {String(config.revision)} differs from current revision{" "}
          {connection.revision}. Existing history remains pinned.{" "}
          <button
            type="button"
            className="button"
            onClick={() => {
              pending();
              change({ ...config, revision: connection.revision }, inputs);
            }}
          >
            Use current connection revision
          </button>
        </p>
      )}
      {validId && operations.isPending && (
        <p role="status">Loading registered operations…</p>
      )}
      {operations.isError && (
        <p role="alert">
          Registered operations could not be loaded.{" "}
          <button
            type="button"
            className="button"
            onClick={() => void operations.refetch()}
          >
            Reload registered operations
          </button>
        </p>
      )}
      {node.type !== "notification" ? (
        <label>
          Registered operation
          <select
            required
            value={savedOperation}
            onChange={(e) => chooseOperation(e.target.value)}
          >
            <option value="">Choose an operation</option>
            {savedOperation && !operation && (
              <option value={savedOperation}>
                Unavailable saved operation
              </option>
            )}
            {operations.data?.map((o) => (
              <option key={o.id} value={o.id}>
                {o.id} · {o.classification}
              </option>
            ))}
          </select>
        </label>
      ) : (
        operation && (
          <button
            type="button"
            className="button"
            onClick={() => chooseOperation("create")}
          >
            Prepare internal notification fields
          </button>
        )
      )}
      {operation && (
        <>
          <p>
            {operation.classification} ·{" "}
            {operation.requiresApproval
              ? "Exact independent human approval is mandatory."
              : "Explicit gates and current organization policy still apply."}
          </p>
          {node.type === "configuredHttp" && (
            <p>
              Request fields:{" "}
              {Object.entries(operation.requestFields)
                .map(([key, type]) => `${key} (${type})`)
                .join(", ") || "none"}
              . Path parameters:{" "}
              {operation.pathParameters?.join(", ") || "none"}. Projected
              response fields:{" "}
              {Object.entries(operation.responseFields)
                .map(([key, type]) => `${key} (${type})`)
                .join(", ") || "none"}
              . Build a transform to compose dynamic field objects, then select
              its complete declared object.
            </p>
          )}
          {node.type === "configuredHttp" && (
            <>
              <FlatLiteralFields
                name="fields"
                fields={operation.requestFields}
                binding={inputs.fields}
                change={(binding) => {
                  pending();
                  change(config, { ...inputs, fields: binding });
                }}
              />
              <FlatLiteralFields
                name="pathParameters"
                fields={Object.fromEntries(
                  (operation.pathParameters ?? []).map((name) => [
                    name,
                    "STRING",
                  ]),
                )}
                binding={inputs.pathParameters}
                change={(binding) => {
                  pending();
                  change(config, { ...inputs, pathParameters: binding });
                }}
              />
            </>
          )}
          {connection?.type === "SUPPORT" && operation.id === "list" && (
            <>
              <EnumLiteralFilter
                label="Ticket priorities"
                values={["LOW", "NORMAL", "HIGH", "URGENT"]}
                binding={inputs.priorities}
                change={(binding) => {
                  pending();
                  change(config, { ...inputs, priorities: binding });
                }}
              />
              <EnumLiteralFilter
                label="Ticket statuses"
                values={["OPEN", "IN_PROGRESS", "RESOLVED", "CLOSED"]}
                binding={inputs.statuses}
                change={(binding) => {
                  pending();
                  change(config, { ...inputs, statuses: binding });
                }}
              />
            </>
          )}
          {Object.entries(
            node.type === "configuredHttp"
              ? { pathParameters: "OBJECT", fields: "OBJECT" }
              : operation.requestFields,
          ).map(([name, type]) => (
            <MappingField
              key={name}
              label={`Input ${name}`}
              value={inputs[name]}
              change={(value) => {
                pending();
                change(config, { ...inputs, [name]: value });
              }}
              pending={pending}
              sources={sources}
              names={names}
              scope={scope}
              expectedType={type}
            />
          ))}
        </>
      )}
      <details>
        <summary>Technical connection binding</summary>
        <p>
          Connection ID: <code>{id || "Not selected"}</code> · saved revision{" "}
          {String(config.revision ?? "Not selected")}
        </p>
      </details>
    </>
  );
}
function defaultValue(
  operation: OperationDescriptor,
  name: string,
  type: string,
): unknown {
  if (operation.id === "list" && name === "limit") return 50;
  if (operation.id === "list" && name === "priorities")
    return ["LOW", "NORMAL", "HIGH", "URGENT"];
  if (operation.id === "list" && name === "statuses")
    return ["OPEN", "IN_PROGRESS", "RESOLVED", "CLOSED"];
  return literalDefault(type);
}
function FlatLiteralFields({
  name,
  fields,
  binding,
  change,
}: {
  name: string;
  fields: Record<string, string>;
  binding: unknown;
  change: (binding: unknown) => void;
}) {
  const literal = object(object(binding)?.literal);
  if (!literal || !Object.keys(fields).length) return null;
  return (
    <fieldset className="mapping-field">
      <legend>Declared literal {name}</legend>
      <p>
        These controls edit declared literal fields. Existing extra fields
        remain visible in JSON until explicitly replaced.
      </p>
      {Object.entries(fields).map(([key, type]) => (
        <label key={key}>
          {name}.{key} ({type})
          {type === "BOOLEAN" ? (
            <select
              required
              value={
                typeof literal[key] === "boolean" ? String(literal[key]) : ""
              }
              onChange={(e) =>
                change({
                  literal: {
                    ...literal,
                    [key]:
                      e.target.value === "" ? null : e.target.value === "true",
                  },
                })
              }
            >
              <option value="">Choose true or false</option>
              <option value="true">True</option>
              <option value="false">False</option>
            </select>
          ) : type === "INTEGER" ? (
            <input
              required
              type="number"
              step={1}
              min={-Number.MAX_SAFE_INTEGER}
              max={Number.MAX_SAFE_INTEGER}
              value={
                typeof literal[key] === "number" ? (literal[key] as number) : ""
              }
              onChange={(e) =>
                change({
                  literal: {
                    ...literal,
                    [key]:
                      e.target.value === "" ? null : Number(e.target.value),
                  },
                })
              }
            />
          ) : (
            <input
              required={
                !Object.hasOwn(literal, key) || typeof literal[key] !== "string"
              }
              maxLength={2000}
              value={
                typeof literal[key] === "string" ? (literal[key] as string) : ""
              }
              onChange={(e) =>
                change({ literal: { ...literal, [key]: e.target.value } })
              }
            />
          )}
        </label>
      ))}
      <button
        type="button"
        className="button"
        onClick={() =>
          change({
            literal: Object.fromEntries(
              Object.entries(fields).map(([key, type]) => [
                key,
                literalDefault(type),
              ]),
            ),
          })
        }
      >
        Replace {name} with declared literal defaults
      </button>
    </fieldset>
  );
}
function EnumLiteralFilter({
  label,
  values,
  binding,
  change,
}: {
  label: string;
  values: string[];
  binding: unknown;
  change: (binding: unknown) => void;
}) {
  const literal = object(binding)?.literal;
  if (!Array.isArray(literal)) return null;
  const selected = literal.filter(
    (value): value is string => typeof value === "string",
  );
  return (
    <label>
      {label}
      <select
        multiple
        required
        size={4}
        value={selected}
        onChange={(e) =>
          change({
            literal: Array.from(
              e.target.selectedOptions,
              (option) => option.value,
            ),
          })
        }
      >
        {selected
          .filter((value) => !values.includes(value))
          .map((value) => (
            <option value={value} key={value}>
              Unsupported saved filter value
            </option>
          ))}
        {values.map((value) => (
          <option key={value}>{value}</option>
        ))}
      </select>
    </label>
  );
}
