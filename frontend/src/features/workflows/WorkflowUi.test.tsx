import {
  render,
  screen,
  fireEvent,
  waitFor,
  within,
  act,
} from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import { beforeEach, afterEach, it, expect, vi } from "vitest";
import { session, HttpFailure } from "../../lib/session";
import { WorkflowEditor, PublishedVersion } from "./WorkflowEditor";
import { WorkflowList } from "./WorkflowList";
import { workflowApi } from "./api";
import { emptyGraph, addNode } from "./model";
import type { Graph, WorkflowDetail, VersionSummary, Snapshot } from "./model";

const user = {
  id: "user",
  email: "fixture@example.invalid",
  displayName: "Fixture",
};
const graph = addNode(emptyGraph(), "syntheticAction", "action");
const detail = (): WorkflowDetail => ({
  workflow: {
    id: "workflow",
    name: "Ticket triage",
    description: "Definition only",
    status: "ACTIVE",
    revision: 0,
    currentPublishedVersionId: null,
    updatedAt: "2026-10-16T12:00:00Z",
  },
  definition: structuredClone(graph),
  currentPublishedVersion: null,
});
const version: VersionSummary = {
  id: "version",
  workflowId: "workflow",
  versionNumber: 1,
  sourceRevision: 0,
  schemaVersion: 1,
  canonicalizationVersion: 1,
  name: "Original title",
  description: "Frozen metadata",
  definitionHash: "a".repeat(64),
  publishedBy: "synthetic-actor",
  publishedAt: "2026-10-16T12:00:00Z",
};
const empty = { items: [], page: 0, size: 10, totalElements: 0, totalPages: 0 };
beforeEach(() => {
  vi.spyOn(session, "request").mockResolvedValue({
    membershipId: "member",
    permissions: ["workflow.read", "workflow.edit", "workflow.publish"],
  });
  vi.spyOn(workflowApi, "get").mockResolvedValue(detail());
  vi.spyOn(workflowApi, "versions").mockResolvedValue(empty);
  vi.spyOn(workflowApi, "list").mockResolvedValue(empty);
});
afterEach(() => vi.restoreAllMocks());
function show(route = "/organizations/org/workflows/workflow") {
  render(
    <QueryClientProvider
      client={
        new QueryClient({
          defaultOptions: { queries: { retry: false, gcTime: 0 } },
        })
      }
    >
      <MemoryRouter initialEntries={[route]}>
        <Routes>
          <Route
            path="/organizations/:id/workflows/:workflowId"
            element={<WorkflowEditor user={user} />}
          />
          <Route
            path="/organizations/:id/workflows"
            element={<WorkflowList user={user} />}
          />
          <Route
            path="/organizations/:id/workflows/:workflowId/versions/:versionId"
            element={<PublishedVersion user={user} />}
          />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  );
}
async function ready() {
  await screen.findByRole("heading", { name: "Ticket triage" });
}
it("blocks browser saves of API-created large integers and displays exact original draft bytes", async () => {
  const saved = detail(),
    definitionJson = JSON.stringify({
      ...graph,
      schemaVersion: 2,
      unrecognized: { value: 0 },
    }).replace('"value":0', '"value":9223372036854775807');
  saved.definition = JSON.parse(definitionJson);
  saved.definitionJson = definitionJson;
  vi.mocked(workflowApi.get).mockResolvedValue(saved);
  const save = vi.spyOn(workflowApi, "save");
  show();
  await ready();
  expect(screen.getByRole("button", { name: "Save draft" })).toBeDisabled();
  expect(screen.getByLabelText("Workflow name")).toBeDisabled();
  expect(
    screen.getByRole("heading", { name: "Saved draft definition" }),
  ).toBeInTheDocument();
  expect(
    screen.getByLabelText("Saved draft definition JSON").textContent,
  ).toContain("9223372036854775807");
  expect(
    screen.queryByRole("combobox", { name: "Inspect node" }),
  ).not.toBeInTheDocument();
  fireEvent.submit(screen.getByLabelText("Workflow name").closest("form")!);
  expect(save).not.toHaveBeenCalled();
});
it("blocks saving and publication while canvas properties have unapplied invalid edits", async () => {
  vi.stubGlobal(
    "ResizeObserver",
    class {
      observe() {}
      unobserve() {}
      disconnect() {}
    },
  );
  try {
    show();
    await ready();
    fireEvent.click(screen.getByRole("button", { name: "Show visual canvas" }));
    const inspect = await screen.findByRole(
      "combobox",
      { name: "Inspect node" },
      { timeout: 10000 },
    );
    fireEvent.change(inspect, { target: { value: "action" } });
    fireEvent.click(screen.getByText("Advanced JSON"));
    fireEvent.change(
      screen.getByRole("textbox", { name: "Node configuration JSON" }),
      { target: { value: "invalid configuration" } },
    );
    expect(screen.getByRole("button", { name: "Save draft" })).toBeDisabled();
    expect(
      screen.getByRole("button", { name: "Publish draft" }),
    ).toBeDisabled();
    expect(
      screen.getByRole("button", { name: "Reload saved draft" }),
    ).toBeDisabled();
    fireEvent.click(
      screen.getByRole("button", { name: "Discard property edits" }),
    );
    expect(screen.getByRole("button", { name: "Publish draft" })).toBeEnabled();
    expect(screen.getByText("All changes saved")).toBeInTheDocument();
  } finally {
    vi.unstubAllGlobals();
  }
}, 15000);
it("adds and removes a configured node through the accessible palette controls", async () => {
  show();
  await ready();
  fireEvent.change(screen.getByRole("combobox", { name: /^Node type/ }), {
    target: { value: "approval" },
  });
  fireEvent.click(screen.getByRole("button", { name: "Add node before end" }));
  const node = screen.getByRole("group", { name: /^Approval gate/ });
  fireEvent.change(within(node).getByLabelText("Review prompt"), {
    target: { value: "Review the plan" },
  });
  expect(screen.getByText("Unsaved changes")).toBeInTheDocument();
  fireEvent.click(within(node).getByRole("button", { name: "Remove node" }));
  expect(screen.queryByLabelText("Review prompt")).not.toBeInTheDocument();
  expect(screen.getByText("All changes saved")).toBeInTheDocument();
});
it("edits metadata/node configuration and saves the exact graph with the current revision", async () => {
  const save = vi
    .spyOn(workflowApi, "save")
    .mockImplementation(
      async (_org, _id, expected, name, description, definition) => ({
        ...detail(),
        workflow: {
          ...detail().workflow,
          revision: expected + 1,
          name,
          description,
        },
        definition,
      }),
    );
  show();
  await ready();
  fireEvent.change(screen.getByLabelText("Workflow name"), {
    target: { value: "Updated triage" },
  });
  fireEvent.change(screen.getByLabelText("Message"), {
    target: { value: "Record the plan" },
  });
  expect(screen.getByText("Unsaved changes")).toBeInTheDocument();
  expect(
    screen.getByRole("button", { name: "Validate saved draft" }),
  ).toBeDisabled();
  fireEvent.click(screen.getByRole("button", { name: "Save draft" }));
  await screen.findByText("All changes saved");
  expect(save).toHaveBeenCalledWith(
    "org",
    "workflow",
    0,
    "Updated triage",
    "Definition only",
    expect.objectContaining({
      nodes: expect.arrayContaining([
        expect.objectContaining({
          id: "action",
          config: { operation: "record", message: "Record the plan" },
        }),
      ]),
    }),
  );
  expect(screen.getByText("Saved revision 1")).toBeInTheDocument();
});
it("keeps invalid saved configuration correctable without coercing arbitrary objects", async () => {
  const malformed = detail();
  const definition = malformed.definition as Graph;
  const action = definition.nodes.find((node) => node.id === "action");
  if (!action) throw new Error("Fixture node missing");
  action.config.operation = { toString: "invalid", valueOf: null };
  vi.mocked(workflowApi.get).mockResolvedValue(malformed);
  show();
  await ready();
  const operation = within(
    screen.getByRole("group", { name: /Synthetic action/ }),
  ).getByRole("combobox", { name: /^Operation/ });
  expect(operation).toHaveValue("");
  fireEvent.change(operation, { target: { value: "record" } });
  expect(operation).toHaveValue("record");
  expect(screen.getByText("Unsaved changes")).toBeInTheDocument();
});
it("maps server validation to the offending node and field", async () => {
  vi.spyOn(workflowApi, "validate").mockResolvedValue({
    valid: false,
    errors: [
      {
        nodeId: "action",
        path: "$.nodes[1].config.message",
        code: "INVALID_TEXT",
        message: "Provide a bounded message.",
      },
    ],
  });
  show();
  await ready();
  fireEvent.click(screen.getByRole("button", { name: "Validate saved draft" }));
  await screen.findByRole("heading", {
    name: "Saved definition needs changes",
  });
  const node = screen.getByRole("group", { name: /Synthetic action/ });
  expect(within(node).getByLabelText("Message")).toHaveAttribute(
    "aria-describedby",
    "node-action-errors",
  );
  expect(within(node).getByLabelText("Message")).toHaveAttribute(
    "aria-invalid",
    "true",
  );
  expect(
    within(node).getByText("Provide a bounded message."),
  ).toBeInTheDocument();
  expect(
    screen.getByRole("link", { name: "Synthetic action 1" }),
  ).toHaveAttribute("href", "#node-action");
});
it("preserves local edits after 409 and requires explicit discard before reload", async () => {
  const save = vi
    .spyOn(workflowApi, "save")
    .mockRejectedValue(new HttpFailure(409, "STALE_REVISION"));
  show();
  await ready();
  fireEvent.change(screen.getByLabelText("Workflow name"), {
    target: { value: "My local edits" },
  });
  fireEvent.click(screen.getByRole("button", { name: "Save draft" }));
  expect(await screen.findByRole("alert")).toHaveTextContent("record changed");
  expect(screen.getByLabelText("Workflow name")).toHaveValue("My local edits");
  expect(save).toHaveBeenCalledTimes(1);
  vi.mocked(workflowApi.get).mockResolvedValue({
    ...detail(),
    workflow: { ...detail().workflow, name: "Remote edit", revision: 9 },
  });
  fireEvent.click(screen.getByRole("button", { name: "Reload saved draft" }));
  await screen.findByRole("dialog");
  fireEvent.click(screen.getByRole("button", { name: "Cancel" }));
  expect(screen.getByLabelText("Workflow name")).toHaveValue("My local edits");
  fireEvent.click(screen.getByRole("button", { name: "Reload saved draft" }));
  fireEvent.click(
    screen.getByRole("button", { name: "Discard edits and reload" }),
  );
  await waitFor(() =>
    expect(screen.getByLabelText("Workflow name")).toHaveValue("Remote edit"),
  );
  expect(screen.getByText("Saved revision 9")).toBeInTheDocument();
});
it("confirms the exact persisted revision, shows pending publication and refreshes history on success", async () => {
  let complete: ((snapshot: Snapshot) => void) | undefined;
  const publish = vi.spyOn(workflowApi, "publish").mockImplementation(
    () =>
      new Promise((resolve) => {
        complete = resolve;
      }),
  );
  vi.mocked(workflowApi.get)
    .mockResolvedValueOnce(detail())
    .mockResolvedValue({
      ...detail(),
      workflow: {
        ...detail().workflow,
        revision: 1,
        currentPublishedVersionId: version.id,
      },
      currentPublishedVersion: version,
    });
  vi.mocked(workflowApi.versions)
    .mockResolvedValueOnce(empty)
    .mockResolvedValue({
      ...empty,
      items: [version],
      totalElements: 1,
      totalPages: 1,
    });
  show();
  await ready();
  await screen.findByText(/No published versions/);
  fireEvent.click(screen.getByRole("button", { name: "Publish draft" }));
  expect(
    await screen.findByRole("heading", { name: "Publish revision 0?" }),
  ).toBeInTheDocument();
  fireEvent.click(screen.getByRole("button", { name: "Confirm publication" }));
  await screen.findByText("Publishing…");
  expect(
    screen.getByRole("button", { name: "Confirm publication" }),
  ).toBeDisabled();
  expect(publish).toHaveBeenCalledWith("org", "workflow", 0);
  await act(async () => {
    complete?.({ version, definition: graph });
  });
  expect(
    await screen.findByText(
      /Published version 1\. Publication did not start an execution/,
    ),
  ).toBeInTheDocument();
  expect(
    await screen.findByRole("link", { name: "Inspect version 1" }),
  ).toBeInTheDocument();
  expect(screen.getByText("Saved revision 1")).toBeInTheDocument();
  expect(
    screen.getByText(`SHA-256 ${version.definitionHash}`),
  ).toBeInTheDocument();
});
it("renders a 422 publication rejection as node errors without claiming success", async () => {
  vi.spyOn(workflowApi, "publish").mockRejectedValue(
    new HttpFailure(422, "INVALID_WORKFLOW", undefined, undefined, {
      valid: false,
      errors: [
        {
          nodeId: "action",
          path: "$.nodes[1].config.message",
          code: "INVALID_TEXT",
          message: "Enter a message.",
        },
      ],
    }),
  );
  show();
  await ready();
  fireEvent.click(screen.getByRole("button", { name: "Publish draft" }));
  fireEvent.click(screen.getByRole("button", { name: "Confirm publication" }));
  await screen.findByRole("heading", {
    name: "Saved definition needs changes",
  });
  expect(screen.getByRole("alert")).toHaveTextContent(
    "Correct the saved workflow",
  );
  expect(screen.queryByText(/Published version 1\./)).not.toBeInTheDocument();
  expect(screen.getByRole("button", { name: "Publish draft" })).toBeEnabled();
  expect(screen.getByText("Saved revision 0")).toBeInTheDocument();
});
it("shows read-only permissions without editing or publication controls", async () => {
  vi.mocked(session.request).mockResolvedValue({
    membershipId: "member",
    permissions: ["workflow.read"],
  });
  show();
  await ready();
  expect(screen.getByLabelText("Workflow name")).toBeDisabled();
  expect(screen.getByLabelText("Message")).toBeDisabled();
  for (const name of [
    "Save draft",
    "Publish draft",
    "Duplicate saved draft",
    "Archive workflow",
  ])
    expect(screen.queryByRole("button", { name })).not.toBeInTheDocument();
  expect(
    screen.getByRole("button", { name: "Validate saved draft" }),
  ).toBeEnabled();
});
it("offers creation for an authorized empty list and navigates to the real created draft", async () => {
  const create = vi.spyOn(workflowApi, "create").mockResolvedValue(detail());
  show("/organizations/org/workflows");
  await screen.findByRole("heading", { name: "No workflows yet" });
  fireEvent.change(screen.getByLabelText("Workflow name"), {
    target: { value: "My workflow" },
  });
  fireEvent.click(screen.getByRole("button", { name: "Create workflow" }));
  await ready();
  expect(create).toHaveBeenCalledWith("org", "My workflow", "", emptyGraph());
});
it("distinguishes loading/error/retry/empty states and hides creation from a reader", async () => {
  vi.mocked(session.request).mockResolvedValue({
    membershipId: "member",
    permissions: ["workflow.read"],
  });
  let reject: ((failure: unknown) => void) | undefined;
  vi.mocked(workflowApi.list)
    .mockImplementationOnce(
      () =>
        new Promise((_resolve, fail) => {
          reject = fail;
        }),
    )
    .mockResolvedValue(empty);
  show("/organizations/org/workflows");
  await screen.findByText("Loading workflows…");
  await act(async () => {
    reject?.(new HttpFailure(503, "DEPENDENCY_UNAVAILABLE"));
  });
  await screen.findByRole("alert");
  fireEvent.click(screen.getByRole("button", { name: "Try again" }));
  await screen.findByRole("heading", { name: "No workflows yet" });
  expect(
    screen.queryByRole("button", { name: "Create workflow" }),
  ).not.toBeInTheDocument();
  expect(screen.getByText("0 total")).toBeInTheDocument();
});
it("inspects an exact immutable snapshot with frozen metadata and no edit or run controls", async () => {
  const exact = vi
    .spyOn(workflowApi, "version")
    .mockResolvedValue({ version, definition: graph });
  show("/organizations/org/workflows/workflow/versions/version");
  await screen.findByRole("heading", { name: "Published version 1" });
  expect(exact).toHaveBeenCalledWith("org", "workflow", "version");
  expect(screen.getByText("Frozen metadata")).toBeInTheDocument();
  expect(screen.getByLabelText("Published definition JSON")).toHaveTextContent(
    '"operation": "record"',
  );
  for (const name of ["Save draft", "Publish draft", "Run", "Test Run"])
    expect(screen.queryByRole("button", { name })).not.toBeInTheDocument();
  expect(screen.getByRole("link", { name: "Return to draft" })).toHaveAttribute(
    "href",
    "/organizations/org/workflows/workflow",
  );
});

