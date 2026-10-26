import { useState, type FormEvent } from "react";
import { Link, useParams, useSearchParams } from "react-router-dom";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import type { User } from "../../lib/session";
import { useWorkflowAccess } from "../workflows/useWorkflowAccess";
import { Problem, Pager } from "../workflows/Feedback";
import {
  approvalApi,
  approvalFailure,
  terminalExecution,
  type Approval,
  type Policy,
} from "./api";
const label = (value: string) => value.replaceAll("_", " ");
const when = (value: string) => new Date(value).toLocaleString();
const states = [
  "PENDING",
  "APPROVED",
  "REJECTED",
  "EXPIRED",
  "INVALIDATED",
  "CANCELLED",
];

export function ApprovalInbox({ user }: { user: User }) {
  const { id = "" } = useParams();
  const access = useWorkflowAccess(user.id, id);
  const [params] = useSearchParams();
  const execution = params.get("executionId") ?? "";
  const allowed = access.data?.permissions.includes("approval.read") ?? false;
  const [status, setStatus] = useState(execution ? "" : "PENDING");
  const [page, setPage] = useState(0);
  const inbox = useQuery({
    queryKey: [user.id, "approvals", id, status, page, execution],
    queryFn: () => approvalApi.list(id, page, status, execution),
    enabled: allowed,
    retry: false,
    gcTime: 0,
    refetchInterval: (q) =>
      q.state.error
        ? false
        : status === "PENDING"
          ? q.state.data?.items.length
            ? 3000
            : 10000
          : q.state.data?.items.some(
                (r) => !terminalExecution(r.executionStatus),
              )
            ? 3000
            : false,
  });
  if (access.isPending) return <p role="status">Checking approval access…</p>;
  if (access.isError)
    return <Problem error={access.error} retry={() => void access.refetch()} />;
  if (!allowed) return <p role="alert">Approval reading is not permitted.</p>;
  return (
    <>
      <div className="page-heading">
        <div>
          <div className="eyebrow">HUMAN GOVERNANCE</div>
          <h1>Approval inbox</h1>
          <p>
            Review exact proposed operations. One different eligible person
            decides.
          </p>
        </div>
        <Link to={`/organizations/${id}/approval-policy`}>Approval policy</Link>
      </div>
      <section className="data-panel">
        <div className="action-row">
          <label>
            Approval status
            <select
              value={status}
              onChange={(e) => {
                setStatus(e.target.value);
                setPage(0);
              }}
            >
              <option value="">All states</option>
              {states.map((s) => (
                <option key={s} value={s}>
                  {label(s)}
                </option>
              ))}
            </select>
          </label>
          <button className="button" onClick={() => void inbox.refetch()}>
            Refresh approvals
          </button>
          <Link to={`/organizations/${id}/executions`}>Execution history</Link>
        </div>
        {inbox.isPending && <p role="status">Loading approvals…</p>}
        {inbox.isError && (
          <Problem error={inbox.error} retry={() => void inbox.refetch()} />
        )}
        {inbox.data && !inbox.isError && (
          <>
            {inbox.data.items.length === 0 ? (
              <p>No approval requests match this view.</p>
            ) : (
              <ol className="version-history">
                {inbox.data.items.map((r) => (
                  <li key={r.id}>
                    <Link to={`/organizations/${id}/approvals/${r.id}`}>
                      {r.prompt}
                    </Link>
                    <p>
                      {r.workflowName} · published version {r.versionNumber}
                    </p>
                    <p>
                      <strong>{r.status}</strong>
                      {r.invalidated && " · Authorization invalidated"} ·
                      Internal record
                    </p>
                    <p>
                      {typeof r.arguments.message === "string"
                        ? r.arguments.message
                        : `${r.capability}.${r.operation}`}
                    </p>
                    <p>
                      Created {when(r.createdAt)} · expires {when(r.expiresAt)}
                    </p>
                    <p>
                      Requester {r.requesterName} · execution{" "}
                      {label(r.executionStatus)}
                    </p>
                  </li>
                ))}
              </ol>
            )}
            <Pager page={page} pages={inbox.data.totalPages} change={setPage} />
          </>
        )}
      </section>
    </>
  );
}

