import { useEffect, useState } from "react";
import {
  Link,
  useNavigate,
  useParams,
  useSearchParams,
} from "react-router-dom";
import { useQuery } from "@tanstack/react-query";
import type { User } from "../../lib/session";
import { useWorkflowAccess } from "../workflows/useWorkflowAccess";
import { Pager, Problem } from "../workflows/Feedback";
import { nodeSchema } from "../workflows/model";
import { executionApi, executionFailure, active } from "./api";
import type { Execution, Step } from "./api";

const when = (value: string | null) =>
  value ? new Date(value).toLocaleString() : "Not yet";
const stateLabel = (value: string) => value.replaceAll("_", " ");
export function ExecutionList({ user }: { user: User }) {
  const { id = "" } = useParams();
  const [params] = useSearchParams();
  const workflow = params.get("workflowId") ?? "";
  const [page, setPage] = useState(0);
  const [status, setStatus] = useState("");
  const access = useWorkflowAccess(user.id, id);
  const readable = access.data?.permissions.includes("execution.read") ?? false;
  const list = useQuery({
    queryKey: [user.id, "executions", id, page, workflow, status],
    queryFn: () => executionApi.list(id, page, workflow, status),
    enabled: readable,
    retry: false,
    gcTime: 0,
    refetchInterval: (q) =>
      q.state.data?.items.some((e) => active(e.status)) ? 3000 : false,
  });
  if (access.isPending) return <p role="status">Checking execution access…</p>;
  if (access.isError)
    return <Problem error={access.error} retry={() => void access.refetch()} />;
  if (!readable) return <p role="alert">Execution reading is not permitted.</p>;
  return (
    <>
      <div className="page-heading">
        <div>
          <div className="eyebrow">PERSISTED OPERATIONS</div>
          <h1>Execution history</h1>
          <p>Actual internal workflow executions and partial outcomes.</p>
        </div>
        <Link to={`/organizations/${id}/workflows`}>
          Organization workflows
        </Link>
      </div>
      <section className="data-panel">
        <div className="action-row">
          <label>
            Execution status
            <select
              value={status}
              onChange={(event) => {
                setStatus(event.target.value);
                setPage(0);
              }}
            >
              <option value="">All states</option>
              {[
                "QUEUED",
                "RUNNING",
                "WAITING_FOR_RETRY",
                "WAITING_FOR_DELAY",
                "WAITING_FOR_APPROVAL",
                "SUCCEEDED",
                "FAILED",
                "CANCELLED",
              ].map((s) => (
                <option key={s} value={s}>
                  {stateLabel(s)}
                </option>
              ))}
            </select>
          </label>
          <button className="button" onClick={() => void list.refetch()}>
            Refresh executions
          </button>
        </div>
        {list.isPending && <p role="status">Loading executions…</p>}
        {list.isError && (
          <Problem error={list.error} retry={() => void list.refetch()} />
        )}
        {list.data && (
          <>
            {list.data.items.length === 0 ? (
              <p>No executions match this view.</p>
            ) : (
              <ol className="version-history">
                {list.data.items.map((e) => (
                  <li key={e.id}>
                    <Link to={`/organizations/${id}/executions/${e.id}`}>
                      Inspect execution · {stateLabel(e.status)}
                    </Link>
                    <p>
                      Created {when(e.createdAt)} · {e.source} · {e.mode}
                    </p>
                    <code className="definition-hash">
                      SHA-256 {e.definitionHash}
                    </code>
                  </li>
                ))}
              </ol>
            )}
            <Pager page={page} pages={list.data.totalPages} change={setPage} />
          </>
        )}
      </section>
    </>
  );
}
export function ExecutionDetail({ user }: { user: User }) {
  const { id = "", executionId = "" } = useParams();
  const access = useWorkflowAccess(user.id, id);
  const readable = access.data?.permissions.includes("execution.read") ?? false;
  const detail = useQuery({
    queryKey: [user.id, "execution", id, executionId],
    queryFn: () => executionApi.get(id, executionId),
    enabled: readable,
    retry: false,
    gcTime: 0,
    refetchInterval: (q) =>
      q.state.data && active(q.state.data.status) && !q.state.error
        ? 2000
        : false,
  });
  if (access.isPending) return <p role="status">Checking execution access…</p>;
  if (access.isError)
    return <Problem error={access.error} retry={() => void access.refetch()} />;
  if (!readable) return <p role="alert">Execution reading is not permitted.</p>;
  if (detail.isPending) return <p role="status">Loading execution…</p>;
  if (!detail.data)
    return <Problem error={detail.error} retry={() => void detail.refetch()} />;
  return (
    <>
      <div className="page-heading">
        <div>
          <div className="eyebrow">DURABLE EXECUTION</div>
          <h1>Execution detail</h1>
          <p>Persisted state and internal operation receipts.</p>
        </div>
        <Link to={`/organizations/${id}/executions`}>All executions</Link>
      </div>
      {detail.isError && (
        <>
          <p role="alert">
            The displayed state may be stale. Refresh before requesting a
            mutation.
          </p>
          <Problem error={detail.error} retry={() => void detail.refetch()} />
        </>
      )}
      <ExecutionWorkspace
        key={executionId}
        organization={id}
        execution={detail.data}
        permissions={access.data.permissions}
        stale={detail.isError}
        refresh={async () => {
          await detail.refetch();
        }}
        user={user}
      />
    </>
  );
}
function ExecutionWorkspace({
  organization,
  execution: e,
  permissions,
  stale,
  refresh,
  user,
}: {
  organization: string;
  execution: Execution;
  permissions: string[];
  stale: boolean;
  refresh: () => Promise<void>;
  user: User;
}) {
  const navigate = useNavigate();
  const [now, setNow] = useState(() => Date.now());
  useEffect(() => {
    if (!active(e.status)) return;
    const timer = setInterval(() => setNow(Date.now()), 1000);
    return () => clearInterval(timer);
  }, [e.status]);
  const [eventPage, setEventPage] = useState(0);
  const [attemptPage, setAttemptPage] = useState(0);
  const [confirm, setConfirm] = useState<"cancel" | "retry" | null>(null);
  const [reason, setReason] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const [key, setKey] = useState(() => crypto.randomUUID());
  const interval = active(e.status) ? 2000 : false;
  const steps = useQuery({
    queryKey: [user.id, "execution-steps", organization, e.id, e.revision],
    queryFn: () => executionApi.steps(organization, e.id),
    retry: false,
    gcTime: 0,
    refetchInterval: interval,
  });
  const attempts = useQuery({
    queryKey: [
      user.id,
      "execution-attempts",
      organization,
      e.id,
      e.revision,
      attemptPage,
    ],
    queryFn: () => executionApi.attempts(organization, e.id, attemptPage),
    retry: false,
    gcTime: 0,
    refetchInterval: interval,
  });
  const events = useQuery({
    queryKey: [
      user.id,
      "execution-events",
      organization,
      e.id,
      e.revision,
      eventPage,
    ],
    queryFn: () => executionApi.events(organization, e.id, eventPage),
    retry: false,
    gcTime: 0,
    refetchInterval: interval,
  });
  const names = new Map<string, string>();
  const counts = new Map<string, number>();
  for (const step of steps.data ?? []) {
    const n = (counts.get(step.type) ?? 0) + 1;
    counts.set(step.type, n);
    names.set(
      step.nodeId,
      (nodeSchema(step.type)?.label ?? "Step") +
        (step.type === "syntheticAction" || step.type === "approval"
          ? ` ${n}`
          : ""),
    );
  }
  async function mutate() {
    if (!confirm) return;
    setBusy(true);
    setError("");
    try {
      if (confirm === "cancel") {
        await executionApi.cancel(organization, e.id, e.revision, reason);
        await refresh();
        setConfirm(null);
      } else {
        const next = await executionApi.retry(
          organization,
          e.id,
          e.revision,
          reason,
          key,
        );
        navigate(`/organizations/${organization}/executions/${next.id}`);
      }
    } catch (failure) {
      setError(executionFailure(failure));
      await refresh();
    } finally {
      setBusy(false);
    }
  }
  async function reload() {
    await Promise.all([
      refresh(),
      steps.refetch(),
      attempts.refetch(),
      events.refetch(),
    ]);
  }
  const elapsed = e.startedAt
    ? Math.max(
        0,
        Math.floor(
          ((e.completedAt ? Date.parse(e.completedAt) : now) -
            Date.parse(e.startedAt)) /
            1000,
        ),
      )
    : null;
  return (
    <>
      <section className="data-panel">
        <div className="action-row">
          <h2>Execution state</h2>
          <strong role="status">{stateLabel(e.status)}</strong>
          <button className="button" onClick={() => void reload()}>
            Refresh execution
          </button>
        </div>
        <p>
          Created {when(e.createdAt)} · started {when(e.startedAt)} · completed{" "}
          {when(e.completedAt)}
        </p>
        <p>
          Absolute deadline: {when(e.deadlineAt)} · not before:{" "}
          {when(e.notBefore)}
        </p>
        <p>
          Duration since start:{" "}
          {elapsed === null ? "Not started" : `${elapsed}s`} · source {e.source}{" "}
          · {e.mode}
        </p>
        <p>
          Current step:{" "}
          {steps.data?.[e.currentPosition]
            ? names.get(steps.data[e.currentPosition]!.nodeId)
            : active(e.status)
              ? "Awaiting dispatch"
              : "No remaining dispatch"}
        </p>
        {e.status === "WAITING_FOR_APPROVAL" && (
          <div className="phase-note">
            <p>
              A distinct eligible person must review the exact proposed
              operation. Completed records remain visible while this execution
              waits.
            </p>
            {e.approvalId && (
              <Link
                to={`/organizations/${organization}/approvals/${e.approvalId}`}
              >
                Review outstanding approval
              </Link>
            )}
            {e.approvalExpiresAt && (
              <p>Approval expires: {when(e.approvalExpiresAt)}</p>
            )}
          </div>
        )}
        {e.errorCode && (
          <p role="alert">
            Reason: {stateLabel(e.errorCode)}. Earlier completed effects remain
            recorded.
          </p>
        )}
        <Link
          to={`/organizations/${organization}/workflows/${e.workflowId}/versions/${e.versionId}`}
        >
          Inspect pinned workflow version
        </Link>
        <code className="definition-hash">SHA-256 {e.definitionHash}</code>
        <p>
          <Link
            to={`/organizations/${organization}/approvals?executionId=${e.id}`}
          >
            Approval history for this execution
          </Link>
        </p>
        {e.retryOfExecutionId && (
          <Link
            to={`/organizations/${organization}/executions/${e.retryOfExecutionId}`}
          >
            Original failed execution
          </Link>
        )}
        <details>
          <summary>Technical execution details</summary>
          <p>
            Execution ID: <code>{e.id}</code> · revision {e.revision} ·
            requester <code>{e.requestedBy}</code>
          </p>
        </details>
        <div className="action-row">
          {active(e.status) && permissions.includes("execution.cancel") && (
            <button
              className="button danger"
              disabled={busy || stale}
              onClick={() => {
                setConfirm("cancel");
                setReason("");
                setError("");
              }}
            >
              Cancel execution
            </button>
          )}
          {e.retryEligible && permissions.includes("execution.retry") && (
            <button
              className="button"
              disabled={busy || stale}
              onClick={() => {
                setConfirm("retry");
                setReason("");
                setError("");
                setKey(crypto.randomUUID());
              }}
            >
              Retry eligible failure
            </button>
          )}
        </div>
        {confirm && (
          <div
            role="dialog"
            aria-modal="true"
            aria-labelledby="execution-mutation-title"
            className="confirmation-panel"
          >
            <h3 id="execution-mutation-title">
              {confirm === "cancel"
                ? "Cancel further dispatch?"
                : "Retry with the same snapshot?"}
            </h3>
            <p>
              {confirm === "cancel"
                ? "Completed effects will remain. Cancellation prevents further dispatch at cooperative checkpoints."
                : "A new linked execution reuses committed operation receipts and receives a new explicit 24-hour deadline. The original remains failed."}
            </p>
            <label>
              Reason
              <input
                value={reason}
                maxLength={200}
                onChange={(event) => setReason(event.target.value)}
                disabled={busy}
              />
            </label>
            {error && <p role="alert">{error}</p>}
            {busy && <p role="status">Saving execution request…</p>}
            <div className="action-row">
              <button
                className="button primary"
                disabled={busy || !reason.trim() || stale}
                onClick={() => void mutate()}
              >
                {confirm === "cancel"
                  ? "Confirm cancellation"
                  : "Confirm linked retry"}
              </button>
              <button
                className="button"
                disabled={busy}
                onClick={() => setConfirm(null)}
              >
                Keep current execution
              </button>
            </div>
          </div>
        )}
      </section>
      <section className="data-panel">
        <h2>Steps and results</h2>
        {steps.isPending && <p role="status">Loading steps…</p>}
        {steps.isError && (
          <Problem error={steps.error} retry={() => void steps.refetch()} />
        )}
        <ol className="version-history">
          {steps.data?.map((step) => (
            <StepView
              key={step.nodeId}
              step={step}
              name={names.get(step.nodeId) ?? "Step"}
            />
          ))}
        </ol>
      </section>
      <section className="data-panel">
        <h2>Attempt history</h2>
        {attempts.isPending && <p role="status">Loading attempts…</p>}
        {attempts.isError && (
          <Problem
            error={attempts.error}
            retry={() => void attempts.refetch()}
          />
        )}
        {attempts.data && (
          <>
            <ol className="version-history">
              {attempts.data.items.map((a) => (
                <li key={a.id}>
                  {names.get(a.nodeId) ?? "Step"} · attempt {a.attemptNumber} ·{" "}
                  {stateLabel(a.outcome)}
                  <p>
                    {when(a.startedAt)} → {when(a.completedAt)}{" "}
                    {a.errorCode && ` · ${stateLabel(a.errorCode)}`}
                  </p>
                </li>
              ))}
            </ol>
            <Pager
              page={attemptPage}
              pages={attempts.data.totalPages}
              change={setAttemptPage}
            />
          </>
        )}
      </section>
      <section className="data-panel">
        <h2>Execution timeline</h2>
        {events.isPending && <p role="status">Loading timeline…</p>}
        {events.isError && (
          <Problem error={events.error} retry={() => void events.refetch()} />
        )}
        {events.data && (
          <>
            <ol className="version-history">
              {events.data.items.map((event) => (
                <li key={event.sequence}>
                  {stateLabel(event.code)}
                  {event.nodeId && ` · ${names.get(event.nodeId) ?? "Step"}`}
                  <p>{when(event.occurredAt)}</p>
                </li>
              ))}
            </ol>
            <Pager
              page={eventPage}
              pages={events.data.totalPages}
              change={setEventPage}
            />
          </>
        )}
      </section>
    </>
  );
}
function StepView({ step, name }: { step: Step; name: string }) {
  return (
    <li>
      <h3>
        {name} · {stateLabel(step.status)}
      </h3>
      <p>
        {step.attemptCount} attempts · {when(step.startedAt)} →{" "}
        {when(step.completedAt)}
      </p>
      {step.errorCode && <p>Reason: {stateLabel(step.errorCode)}</p>}
      {step.retryAt && <p>Next automatic attempt: {when(step.retryAt)}</p>}
      {step.output !== null && (
        <pre className="definition-json" tabIndex={0}>
          {JSON.stringify(step.output, null, 2)}
        </pre>
      )}
      <details>
        <summary>Technical step details</summary>
        <code>{step.nodeId}</code> · position {step.position}
      </details>
    </li>
  );
}
