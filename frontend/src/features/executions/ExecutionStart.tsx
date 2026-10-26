import { useEffect, useState } from "react";
import { Link, useNavigate } from "react-router-dom";
import { executionApi, executionFailure } from "./api";
import { workflowApi } from "../workflows/api";
import type { Snapshot } from "../workflows/model";
import { publishedStart, triggerValues } from "./publishedStart";

export function ExecutionStart({
  organization,
  workflow,
  version,
  permissions,
  disabled = false,
  schemaVersion = 1,
}: {
  organization: string;
  workflow: string;
  version: string | null;
  permissions: string[];
  disabled?: boolean;
  schemaVersion?: number;
}) {
  const navigate = useNavigate();
  const [confirm, setConfirm] = useState(false);
  const [externalAcknowledged, setExternalAcknowledged] = useState(false);
  const [delay, setDelay] = useState(0);
  const [key, setKey] = useState(() => crypto.randomUUID());
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const [snapshot, setSnapshot] = useState<Snapshot | null>(null);
  const [snapshotError, setSnapshotError] = useState(false);
  const [reload, setReload] = useState(0);
  const [values, setValues] = useState<Record<string, string>>({});
  useEffect(() => {
    if (!confirm || !version || schemaVersion !== 2) return;
    let current = true;
    void workflowApi
      .version(organization, workflow, version)
      .then((loaded) => {
        if (loaded.version.id !== version || loaded.version.schemaVersion !== 2)
          throw new Error("Published version mismatch");
        publishedStart(loaded.definition);
        if (current) {
          setSnapshot(loaded);
          setSnapshotError(false);
        }
      })
      .catch(() => {
        if (current) setSnapshotError(true);
      });
    return () => {
      current = false;
    };
  }, [confirm, organization, workflow, version, schemaVersion, reload]);
  const pinned = snapshot?.version.id === version ? snapshot : null;
  const preview = pinned ? publishedStart(pinned.definition) : null;
  const input = triggerValues(preview?.fields ?? [], values);
  const inputReady =
    schemaVersion !== 2 ||
    Boolean(preview && preview.manual && !preview.unsupported && input);
  function updateValue(name: string, value: string) {
    setExternalAcknowledged(false);
    setValues((before) => ({ ...before, [name]: value }));
    setKey(crypto.randomUUID());
    setError("");
  }
  async function start() {
    if (!version || !inputReady || (preview?.external && !externalAcknowledged))
      return;
    setBusy(true);
    setError("");
    try {
      const execution = await executionApi.start(
        organization,
        workflow,
        version,
        delay,
        key,
        input ?? {},
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
        Run the saved published version with registered internal records,
        ticket/team operations, internal notifications and configured REST
        operations. Publishing and editing never start an execution. Approval
        gates and organization policy pause protected operations for a different
        eligible person.
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
            setExternalAcknowledged(false);
            setError("");
            setSnapshot(null);
            setSnapshotError(false);
            setValues({});
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
          <h3 id="execution-confirm-title">
            {preview?.external
              ? "Start external workflow execution?"
              : "Start internal execution?"}
          </h3>
          <p>
            {preview?.external
              ? "This published version can contact its configured external provider and perform approved writes. External effects cannot be undone by cancelling this execution. An uncertain write outcome requires reconciliation before any new submission. "
              : schemaVersion === 2 && !preview
                ? "Inspect the published inputs and registered operations before confirming. "
                : "This runs supported internal records, ticket/team operations and internal notifications using the exact published snapshot. No external service is contacted. "}
            Required approval waits for a different eligible human; starting
            never approves it. The 24-hour deadline includes queue time,
            approval waits and the optional start delay.
          </p>
          <details>
            <summary>Pinned version ID</summary>
            <code>{version}</code>
          </details>
          {schemaVersion === 2 && !pinned && !snapshotError && (
            <p role="status">Loading the published inputs and operations…</p>
          )}
          {schemaVersion === 2 && snapshotError && (
            <div role="alert">
              <p>
                The published preview is unavailable. Starting is disabled until
                it can be inspected.
              </p>
              <button
                className="button"
                onClick={() => {
                  setSnapshotError(false);
                  setReload((value) => value + 1);
                }}
              >
                Reload published preview
              </button>
            </div>
          )}
          {preview && (
            <>
              <p>
                Published version {pinned?.version.versionNumber}. Branches
                select which operations run; mapped arguments resolve during
                execution. Human approval shows the exact prepared plan before
                protected writes.
              </p>
              <details>
                <summary>Published definition hash</summary>
                <code>{pinned?.version.definitionHash}</code>
              </details>
              {!preview.manual && (
                <p role="alert">
                  This version has a webhook trigger and cannot be started
                  manually.
                </p>
              )}
              {preview.unsupported && (
                <p role="alert">
                  This published version contains an operation the current
                  runtime cannot execute. No execution will be created.
                </p>
              )}
              <fieldset disabled={busy}>
                <legend>Published trigger inputs</legend>
                {preview.fields.length === 0 && (
                  <p>This trigger accepts an empty object.</p>
                )}
                {preview.fields.map(([name, type]) => (
                  <label key={name}>
                    {name} ({type})
                    {type === "BOOLEAN" ? (
                      <select
                        value={Object.hasOwn(values, name) ? values[name] : ""}
                        onChange={(event) =>
                          updateValue(name, event.target.value)
                        }
                      >
                        <option value="">Choose true or false</option>
                        <option value="true">True</option>
                        <option value="false">False</option>
                      </select>
                    ) : (
                      <input
                        type="text"
                        inputMode={type === "INTEGER" ? "numeric" : undefined}
                        maxLength={type === "STRING" ? 2000 : 17}
                        value={Object.hasOwn(values, name) ? values[name] : ""}
                        onChange={(event) =>
                          updateValue(name, event.target.value)
                        }
                      />
                    )}
                  </label>
                ))}
                {!input && (
                  <p role="status">
                    Complete all typed inputs. Integers must be exact whole
                    numbers within the browser’s safe integer range.
                  </p>
                )}
              </fieldset>
              <h4>Registered operations in this snapshot</h4>
              {preview.effects.length === 0 ? (
                <p>No business operation is defined.</p>
              ) : (
                <ul>
                  {preview.effects.map((effect) => (
                    <li key={effect.id}>
                      <strong>{effect.label}</strong>: {effect.operation}
                      {effect.protected &&
                        " — distinct-human approval required"}
                      {effect.external &&
                        " — configured external writes require distinct-human approval"}
                      <details>
                        <summary>Operation binding details</summary>
                        <p>
                          Connection:{" "}
                          {effect.connection ?? "Built-in internal record"};
                          revision {String(effect.revision ?? "not applicable")}
                          .
                        </p>
                        <p>
                          These are saved mappings, not a preview of live ticket
                          data.
                        </p>
                        <pre>{JSON.stringify(effect.inputs, null, 2)}</pre>
                        <code>{effect.id}</code>
                      </details>
                    </li>
                  ))}
                </ul>
              )}
            </>
          )}
          <label>
            Start delay in seconds
            <input
              type="number"
              min={0}
              max={3600}
              step={1}
              value={delay}
              disabled={busy}
              onChange={(event) => {
                setExternalAcknowledged(false);
                setDelay(event.target.valueAsNumber);
                setKey(crypto.randomUUID());
                setError("");
              }}
            />
          </label>
          {preview?.external && (
            <label>
              <input
                type="checkbox"
                checked={externalAcknowledged}
                disabled={busy}
                onChange={(event) =>
                  setExternalAcknowledged(event.target.checked)
                }
              />
              I acknowledge that these configured operations may contact an
              external provider and create effects.
            </label>
          )}
          {error && <p role="alert">{error}</p>}
          {busy && <p role="status">Submitting durable execution…</p>}
          <div className="action-row">
            <button
              className="button primary"
              disabled={
                busy ||
                !inputReady ||
                (Boolean(preview?.external) && !externalAcknowledged) ||
                !Number.isInteger(delay) ||
                delay < 0 ||
                delay > 3600
              }
              onClick={() => void start()}
            >
              {preview?.external
                ? "Confirm external execution"
                : "Confirm internal execution"}
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