export function ApprovalDetail({ user }: { user: User }) {
  const { id = "", approvalId = "" } = useParams();
  const access = useWorkflowAccess(user.id, id);
  const allowed = access.data?.permissions.includes("approval.read") ?? false;
  const request = useQuery({
    queryKey: [user.id, "approval", id, approvalId],
    queryFn: () => approvalApi.get(id, approvalId),
    enabled: allowed,
    retry: false,
    gcTime: 0,
    refetchInterval: (q) =>
      q.state.error
        ? false
        : q.state.data &&
            (q.state.data.status === "PENDING" ||
              !terminalExecution(q.state.data.executionStatus))
          ? 2000
          : false,
  });
  if (access.isPending) return <p role="status">Checking approval access…</p>;
  if (access.isError)
    return <Problem error={access.error} retry={() => void access.refetch()} />;
  if (!allowed) return <p role="alert">Approval reading is not permitted.</p>;
  if (request.isPending) return <p role="status">Loading approval…</p>;
  if (request.isError)
    return (
      <Problem error={request.error} retry={() => void request.refetch()} />
    );
  return (
    <ApprovalReview
      user={user}
      organization={id}
      approval={request.data}
      refresh={() => request.refetch()}
    />
  );
}

function ApprovalReview({
  user,
  organization,
  approval: r,
  refresh,
}: {
  user: User;
  organization: string;
  approval: Approval;
  refresh: () => Promise<unknown>;
}) {
  const client = useQueryClient();
  const [confirm, setConfirm] = useState<"APPROVE" | "REJECT" | null>(null);
  const [reason, setReason] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const [notice, setNotice] = useState("");
  async function submit(e: FormEvent) {
    e.preventDefault();
    if (!confirm || !r.actionable) return;
    setBusy(true);
    setError("");
    try {
      await approvalApi.decide(organization, r.id, r.revision, confirm, reason);
      setNotice(
        "Decision recorded. Execution status below comes from persisted server data.",
      );
      setConfirm(null);
      setReason("");
      await client.invalidateQueries({
        predicate: (q) =>
          q.queryKey[0] === user.id &&
          q.queryKey.includes(organization) &&
          q.queryKey.some(
            (k) =>
              typeof k === "string" &&
              (k.startsWith("approval") || k.startsWith("execution")),
          ),
      });
      await refresh();
    } catch (failure) {
      setError(approvalFailure(failure));
      setConfirm(null);
      await refresh();
    } finally {
      setBusy(false);
    }
  }
  return (
    <>
      <div className="page-heading">
        <div>
          <div className="eyebrow">EXACT PROPOSED INTENT</div>
          <h1>Approval detail</h1>
          <p>{r.prompt}</p>
        </div>
        <Link to={`/organizations/${organization}/approvals`}>
          All approvals
        </Link>
      </div>
      <section className="data-panel">
        <div className="action-row">
          <h2>Request state</h2>
          <strong role="status">{r.status}</strong>
          <button className="button" onClick={() => void refresh()}>
            Refresh approval
          </button>
        </div>
        <p>
          Execution state: <strong>{label(r.executionStatus)}</strong> ·{" "}
          <Link
            to={`/organizations/${organization}/executions/${r.executionId}`}
          >
            Inspect execution
          </Link>
        </p>
        <p>
          Created {when(r.createdAt)} · expires {when(r.expiresAt)} · absolute
          execution deadline {when(r.deadlineAt)}
        </p>
        <p>Requester {r.requesterName}</p>
        <Link
          to={`/organizations/${organization}/workflows/${r.workflowId}/versions/${r.versionId}`}
        >
          {r.workflowName} · published version {r.versionNumber}
        </Link>
        <p>
          Approval policy version {r.policyVersion} · maximum lifetime{" "}
          {r.policySnapshot.lifetimeSeconds} seconds
        </p>
        {r.invalidated && (
          <p role="alert">
            This authorization is invalidated. It cannot authorize a future
            effect.
          </p>
        )}
        {r.errorCode && <p role="alert">Reason: {label(r.errorCode)}</p>}
      </section>
      <section className="data-panel">
        <h2>
          {r.capability === "synthetic"
            ? "Exact internal operation"
            : "Exact proposed operation"}
        </h2>
        <p>
          <strong>
            {r.capability}.{r.operation}
          </strong>{" "}
          {r.capability === "synthetic"
            ? "creates one bounded internal record. It does not contact or change an external service."
            : `is a registered ${r.classification === "READ" ? "read" : "write"} operation. Review its exact arguments and connection revision before deciding.`}
        </p>
        {r.connection && (
          <p>
            Connection revision {r.connection.revision} · implementation v
            {r.connection.implementationVersion} · registered operation{" "}
            {r.connection.operationId}. Sensitive business and external writes
            require a different eligible human even when record approval policy
            is off.
          </p>
        )}
        <pre className="definition-json" aria-label="Exact proposed arguments">
          {r.canonicalArguments ?? JSON.stringify(r.arguments, null, 2)}
        </pre>
        <p>
          Your decision applies only to these stored arguments. You cannot edit
          them here.
        </p>
        <details>
          <summary>Technical intent details</summary>
          {r.origin && (
            <p>
              Accepted webhook endpoint <code>{r.origin.endpoint}</code> ·
              revision {r.origin.endpointRevision} · signing revision author{" "}
              <code>{r.origin.endpointAuthor}</code>
            </p>
          )}
          {r.connection && (
            <p>
              Connection ID <code>{r.connection.id}</code> · intent schema{" "}
              {r.intentSchemaVersion}
            </p>
          )}
          <p>
            Requester ID{" "}
            <code>{r.actor?.id ?? r.requestedBy ?? "Unavailable"}</code>
            {r.decision && (
              <>
                {" "}
                · decision actor ID <code>{r.decision.actor}</code>
              </>
            )}
          </p>
          <p>
            Request ID <code>{r.id}</code> · revision {r.revision}
          </p>
          <p>
            Gate <code>{r.gateNodeId}</code> · protected step{" "}
            <code>{r.actionNodeId}</code>
          </p>
          <code className="definition-hash">Intent SHA-256 {r.intentHash}</code>
          <code className="definition-hash">
            Arguments SHA-256 {r.argumentHash}
          </code>
          <code className="definition-hash">
            Definition SHA-256 {r.definitionHash}
          </code>
          <p>
            Version ID <code>{r.versionId}</code>
          </p>
        </details>
        {notice && <p role="status">{notice}</p>}
        {error && (
          <p role="alert" className="form-error">
            {error}
          </p>
        )}
        {r.actionable ? (
          <>
            {!confirm ? (
              <div className="action-row">
                <button
                  className="button primary"
                  onClick={() => setConfirm("APPROVE")}
                >
                  {r.capability === "synthetic"
                    ? "Approve internal record"
                    : "Approve exact operation"}
                </button>
                <button
                  className="button danger"
                  onClick={() => setConfirm("REJECT")}
                >
                  Reject request
                </button>
              </div>
            ) : (
              <form onSubmit={(e) => void submit(e)}>
                <fieldset disabled={busy}>
                  <legend>
                    {confirm === "APPROVE"
                      ? "Confirm exact approval"
                      : "Confirm rejection"}
                  </legend>
                  <p>
                    {confirm === "APPROVE"
                      ? `Authorize this exact ${r.capability === "synthetic" ? "internal record" : "registered operation"}. The worker must still revalidate policy, authority, preconditions and deadline before executing it.`
                      : "Reject this intent. Earlier completed operations remain recorded."}
                  </p>
                  <label>
                    Optional decision reason
                    <textarea
                      value={reason}
                      maxLength={200}
                      onChange={(e) => setReason(e.target.value)}
                    />
                  </label>
                  <div className="action-row">
                    <button className="button primary" type="submit">
                      {busy
                        ? "Saving decision…"
                        : confirm === "APPROVE"
                          ? "Confirm approval"
                          : "Confirm rejection"}
                    </button>
                    <button
                      className="button"
                      type="button"
                      onClick={() => setConfirm(null)}
                    >
                      Back to review
                    </button>
                  </div>
                </fieldset>
              </form>
            )}
          </>
        ) : (
          r.status === "PENDING" && (
            <p>
              {r.requestedBy === user.id
                ? "You requested this operation. A different eligible person must decide; Owner status does not permit self-approval."
                : r.actor?.type === "AUTOMATION" &&
                    r.actor.configuringHumans.includes(user.id)
                  ? "You configured this automation or its accepted signing revision. A different eligible person must decide this request."
                  : "This request is not actionable for your current permissions or its current state. The server will reject expired or stale decisions."}
            </p>
          )
        )}
      </section>
      {r.decision && (
        <section className="data-panel">
          <h2>Recorded decision</h2>
          <p>
            {r.decision.decision} · {when(r.decision.at)} · actor{" "}
            {r.decision.actorName}
          </p>
          {r.decision.reason && <p>{r.decision.reason}</p>}
          <p>A recorded approval is separate from the execution result.</p>
        </section>
      )}
      <section className="data-panel">
        <h2>Approval history</h2>
        {r.events.length ? (
          <ol className="version-history">
            {r.events.map((event) => (
              <li key={event.sequence}>
                <strong>{label(event.code)}</strong>
                <p>
                  {when(event.occurredAt)} · actor {event.actorName}
                </p>
              </li>
            ))}
          </ol>
        ) : (
          <p>No approval events returned.</p>
        )}
      </section>
    </>
  );
}

