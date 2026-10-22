import { useState } from "react";
import { Link, useNavigate } from "react-router-dom";
import { executionApi, executionFailure } from "./api";

export function ExecutionStart({
  organization,
  workflow,
  version,
  permissions,
  disabled = false,
}: {
  organization: string;
  workflow: string;
  version: string | null;
  permissions: string[];
  disabled?: boolean;
}) {
  const navigate = useNavigate();
  const [confirm, setConfirm] = useState(false);
  const [delay, setDelay] = useState(0);
  const [key, setKey] = useState(() => crypto.randomUUID());
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  async function start() {
    if (!version) return;
    setBusy(true);
    setError("");
    try {
      const execution = await executionApi.start(
        organization,
        workflow,
        version,
        delay,
        key,
      );
      navigate(`/organizations/${organization}/executions/${execution.id}`);
    } catch (failure) {
      setError(executionFailure(failure));
    } finally {
      setBusy(false);
    }
  }
  return (
    <section className="data-panel">
      <h2>Published execution</h2>
      <p>
        Run the saved published version with registered internal records and
        ticket/team reads. Publishing and editing never start an execution.
        Approval gates and organization policy pause protected operations for a
        different eligible person.
      </p>
      {permissions.includes("execution.read") && (
        <Link
          to={`/organizations/${organization}/executions?workflowId=${workflow}`}
        >
          Execution history
        </Link>
      )}
      {permissions.includes("execution.run") ? (
        <button
          className="button primary"
          disabled={!version || disabled || busy}
          onClick={() => {
            setConfirm(true);
            setError("");
            setKey(crypto.randomUUID());
          }}
        >
          Run published version
        </button>
      ) : (
        <p>Starting requires execution.run.</p>
      )}
      {!version && <p>No published version is available.</p>}
      {disabled && (
        <p>
          Save local changes before leaving the editor to run its published
          version.
        </p>
      )}
      {confirm && (
        <div
          role="dialog"
          aria-modal="true"
          aria-labelledby="execution-confirm-title"
          className="confirmation-panel"
        >
          <h3 id="execution-confirm-title">Start internal execution?</h3>
          <p>
            This runs supported internal records and ticket/team reads using the
            exact published snapshot. No external service is contacted. Required
            approval waits for a different eligible human; starting never
            approves it. The 24-hour deadline includes queue time, approval
            waits and the optional start delay.
          </p>
          <details>
            <summary>Pinned version ID</summary>
            <code>{version}</code>
          </details>
          <label>
            Start delay in seconds
            <input
              type="number"
              min={0}
              max={3600}
              step={1}
              value={delay}
              disabled={busy}
              onChange={(event) => setDelay(event.target.valueAsNumber)}
            />
          </label>
          {error && <p role="alert">{error}</p>}
          {busy && <p role="status">Submitting durable execution…</p>}
          <div className="action-row">
            <button
              className="button primary"
              disabled={
                busy || !Number.isInteger(delay) || delay < 0 || delay > 3600
              }
              onClick={() => void start()}
            >
              Confirm internal execution
            </button>
            <button
              className="button"
              disabled={busy}
              onClick={() => setConfirm(false)}
            >
              Keep editing
            </button>
          </div>
        </div>
      )}
    </section>
  );
}
