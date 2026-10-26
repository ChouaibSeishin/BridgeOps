import { render, screen, fireEvent, waitFor } from "@testing-library/react";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import { afterEach, beforeEach, expect, it, vi } from "vitest";
import { ExecutionStart } from "./ExecutionStart";
import { executionApi } from "./api";
import { workflowApi } from "../workflows/api";
import type { Snapshot } from "../workflows/model";
import { publishedStart, triggerValues } from "./publishedStart";
import type { CanvasGraph } from "../workflows/canvasModel";

function snapshot(): Snapshot {
  return {
    version: {
      id: "published",
      workflowId: "workflow",
      versionNumber: 7,
      sourceRevision: 9,
      schemaVersion: 2,
      canonicalizationVersion: 2,
      name: "Published business plan",
      description: "",
      definitionHash: "a".repeat(64),
      publishedBy: "author",
      publishedAt: "2026-10-22T00:00:00Z",
    },
    definition: {
      schemaVersion: 2,
      nodes: [
        {
          id: "start",
          type: "manualTrigger",
          typeVersion: 1,
          config: {
            inputFields: {
              count: "INTEGER",
              include: "BOOLEAN",
              label: "STRING",
            },
          },
          inputs: {},
        },
        {
          id: "assign",
          type: "capabilityAction",
          typeVersion: 1,
          config: {
            integrationId: "connection",
            revision: 3,
            operation: "support.assignBatch",
          },
          inputs: { assignments: { from: "plan", path: "assignments" } },
        },
        { id: "finish", type: "end", typeVersion: 1, config: {}, inputs: {} },
      ],
      edges: [
        { source: "start", target: "assign" },
        { source: "assign", target: "finish" },
      ],
    },
  };
}
beforeEach(() => {
  vi.spyOn(workflowApi, "version").mockResolvedValue(snapshot());
});
afterEach(() => vi.restoreAllMocks());
function externalSnapshot(): Snapshot {
  const value = snapshot();
  const graph = value.definition as CanvasGraph;
  graph.nodes[0]!.config = { inputFields: {} };
  graph.nodes[1]!.type = "configuredHttp";
  graph.nodes[1]!.config = {
    integrationId: "connection",
    revision: 3,
    operation: "operation",
  };
  graph.nodes[1]!.inputs = {
    pathParameters: { literal: {} },
    fields: { literal: { message: "Approved payload" } },
  };
  return value;
}
it("requires explicit external acknowledgement and clears it when the dispatch intent changes", async () => {
  vi.mocked(workflowApi.version).mockResolvedValue(externalSnapshot());
  const start = vi
    .spyOn(executionApi, "start")
    .mockRejectedValue(new Error("Controlled submission failure"));
  show();
  await screen.findByRole("heading", {
    name: "Start external workflow execution?",
  });
  const button = screen.getByRole("button", {
    name: "Confirm external execution",
  });
  expect(button).toBeDisabled();
  expect(
    screen.getByText(/External effects cannot be undone/),
  ).toBeInTheDocument();
  const acknowledgement = screen.getByLabelText(
    "I acknowledge that these configured operations may contact an external provider and create effects.",
  );
  fireEvent.click(acknowledgement);
  expect(button).toBeEnabled();
  fireEvent.change(screen.getByLabelText("Start delay in seconds"), {
    target: { value: "5" },
  });
  expect(acknowledgement).not.toBeChecked();
  expect(button).toBeDisabled();
  expect(start).not.toHaveBeenCalled();
  fireEvent.click(acknowledgement);
  fireEvent.click(button);
  await waitFor(() => expect(start).toHaveBeenCalledTimes(1));
  expect(start.mock.calls[0]?.[3]).toBe(5);
});
it("recognizes configured HTTP without claiming that a read always requires human approval", () => {
  const value = externalSnapshot();
  const before = JSON.stringify(value);
  const preview = publishedStart(value.definition);
  expect(preview.external).toBe(true);
  expect(preview.unsupported).toBe(false);
  expect(preview.effects[0]).toMatchObject({
    external: true,
    protected: false,
    operation: "operation",
    connection: "connection",
    revision: 3,
  });
  expect(JSON.stringify(value)).toBe(before);
});
function show() {
  render(
    <MemoryRouter initialEntries={["/start"]}>
      <Routes>
        <Route
          path="/start"
          element={
            <ExecutionStart
              organization="org"
              workflow="workflow"
              version="published"
              schemaVersion={2}
              permissions={["execution.run", "execution.read"]}
            />
          }
        />
        <Route
          path="/organizations/org/executions/created"
          element={<h1>Persisted execution</h1>}
        />
      </Routes>
    </MemoryRouter>,
  );
  fireEvent.click(
    screen.getByRole("button", { name: "Run published version" }),
  );
}
it("loads the pinned published snapshot and requires exact typed inputs before confirmation", async () => {
  const start = vi
    .spyOn(executionApi, "start")
    .mockResolvedValue({ id: "created" } as Awaited<
      ReturnType<typeof executionApi.start>
    >);
  show();
  expect(
    screen.getByRole("button", { name: "Confirm internal execution" }),
  ).toBeDisabled();
  const count = await screen.findByLabelText("count (INTEGER)");
  expect(workflowApi.version).toHaveBeenCalledWith(
    "org",
    "workflow",
    "published",
  );
  expect(screen.getByRole("listitem")).toHaveTextContent(
    "distinct-human approval required",
  );
  expect(screen.getByText(/Published version 7/)).toBeInTheDocument();
  fireEvent.change(count, { target: { value: "3" } });
  fireEvent.change(screen.getByLabelText("include (BOOLEAN)"), {
    target: { value: "false" },
  });
  fireEvent.change(screen.getByLabelText("label (STRING)"), {
    target: { value: "High priority" },
  });
  fireEvent.click(
    screen.getByRole("button", { name: "Confirm internal execution" }),
  );
  await screen.findByRole("heading", { name: "Persisted execution" });
  expect(start).toHaveBeenCalledWith(
    "org",
    "workflow",
    "published",
    0,
    expect.any(String),
    { count: 3, include: false, label: "High priority" },
  );
});
it("fails closed on unavailable or mismatched published preview and allows explicit reload", async () => {
  vi.mocked(workflowApi.version).mockRejectedValueOnce(
    new Error("Unavailable"),
  );
  show();
  await screen.findByRole("alert");
  expect(
    screen.getByRole("button", { name: "Confirm internal execution" }),
  ).toBeDisabled();
  fireEvent.click(
    screen.getByRole("button", { name: "Reload published preview" }),
  );
  await screen.findByLabelText("count (INTEGER)");
  expect(workflowApi.version).toHaveBeenCalledTimes(2);
});
it("rejects a preview for a different immutable version", async () => {
  const wrong = snapshot();
  wrong.version.id = "other";
  vi.mocked(workflowApi.version).mockResolvedValue(wrong);
  show();
  await screen.findByRole("alert");
  expect(
    screen.getByRole("button", { name: "Confirm internal execution" }),
  ).toBeDisabled();
  expect(screen.queryByLabelText("count (INTEGER)")).not.toBeInTheDocument();
});
it("blocks webhook-only and unsupported runtime definitions before submission", async () => {
  const wrong = snapshot();
  const definition = wrong.definition as { nodes: { type: string }[] };
  definition.nodes[0]!.type = "webhookTrigger";
  vi.mocked(workflowApi.version).mockResolvedValue(wrong);
  show();
  await screen.findByText(/cannot be started manually/);
  expect(
    screen.getByRole("button", { name: "Confirm internal execution" }),
  ).toBeDisabled();
  expect(
    screen.getByText(/current runtime cannot execute/),
  ).toBeInTheDocument();
});
it("retains the submission key across unavailability but changes it when edited inputs change", async () => {
  const start = vi
    .spyOn(executionApi, "start")
    .mockRejectedValue(new Error("Unavailable"));
  show();
  fireEvent.change(await screen.findByLabelText("count (INTEGER)"), {
    target: { value: "1" },
  });
  fireEvent.change(screen.getByLabelText("include (BOOLEAN)"), {
    target: { value: "true" },
  });
  fireEvent.click(
    screen.getByRole("button", { name: "Confirm internal execution" }),
  );
  await screen.findByRole("alert");
  fireEvent.click(
    screen.getByRole("button", { name: "Confirm internal execution" }),
  );
  await waitFor(() => expect(start).toHaveBeenCalledTimes(2));
  expect(start.mock.calls[0]?.[4]).toBe(start.mock.calls[1]?.[4]);
  await screen.findByRole("alert");
  fireEvent.change(screen.getByLabelText("count (INTEGER)"), {
    target: { value: "2" },
  });
  fireEvent.click(
    screen.getByRole("button", { name: "Confirm internal execution" }),
  );
  await waitFor(() => expect(start).toHaveBeenCalledTimes(3));
  expect(start.mock.calls[2]?.[4]).not.toBe(start.mock.calls[1]?.[4]);
});
it("validates primitive input values without coercion or loss of integer precision", () => {
  const fields: Parameters<typeof triggerValues>[0] = [
    ["amount", "INTEGER"],
    ["flag", "BOOLEAN"],
    ["text", "STRING"],
  ];
  expect(
    triggerValues(fields, { amount: "-2", flag: "false", text: "" }),
  ).toEqual({ amount: -2, flag: false, text: "" });
  for (const amount of ["", "1.5", "1e2", " 2", "9007199254740993"])
    expect(triggerValues(fields, { amount, flag: "true" })).toBeNull();
  expect(triggerValues(fields, { amount: "2", flag: "1" })).toBeNull();
  expect(
    triggerValues(fields, { amount: "2", flag: "true", text: "bad\nline" }),
  ).toBeNull();
});
it("describes saved bindings and protects support writes without inventing live results", () => {
  const before = snapshot();
  const serialized = JSON.stringify(before);
  const preview = publishedStart(before.definition);
  expect(preview.effects[0]).toMatchObject({
    operation: "support.assignBatch",
    protected: true,
    connection: "connection",
    revision: 3,
  });
  expect(preview.effects[0]!.inputs).toEqual({
    assignments: { from: "plan", path: "assignments" },
  });
  expect(JSON.stringify(before)).toBe(serialized);
});
it("treats schema fields named constructor or toString as ordinary own inputs", () => {
  const fields: Parameters<typeof triggerValues>[0] = [
    ["constructor", "STRING"],
    ["toString", "BOOLEAN"],
  ];
  expect(triggerValues(fields, {})).toBeNull();
  expect(
    triggerValues(fields, { constructor: "Ordinary text", toString: "false" }),
  ).toEqual({ constructor: "Ordinary text", toString: false });
});
