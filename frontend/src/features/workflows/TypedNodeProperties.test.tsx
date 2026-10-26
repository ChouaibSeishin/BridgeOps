import { useState } from "react";
import {
  cleanup,
  fireEvent,
  render,
  screen,
  waitFor,
  within,
} from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { afterEach, beforeEach, expect, it, vi } from "vitest";
import { WorkflowCanvas } from "./WorkflowCanvas";
import { newV2Graph, type CanvasGraph } from "./canvasModel";
import {
  connectionApi,
  type ConnectionSummary,
} from "../integrations/connectionApi";

beforeEach(() =>
  vi.stubGlobal(
    "ResizeObserver",
    class {
      observe() {}
      unobserve() {}
      disconnect() {}
    },
  ),
);
afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});
function show(graph: CanvasGraph, scope = false) {
  const changed = vi.fn();
  function View() {
    const [value, setValue] = useState(graph);
    return (
      <WorkflowCanvas
        graph={value}
        change={(g) => {
          changed(g);
          setValue(g);
        }}
        scope={scope ? { organization: "org", user: "editor" } : undefined}
      />
    );
  }
  render(
    <QueryClientProvider
      client={
        new QueryClient({
          defaultOptions: { queries: { retry: false, gcTime: 0 } },
        })
      }
    >
      <View />
    </QueryClientProvider>,
  );
  return changed;
}
function inspect(id: string) {
  fireEvent.change(screen.getByRole("combobox", { name: "Inspect node" }), {
    target: { value: id },
  });
}
function apply() {
  fireEvent.click(
    screen.getByRole("button", { name: "Apply node properties" }),
  );
}
it("adds typed trigger fields only on explicit Apply and preserves stable IDs and connections", () => {
  const original = newV2Graph(),
    changed = show(original);
  inspect("start");
  fireEvent.change(screen.getByLabelText("New trigger field name"), {
    target: { value: "limit" },
  });
  fireEvent.click(screen.getByRole("button", { name: "Add trigger field" }));
  fireEvent.change(screen.getByLabelText("limit trigger type"), {
    target: { value: "INTEGER" },
  });
  expect(changed).not.toHaveBeenCalled();
  apply();
  const next = changed.mock.calls[0]![0] as CanvasGraph;
  expect(next.nodes.find((n) => n.id === "start")!.config).toEqual({
    inputFields: { limit: "INTEGER" },
  });
  expect(next.edges).toEqual(original.edges);
});
it("prevents duplicate field names without overwriting an existing mapping", () => {
  const original = newV2Graph();
  original.nodes[0]!.config = { inputFields: { limit: "INTEGER" } };
  const changed = show(original);
  inspect("start");
  fireEvent.change(screen.getByLabelText("New trigger field name"), {
    target: { value: "limit" },
  });
  expect(
    screen.getByRole("button", { name: "Add trigger field" }),
  ).toBeDisabled();
  expect(screen.getByRole("alert")).toHaveTextContent("unique field name");
  expect(changed).not.toHaveBeenCalled();
});
it("rejects an out-of-range typed delay and clears its local invalid edits on discard", () => {
  const original = newV2Graph();
  original.nodes.splice(1, 0, {
    id: "pause",
    type: "delay",
    typeVersion: 1,
    config: { seconds: 0 },
    inputs: {},
  });
  original.edges = [
    { source: "start", target: "pause" },
    { source: "pause", target: "finish" },
  ];
  const changed = show(original);
  inspect("pause");
  fireEvent.change(screen.getByLabelText("Delay seconds"), {
    target: { value: "3601" },
  });
  apply();
  expect(changed).not.toHaveBeenCalled();
  expect(screen.getByRole("alert")).toHaveTextContent(
    "Correct the typed fields",
  );
  fireEvent.click(
    screen.getByRole("button", { name: "Discard property edits" }),
  );
  expect(screen.getByLabelText("Delay seconds")).toHaveValue(0);
  expect(
    screen.getByRole("button", { name: "Apply node properties" }),
  ).toBeDisabled();
});
it("retains invalid structured literal text, blocks Apply, then restores the original value on discard", () => {
  const original = newV2Graph();
  original.nodes.splice(1, 0, {
    id: "build",
    type: "transform",
    typeVersion: 1,
    config: {},
    inputs: { fields: { literal: { message: "original" } } },
  });
  original.edges = [
    { source: "start", target: "build" },
    { source: "build", target: "finish" },
  ];
  const changed = show(original);
  inspect("build");
  fireEvent.change(screen.getByLabelText("Input fields literal JSON"), {
    target: { value: "{invalid-private-fixture" },
  });
  apply();
  expect(changed).not.toHaveBeenCalled();
  expect(screen.getByLabelText("Input fields literal JSON")).toHaveValue(
    "{invalid-private-fixture",
  );
  fireEvent.click(
    screen.getByRole("button", { name: "Discard property edits" }),
  );
  expect(screen.getByLabelText("Input fields literal JSON")).toHaveValue(
    JSON.stringify({ message: "original" }, null, 2),
  );
});
it("maps a named transform field from a guaranteed trigger output using friendly source labels", () => {
  const original = newV2Graph();
  original.nodes[0]!.config = { inputFields: { message: "STRING" } };
  original.nodes.splice(1, 0, {
    id: "build",
    type: "transform",
    typeVersion: 1,
    config: {},
    inputs: { message: { literal: "" } },
  });
  original.edges = [
    { source: "start", target: "build" },
    { source: "build", target: "finish" },
  ];
  const changed = show(original);
  inspect("build");
  fireEvent.change(screen.getByLabelText("Input message mapping"), {
    target: { value: "source" },
  });
  const source = screen.getByLabelText("Input message source");
  expect(
    within(source).getByRole("option", { name: "Manual trigger" }),
  ).toHaveValue("start");
  fireEvent.change(source, { target: { value: "start" } });
  fireEvent.change(screen.getByLabelText("Input message output field"), {
    target: { value: "message" },
  });
  apply();
  expect(
    (changed.mock.calls[0]![0] as CanvasGraph).nodes.find(
      (n) => n.id === "build",
    )!.inputs,
  ).toEqual({ message: { from: "start", path: "message" } });
});
it("inspects registered connection metadata, requires explicit revision updates, and never returns credentials", async () => {
  const id = "11111111-1111-4111-8111-111111111111",
    connection: ConnectionSummary = {
      id,
      name: "Support directory",
      description: "",
      type: "SUPPORT",
      revision: 2,
      implementationVersion: 1,
      enabled: true,
      updatedAt: "2026-10-26T00:00:00Z",
      health: {
        status: "UNKNOWN",
        code: null,
        checkedAt: null,
        revision: null,
      },
    };
  vi.spyOn(connectionApi, "list").mockResolvedValue({
    items: [connection],
    page: 0,
    size: 10,
    totalElements: 1,
    totalPages: 1,
  });
  vi.spyOn(connectionApi, "get").mockResolvedValue(connection);
  vi.spyOn(connectionApi, "operations").mockResolvedValue([
    {
      id: "list",
      classification: "READ",
      requiresApproval: false,
      requestFields: {
        limit: "INTEGER",
        priorities: "ARRAY",
        statuses: "ARRAY",
      },
      responseFields: { tickets: "ARRAY", count: "INTEGER" },
    },
  ]);
  const original = newV2Graph();
  original.nodes.splice(1, 0, {
    id: "read",
    type: "capabilityAction",
    typeVersion: 1,
    config: { integrationId: id, revision: 1, operation: "support.list" },
    inputs: {
      limit: { literal: 10 },
      priorities: { literal: ["HIGH"] },
      statuses: { literal: ["OPEN"] },
    },
  });
  original.edges = [
    { source: "start", target: "read" },
    { source: "read", target: "finish" },
  ];
  const changed = show(original, true);
  inspect("read");
  await screen.findByRole("button", {
    name: "Use current connection revision",
  });
  expect(changed).not.toHaveBeenCalled();
  expect(
    screen.getByRole("button", { name: "Apply node properties" }),
  ).toBeDisabled();
  fireEvent.click(
    screen.getByRole("button", { name: "Use current connection revision" }),
  );
  apply();
  await waitFor(() => expect(changed).toHaveBeenCalledTimes(1));
  expect(
    (changed.mock.calls[0]![0] as CanvasGraph).nodes.find(
      (n) => n.id === "read",
    )!.config.revision,
  ).toBe(2);
});