export function ApprovalPolicy({ user }: { user: User }) {
  const { id = "" } = useParams();
  const access = useWorkflowAccess(user.id, id);
  const client = useQueryClient();
  const allowed = access.data?.permissions.includes("approval.read") ?? false;
  const manage = access.data?.permissions.includes("policy.manage") ?? false;
  const policy = useQuery({
    queryKey: [user.id, "approval-policy", id],
    queryFn: () => approvalApi.policy(id),
    enabled: allowed,
    retry: false,
    gcTime: 0,
  });
  if (access.isPending) return <p role="status">Checking policy access…</p>;
  if (access.isError)
    return <Problem error={access.error} retry={() => void access.refetch()} />;
  if (!allowed)
    return <p role="alert">Approval policy reading is not permitted.</p>;
  if (policy.isPending) return <p role="status">Loading approval policy…</p>;
  if (policy.isError)
    return <Problem error={policy.error} retry={() => void policy.refetch()} />;
  return (
    <PolicyEditor
      key={id}
      policy={policy.data}
      manage={manage}
      reload={async () => {
        await client.invalidateQueries({
          queryKey: [user.id, "workflow-access", id],
        });
        const result = await policy.refetch();
        return result.isError ? null : (result.data ?? null);
      }}
      saved={(p) => client.setQueryData([user.id, "approval-policy", id], p)}
      organization={id}
    />
  );
}
function PolicyEditor({
  policy,
  manage,
  reload,
  saved,
  organization,
}: {
  policy: Policy;
  manage: boolean;
  reload: () => Promise<Policy | null>;
  saved: (p: Policy) => void;
  organization: string;
}) {
  const [draft, setDraft] = useState(policy);
  const [lifetime, setLifetime] = useState(String(policy.lifetimeSeconds));
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const [notice, setNotice] = useState("");
  async function submit(e: FormEvent) {
    e.preventDefault();
    const value = Number(lifetime);
    if (!Number.isInteger(value) || value < 60 || value > 86400) {
      setError("Approval lifetime must be between 60 and 86400 seconds.");
      return;
    }
    setBusy(true);
    setError("");
    try {
      const result = await approvalApi.updatePolicy(
        organization,
        draft.revision,
        draft.requireRecordApproval,
        value,
      );
      saved(result);
      setDraft(result);
      setLifetime(String(result.lifetimeSeconds));
      setNotice(
        "Policy committed. Pending requests from the previous version are invalidated.",
      );
    } catch (failure) {
      setError(approvalFailure(failure));
      await reload();
    } finally {
      setBusy(false);
    }
  }
  return (
    <>
      <div className="page-heading">
        <div>
          <div className="eyebrow">ORGANIZATION GOVERNANCE</div>
          <h1>Approval policy</h1>
          <p>Effective policy version {policy.version}</p>
        </div>
        <Link to={`/organizations/${organization}/approvals`}>
          Approval inbox
        </Link>
      </div>
      <section className="data-panel">
        <h2>Internal record rule</h2>
        <p>
          This rule protects registered synthetic.record operations only.
          Explicit approval nodes always require a different eligible human,
          even when this rule is off. No external connector, email approval, AI
          or MCP authority is configured here.
        </p>
        <p>
          Effective rule:{" "}
          {policy.requireRecordApproval
            ? "Approval required for every internal record"
            : "Low-risk internal records may run without a policy gate"}
          . Lifetime: {policy.lifetimeSeconds} seconds.
        </p>
        {error && (
          <p role="alert" className="form-error">
            {error}
          </p>
        )}
        {notice && <p role="status">{notice}</p>}
        {manage ? (
          <form onSubmit={(e) => void submit(e)}>
            <fieldset disabled={busy}>
              <legend>Change supported policy fields</legend>
              <label>
                <input
                  type="checkbox"
                  checked={draft.requireRecordApproval}
                  onChange={(e) =>
                    setDraft({
                      ...draft,
                      requireRecordApproval: e.target.checked,
                    })
                  }
                />
                Require approval for every internal record
              </label>
              <label>
                Approval lifetime in seconds
                <input
                  type="number"
                  min={60}
                  max={86400}
                  value={lifetime}
                  onChange={(e) => setLifetime(e.target.value)}
                />
              </label>
              <p>
                Saving advances the policy version and invalidates pending
                requests. Older approved intents cannot authorize future effects
                under a changed policy.
              </p>
              {draft.revision !== policy.revision && (
                <p role="alert">
                  The policy changed. Your draft still targets revision{" "}
                  {draft.revision}; reload the current policy before saving
                  again.
                </p>
              )}
              <div className="action-row">
                <button
                  className="button primary"
                  type="submit"
                  disabled={draft.revision !== policy.revision}
                >
                  {busy ? "Saving policy…" : "Save approval policy"}
                </button>
                <button
                  className="button"
                  type="button"
                  onClick={() => {
                    void reload().then((current) => {
                      if (current) {
                        setDraft(current);
                        setLifetime(String(current.lifetimeSeconds));
                        setError("");
                        setNotice("");
                      }
                    });
                  }}
                >
                  Reload current policy
                </button>
              </div>
            </fieldset>
          </form>
        ) : (
          <p>
            Policy changes require the existing Owner policy.manage permission.
          </p>
        )}
      </section>
    </>
  );
}