it("keeps exactly one execution start panel after republishing without a page reload", async () => {
  vi.mocked(session.request).mockResolvedValue({
    membershipId: "member",
    permissions: [
      "workflow.read",
      "workflow.edit",
      "workflow.publish",
      "execution.read",
      "execution.run",
    ],
  });
  const initial = detail();
  initial.workflow.revision = 1;
  initial.workflow.currentPublishedVersionId = version.id;
  initial.currentPublishedVersion = version;
  const second = {
    ...version,
    id: "second-version",
    versionNumber: 2,
    sourceRevision: 1,
    definitionHash: "b".repeat(64),
  };
  const updated = {
    ...initial,
    workflow: {
      ...initial.workflow,
      revision: 2,
      currentPublishedVersionId: second.id,
    },
    currentPublishedVersion: second,
  };
  vi.mocked(workflowApi.get)
    .mockResolvedValueOnce(initial)
    .mockResolvedValue(updated);
  vi.spyOn(workflowApi, "publish").mockResolvedValue({
    version: second,
    definition: structuredClone(graph),
  });
  show();
  await ready();
  expect(
    screen.getAllByRole("button", {
      name: "Run published version",
    }),
  ).toHaveLength(1);
  fireEvent.click(screen.getByRole("button", { name: "Publish draft" }));
  fireEvent.click(screen.getByRole("button", { name: "Confirm publication" }));
  await screen.findByRole("link", {
    name: "Current published version 2",
  });
  expect(
    screen.getAllByRole("button", {
      name: "Run published version",
    }),
  ).toHaveLength(1);
  await waitFor(() =>
    expect(
      screen.getByRole("button", {
        name: "Run published version",
      }),
    ).toBeEnabled(),
  );
});