function restMetadata() {
  const connection: ConnectionSummary = {
    id: "11111111-1111-4111-8111-111111111111",
    name: "Controlled provider",
    description: "",
    type: "REST",
    revision: 0,
    implementationVersion: 1,
    enabled: true,
    updatedAt: "2026-10-26T00:00:00Z",
    health: { status: "UNKNOWN", code: null, checkedAt: null, revision: null },
  };
  vi.spyOn(connectionApi, "list").mockResolvedValue({
    items: [connection],
    page: 0,
    size: 10,
    totalElements: 1,
    totalPages: 1,
  });
  vi.spyOn(connectionApi, "get").mockResolvedValue(connection);
  vi.spyOn(connectionApi, "operations").mockResolvedValue([
    {
      id: "operation",
      classification: "WRITE",
      requiresApproval: true,
      requestFields: {
        message: "STRING",
        amount: "INTEGER",
        enabled: "BOOLEAN",
      },
      responseFields: { message: "STRING", count: "INTEGER" },
      pathParameters: ["ticketId"],
    },
  ]);
  vi.spyOn(connectionApi, "configuration").mockRejectedValue(
    new Error("Managed credential configuration is not an editor dependency"),
  );
  return connection;
}
it("edits only declared REST literal fields and path parameters with typed controls", async () => {
  const connection = restMetadata(),
    original = newV2Graph();
  original.nodes.splice(1, 0, {
    id: "http",
    type: "configuredHttp",
    typeVersion: 1,
    config: {
      integrationId: connection.id,
      revision: 0,
      operation: "operation",
    },
    inputs: { fields: { literal: {} }, pathParameters: { literal: {} } },
  });
  original.edges = [
    { source: "start", target: "http" },
    { source: "http", target: "finish" },
  ];
  const changed = show(original, true);
  inspect("http");
  await screen.findByLabelText("fields.message (STRING)");
  fireEvent.change(screen.getByLabelText("fields.message (STRING)"), {
    target: { value: "Reviewed message" },
  });
  fireEvent.change(screen.getByLabelText("fields.amount (INTEGER)"), {
    target: { value: "42" },
  });
  fireEvent.change(screen.getByLabelText("fields.enabled (BOOLEAN)"), {
    target: { value: "false" },
  });
  fireEvent.change(screen.getByLabelText("pathParameters.ticketId (STRING)"), {
    target: { value: "ticket-1" },
  });
  fireEvent.change(screen.getByLabelText("fields.enabled (BOOLEAN)"), {
    target: { value: "" },
  });
  apply();
  expect(changed).not.toHaveBeenCalled();
  fireEvent.change(screen.getByLabelText("fields.enabled (BOOLEAN)"), {
    target: { value: "false" },
  });
  apply();
  const inputs = (changed.mock.calls[0]![0] as CanvasGraph).nodes.find(
    (n) => n.id === "http",
  )!.inputs;
  expect(inputs).toEqual({
    fields: {
      literal: { message: "Reviewed message", amount: 42, enabled: false },
    },
    pathParameters: { literal: { ticketId: "ticket-1" } },
  });
  expect(connectionApi.configuration).not.toHaveBeenCalled();
});
it("offers only actual projected response fields from a revision-matching earlier REST node", async () => {
  const connection = restMetadata(),
    original = newV2Graph();
  original.nodes.splice(
    1,
    0,
    {
      id: "http",
      type: "configuredHttp",
      typeVersion: 1,
      config: {
        integrationId: connection.id,
        revision: 0,
        operation: "operation",
      },
      inputs: {},
    },
    {
      id: "build",
      type: "transform",
      typeVersion: 1,
      config: {},
      inputs: { message: { from: "http", path: "response.message" } },
    },
  );
  original.edges = [
    { source: "start", target: "http" },
    { source: "http", target: "build" },
    { source: "build", target: "finish" },
  ];
  show(original, true);
  inspect("build");
  const field = await screen.findByLabelText("Input message output field");
  await within(field).findByRole("option", { name: "response.count" });
  expect(
    within(field)
      .getAllByRole("option")
      .map((option) => option.getAttribute("value")),
  ).toEqual(["", "response.message", "response.count"]);
  expect(connectionApi.get).toHaveBeenCalledWith("org", connection.id);
  expect(connectionApi.configuration).not.toHaveBeenCalled();
});
it("keeps saved bindings intact and omits private error details when connection metadata is denied", async () => {
  const connection = restMetadata(),
    original = newV2Graph();
  vi.mocked(connectionApi.list).mockRejectedValue(
    new Error("private-provider-fixture"),
  );
  vi.mocked(connectionApi.get).mockRejectedValue(
    new Error("private-provider-fixture"),
  );
  vi.mocked(connectionApi.operations).mockRejectedValue(
    new Error("private-provider-fixture"),
  );
  original.nodes.splice(1, 0, {
    id: "http",
    type: "configuredHttp",
    typeVersion: 1,
    config: {
      integrationId: connection.id,
      revision: 7,
      operation: "operation",
    },
    inputs: {
      fields: { literal: { message: "retained" } },
      pathParameters: { literal: {} },
    },
  });
  original.edges = [
    { source: "start", target: "http" },
    { source: "http", target: "finish" },
  ];
  const changed = show(original, true);
  inspect("http");
  await screen.findByText(
    "The saved connection cannot be inspected. Its reference has been retained.",
  );
  expect(changed).not.toHaveBeenCalled();
  expect(
    screen.queryByText(/private-provider-fixture/),
  ).not.toBeInTheDocument();
  expect(
    screen.getByRole("textbox", { name: "Node configuration JSON" }),
  ).toHaveValue(
    JSON.stringify(
      original.nodes.find((n) => n.id === "http")!.config,
      null,
      2,
    ),
  );
});
