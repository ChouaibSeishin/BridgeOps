import { useState } from "react";
import { Link, useNavigate, useParams } from "react-router-dom";
import { useQuery } from "@tanstack/react-query";
import { failureMessage, HttpFailure } from "../../lib/session";
import type { User } from "../../lib/session";
import { workflowApi } from "./api";
import type { WorkflowDetail, Validation } from "./model";
import { useWorkflowAccess } from "./useWorkflowAccess";
import { Problem, Pager, ValidationResults } from "./Feedback";
import { GraphEditor, ReadOnlyDefinition } from "./GraphEditor";

import { ExecutionStart } from "../executions/ExecutionStart";

function validationDetails(value: unknown): value is Validation {
  if (!value || typeof value !== "object") return false;
  const result = value as Validation;
  return (
    typeof result.valid === "boolean" &&
    Array.isArray(result.errors) &&
    result.errors.length <= 100 &&
    result.errors.every(
      (error) =>
        error &&
        (error.nodeId === null ||
          (typeof error.nodeId === "string" &&
            /^[A-Za-z][A-Za-z0-9_-]{0,63}$/.test(error.nodeId))) &&
        typeof error.message === "string" &&
        error.message.length <= 500 &&
        typeof error.path === "string" &&
        error.path.length <= 500 &&
        typeof error.code === "string" &&
        error.code.length <= 64,
    )
  );
}
export function WorkflowEditor({ user }: { user: User }) {
  const { id = "", workflowId = "" } = useParams();
  const access = useWorkflowAccess(user.id, id);
  const readable = access.data?.permissions.includes("workflow.read") ?? false;
  const draft = useQuery({
    queryKey: [user.id, "workflow-editor", id, workflowId],
    queryFn: () => workflowApi.get(id, workflowId),
    enabled: readable,
    retry: false,
    refetchOnWindowFocus: false,
    gcTime: 0,
  });
  if (access.isPending)
    return <p role="status">Checking organization access…</p>;
  if (access.isError)
    return <Problem error={access.error} retry={() => void access.refetch()} />;
  if (!readable)
    return (
      <p role="alert">
        Workflow reading is not permitted in this organization.
      </p>
    );
  if (draft.isPending) return <p role="status">Loading workflow draft…</p>;
  if (draft.isError)
    return <Problem error={draft.error} retry={() => void draft.refetch()} />;
  return (
    <DraftWorkspace
      key={`${id}/${workflowId}`}
      organization={id}
      user={user}
      initial={draft.data}
      permissions={access.data.permissions}
      reloadAccess={async () => {
        await access.refetch();
      }}
    />
  );
}
function DraftWorkspace({
  organization,
  user,
  initial,
  permissions,
  reloadAccess,
}: {
  organization: string;
  user: User;
  initial: WorkflowDetail;
  permissions: string[];
  reloadAccess: () => Promise<void>;
}) {
  const navigate = useNavigate();
  const [saved, setSaved] = useState(initial);
  const [name, setName] = useState(initial.workflow.name);
  const [description, setDescription] = useState(initial.workflow.description);
  const [definition, setDefinition] = useState(initial.definition);
  const [validation, setValidation] = useState<Validation>();
  const [busy, setBusy] = useState("");
  const [error, setError] = useState("");
  const [notice, setNotice] = useState("");
  const [confirmation, setConfirmation] = useState<
    "publish" | "archive" | "reload" | null
  >(null);
  const id = saved.workflow.id;
  const revision = saved.workflow.revision;
  const archived = saved.workflow.status === "ARCHIVED";
  const edit = permissions.includes("workflow.edit");
  const publish = permissions.includes("workflow.publish");
  const dirty =
    name !== saved.workflow.name ||
    description !== saved.workflow.description ||
    JSON.stringify(definition) !== JSON.stringify(saved.definition);
  const frozen = Boolean(busy) || confirmation !== null;
  function adopt(detail: WorkflowDetail) {
    setSaved(detail);
    setName(detail.workflow.name);
    setDescription(detail.workflow.description);
    setDefinition(detail.definition);
    setValidation(undefined);
  }
  async function perform(label: string, work: () => Promise<void>) {
    setBusy(label);
    setError("");
    setNotice("");
    try {
      await work();
    } catch (failure) {
      setError(failureMessage(failure));
      if (
        failure instanceof HttpFailure &&
        failure.status === 422 &&
        validationDetails(failure.details)
      )
        setValidation(failure.details);
    } finally {
      setBusy("");
      setConfirmation(null);
    }
  }
  async function reload() {
    const [detail] = await Promise.all([
      workflowApi.get(organization, id),
      reloadAccess(),
    ]);
    adopt(detail);
    setNotice("Reloaded the current saved draft and access.");
  }
  async function confirm() {
    if (confirmation === "reload") await perform("Reloading…", reload);
    if (confirmation === "publish")
      await perform("Publishing…", async () => {
        const snapshot = await workflowApi.publish(organization, id, revision);
        setNotice(
          `Published version ${snapshot.version.versionNumber}. This definition has not executed.`,
        );
        adopt(await workflowApi.get(organization, id));
      });
    if (confirmation === "archive")
      await perform("Archiving…", async () => {
        await workflowApi.archive(organization, id, revision);
        adopt(await workflowApi.get(organization, id));
        setNotice("Archived. Published history is preserved.");
      });
  }
  return (
    <>
      <div className="page-heading">
        <div>
          <div className="eyebrow">WORKFLOW DEFINITION</div>
          <h1>{saved.workflow.name}</h1>
          <p>
            Organization <span className="mono">{organization}</span>
          </p>
        </div>
        <Link
          className="button"
          to={`/organizations/${organization}/workflows`}
        >
          All workflows
        </Link>
      </div>
      <div className="workflow-revision">
        <span className="status-badge">
          {archived ? "Archived" : "Editable draft"}
        </span>
        <strong>Saved revision {revision}</strong>
        <span role="status">
          {dirty ? "Unsaved changes" : "All changes saved"}
        </span>
        {saved.currentPublishedVersion && (
          <Link
            to={`/organizations/${organization}/workflows/${id}/versions/${saved.currentPublishedVersion.id}`}
          >
            Current published version{" "}
            {saved.currentPublishedVersion.versionNumber}
          </Link>
        )}
      </div>
      <p className="phase-note">
        Publication freezes a snapshot without running it. Explicit execution
        supports internal records; approval processing remains Phase 4.
      </p>
      {!archived && (
        <ExecutionStart
          organization={organization}
          workflow={id}
          version={saved.currentPublishedVersion?.id ?? null}
          permissions={permissions}
          disabled={dirty || frozen}
        />
      )}
      {error && (
        <div className="error-panel" role="alert">
          <p>{error}</p>
          <p>
            Your local edits are preserved. Reload explicitly before retrying a
            revision conflict.
          </p>
        </div>
      )}
      {notice && (
        <p className="operation-notice" role="status">
          {notice}
        </p>
      )}
      {busy && (
        <p role="status" aria-live="polite">
          {busy}
        </p>
      )}
      {!edit && (
        <p className="muted">
          You can read and validate this definition. Editing requires
          workflow.edit.
        </p>
      )}
      {archived && (
        <p className="muted">
          This workflow is archived. Its history remains readable; authorized
          editors may duplicate its saved draft.
        </p>
      )}
      <section className="data-panel">
        <h2>Draft metadata</h2>
        <form
          onSubmit={(event) => {
            event.preventDefault();
            void perform("Saving…", async () => {
              adopt(
                await workflowApi.save(
                  organization,
                  id,
                  revision,
                  name,
                  description,
                  definition,
                ),
              );
              setNotice(
                "Draft saved. Validate the persisted revision before publishing.",
              );
            });
          }}
        >
          <label>
            Workflow name
            <input
              value={name}
              onChange={(event) => setName(event.target.value)}
              required
              maxLength={120}
              disabled={!edit || archived || frozen}
            />
          </label>
          <label>
            Description
            <textarea
              value={description}
              onChange={(event) => setDescription(event.target.value)}
              rows={3}
              maxLength={2000}
              disabled={!edit || archived || frozen}
            />
          </label>
          {edit && !archived && (
            <button className="button primary" disabled={!dirty || frozen}>
              Save draft
            </button>
          )}
        </form>
      </section>
      <GraphEditor
        definition={definition}
        change={setDefinition}
        disabled={!edit || archived || frozen}
        errors={validation?.errors ?? []}
      />
      <div className="action-row workflow-actions">
        <button
          className="button"
          disabled={dirty || frozen}
          onClick={() =>
            void perform("Validating saved revision…", async () =>
              setValidation(
                await workflowApi.validate(organization, id, revision),
              ),
            )
          }
        >
          Validate saved draft
        </button>
        {publish && !archived && (
          <button
            className="button primary"
            disabled={dirty || frozen}
            onClick={() => setConfirmation("publish")}
          >
            Publish draft
          </button>
        )}
        {edit && (
          <button
            className="button"
            disabled={dirty || frozen}
            onClick={() =>
              void perform("Duplicating…", async () => {
                const copy = await workflowApi.duplicate(
                  organization,
                  id,
                  revision,
                );
                navigate(
                  `/organizations/${organization}/workflows/${copy.workflow.id}`,
                );
              })
            }
          >
            Duplicate saved draft
          </button>
        )}
        {edit && !archived && (
          <button
            className="button danger"
            disabled={dirty || frozen}
            onClick={() => setConfirmation("archive")}
          >
            Archive workflow
          </button>
        )}
        <button
          className="button"
          disabled={frozen}
          onClick={() =>
            dirty
              ? setConfirmation("reload")
              : void perform("Reloading…", reload)
          }
        >
          Reload saved draft
        </button>
      </div>
      {dirty && (
        <p className="muted">
          Save changes before validating, publishing, duplicating or archiving
          the persisted revision. Reload asks before discarding unsaved edits.
        </p>
      )}
      {confirmation && (
        <section
          className="confirmation"
          role="dialog"
          aria-labelledby="workflow-confirm-title"
        >
          <h2 id="workflow-confirm-title">
            {confirmation === "publish"
              ? `Publish revision ${revision}?`
              : confirmation === "archive"
                ? `Archive revision ${revision}?`
                : "Discard unsaved changes?"}
          </h2>
          <p>
            {confirmation === "publish"
              ? "Publication validates the saved revision and freezes an immutable version. It does not execute anything."
              : confirmation === "archive"
                ? "Archive preserves all published versions and makes the draft read-only."
                : "Reload will replace local edits with the current server draft."}
          </p>
          <div className="action-row">
            <button
              className="button primary"
              autoFocus
              disabled={Boolean(busy)}
              onClick={() => void confirm()}
            >
              {confirmation === "publish"
                ? "Confirm publication"
                : confirmation === "archive"
                  ? "Confirm archive"
                  : "Discard edits and reload"}
            </button>
            <button
              className="button"
              disabled={Boolean(busy)}
              onClick={() => setConfirmation(null)}
            >
              Cancel
            </button>
          </div>
        </section>
      )}
      <ValidationResults result={validation} />
      <VersionHistory
        key={saved.currentPublishedVersion?.id ?? "unpublished"}
        user={user.id}
        organization={organization}
        id={id}
        current={saved.currentPublishedVersion?.id ?? ""}
      />
    </>
  );
}
function VersionHistory({
  user,
  organization,
  id,
  current,
}: {
  user: string;
  organization: string;
  id: string;
  current: string;
}) {
  const [page, setPage] = useState(0);
  const history = useQuery({
    queryKey: [user, "workflow-versions", organization, id, current, page],
    queryFn: () => workflowApi.versions(organization, id, page),
    retry: false,
  });
  return (
    <section className="data-panel">
      <div className="section-heading">
        <h2>Published versions</h2>
        {history.data && <span>{history.data.totalElements} total</span>}
      </div>
      {history.isPending ? (
        <p role="status">Loading published versions…</p>
      ) : history.isError ? (
        <Problem error={history.error} retry={() => void history.refetch()} />
      ) : (
        <>
          {history.data.items.length === 0 ? (
            <p>
              No published versions. Save and validate a draft, then publish if
              authorized.
            </p>
          ) : (
            <ol className="version-history">
              {history.data.items.map((version) => (
                <li key={version.id}>
                  <Link
                    to={`/organizations/${organization}/workflows/${id}/versions/${version.id}`}
                  >
                    Inspect version {version.versionNumber}
                  </Link>
                  <p>
                    {version.name} · source revision {version.sourceRevision} ·
                    schema {version.schemaVersion} / canonicalization{" "}
                    {version.canonicalizationVersion}
                  </p>
                  <small>
                    Published{" "}
                    <time dateTime={version.publishedAt}>
                      {new Date(version.publishedAt).toLocaleString()}
                    </time>{" "}
                    by <span className="mono">{version.publishedBy}</span>
                  </small>
                  <code className="definition-hash">
                    SHA-256 {version.definitionHash}
                  </code>
                </li>
              ))}
            </ol>
          )}
          <Pager page={page} pages={history.data.totalPages} change={setPage} />
        </>
      )}
    </section>
  );
}
export function PublishedVersion({ user }: { user: User }) {
  const { id = "", workflowId = "", versionId = "" } = useParams();
  const snapshot = useQuery({
    queryKey: [user.id, "workflow-version", id, workflowId, versionId],
    queryFn: () => workflowApi.version(id, workflowId, versionId),
    retry: false,
  });
  if (snapshot.isPending)
    return <p role="status">Loading published snapshot…</p>;
  if (snapshot.isError)
    return (
      <Problem error={snapshot.error} retry={() => void snapshot.refetch()} />
    );
  const version = snapshot.data.version;
  return (
    <>
      <div className="page-heading">
        <div>
          <div className="eyebrow">IMMUTABLE SNAPSHOT</div>
          <h1>Published version {version.versionNumber}</h1>
          <p>{version.name}</p>
        </div>
        <Link
          className="button"
          to={`/organizations/${id}/workflows/${workflowId}`}
        >
          Return to draft
        </Link>
      </div>
      <section className="data-panel">
        <h2>Publication metadata</h2>
        <p>{version.description || "No description"}</p>
        <p>
          Source revision {version.sourceRevision} · graph schema{" "}
          {version.schemaVersion} · canonicalization{" "}
          {version.canonicalizationVersion}
        </p>
        <p>
          Published{" "}
          <time dateTime={version.publishedAt}>
            {new Date(version.publishedAt).toLocaleString()}
          </time>{" "}
          by <span className="mono">{version.publishedBy}</span>
        </p>
        <code className="definition-hash">
          SHA-256 {version.definitionHash}
        </code>
      </section>
      <ReadOnlyDefinition definition={snapshot.data.definition} />
    </>
  );
}
