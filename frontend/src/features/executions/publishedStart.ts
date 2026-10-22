import {
  isCanvasGraph,
  displayOrder,
  canvasLabels,
} from "../workflows/canvasModel";

export type InputType = "STRING" | "INTEGER" | "BOOLEAN";
export function publishedStart(definition: unknown) {
  if (!isCanvasGraph(definition))
    throw new Error("Invalid published definition");
  const trigger = definition.nodes.find((node) =>
    ["manualTrigger", "webhookTrigger"].includes(node.type),
  );
  if (!trigger) throw new Error("Missing published trigger");
  const fields: [string, InputType][] = [];
  if (definition.schemaVersion === 2) {
    const schema = trigger.config.inputFields;
    if (!schema || typeof schema !== "object" || Array.isArray(schema))
      throw new Error("Invalid published inputs");
    for (const [key, type] of Object.entries(schema)) {
      if (
        !/^[A-Za-z][A-Za-z0-9_]{0,63}$/.test(key) ||
        !["STRING", "INTEGER", "BOOLEAN"].includes(type)
      )
        throw new Error("Invalid published inputs");
      fields.push([key, type as InputType]);
    }
  }
  const labels = canvasLabels(definition);
  const supported = new Set([
    "manualTrigger",
    "syntheticAction",
    "capabilityAction",
    "notification",
    "condition",
    "transform",
    "delay",
    "approval",
    "end",
  ]);
  const operations = new Set([
    "support.list",
    "support.get",
    "support.assignBatch",
    "support.addNote",
    "teams.list",
    "teams.get",
    "teams.resolve",
  ]);
  const unsupported = definition.nodes.some(
    (node) =>
      !supported.has(node.type) ||
      (node.type === "capabilityAction" &&
        !operations.has(String(node.config.operation))),
  );
  const effects = displayOrder(definition)
    .filter((node) =>
      [
        "syntheticAction",
        "capabilityAction",
        "configuredHttp",
        "notification",
      ].includes(node.type),
    )
    .map((node) => ({
      id: node.id,
      label: labels.get(node.id) ?? node.type,
      operation:
        node.type === "notification"
          ? "notifications.create"
          : node.type === "syntheticAction"
            ? "synthetic.record"
            : String(node.config.operation),
      connection:
        typeof node.config.integrationId === "string"
          ? node.config.integrationId
          : null,
      revision: node.config.revision,
      inputs: node.inputs ?? {},
      protected:
        node.type === "configuredHttp" ||
        ["support.assignBatch", "support.addNote"].includes(
          String(node.config.operation),
        ),
    }));
  return {
    fields: fields.sort(([a], [b]) => a.localeCompare(b)),
    effects,
    unsupported,
    manual: trigger.type === "manualTrigger",
  };
}

/** Browser numbers must be exact; the server also validates its complete signed-integer domain. */
export function triggerValues(
  fields: [string, InputType][],
  values: Record<string, string>,
): Record<string, string | number | boolean> | null {
  const result: Record<string, string | number | boolean> = Object.create(
    null,
  ) as Record<string, string | number | boolean>;
  for (const [name, type] of fields) {
    const value = Object.hasOwn(values, name) ? (values[name] ?? "") : "";
    if (typeof value !== "string") return null;
    if (type === "STRING") {
      if (
        value.length > 2000 ||
        Array.from(value).some((character) => {
          const code = character.codePointAt(0)!;
          return code < 32 || (code >= 127 && code <= 159);
        })
      )
        return null;
      result[name] = value;
    } else if (type === "BOOLEAN") {
      if (value !== "true" && value !== "false") return null;
      result[name] = value === "true";
    } else {
      if (
        !/^-?(0|[1-9][0-9]*)$/.test(value) ||
        !Number.isSafeInteger(Number(value))
      )
        return null;
      result[name] = Number(value);
    }
  }
  return result;
}
