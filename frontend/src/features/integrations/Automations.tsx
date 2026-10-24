import { useState, type FormEvent } from "react";
import { Link, useParams } from "react-router-dom";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { failureMessage, type User } from "../../lib/session";
import { useWorkflowAccess } from "../workflows/useWorkflowAccess";
import { Pager, Problem } from "../workflows/Feedback";
import { workflowApi } from "../workflows/api";
import {
  automationApi,
  type SigningReveal,
  type WebhookEndpoint,
} from "./automationApi";

export function Automations({ user }: { user: User }) {
  const { id = "" } = useParams();
  const access = useWorkflowAccess(user.id, id);
  if (access.isPending) return <p role="status">Checking automation access…</p>;
  if (access.isError)
    return <Problem error={access.error} retry={() => void access.refetch()} />;
  if (!access.data.permissions.includes("integration.read"))
    return <p role="alert">Automation reading is not permitted.</p>;
  return (
    <AutomationWorkspace
      key={id}
      user={user.id}
      org={id}
      permissions={access.data.permissions}
    />
  );
}
function AutomationWorkspace({
  user,
  org,
  permissions,
}: {
  user: string;
  org: string;
  permissions: string[];
}) {
  const client = useQueryClient(),
    key = [user, "automations", org];
  const manage = ["integration.manage", "execution.run"].every((p) =>
    permissions.includes(p),
  );
  const [principalPage, setPrincipalPage] = useState(0),
    [endpointPage, setEndpointPage] = useState(0);
  const principals = useQuery({
    queryKey: [...key, "principals", principalPage],
    queryFn: () => automationApi.principals(org, principalPage),
    retry: false,
    gcTime: 0,
  });
  const endpoints = useQuery({
    queryKey: [...key, "endpoints", endpointPage],
    queryFn: () => automationApi.endpoints(org, endpointPage),
    retry: false,
    gcTime: 0,
  });
  const [busy, setBusy] = useState(false),
    [error, setError] = useState(""),
    [notice, setNotice] = useState("");
  const [reveal, setReveal] = useState<SigningReveal | null>(null),
    [principal, setPrincipal] = useState(""),
    [endpointName, setEndpointName] = useState("");
  const [confirmEndpoint, setConfirmEndpoint] = useState(false),
    [selected, setSelected] = useState<WebhookEndpoint | null>(null);
  const [action, setAction] = useState<{
    kind: "rotate" | "endpoint" | "principal";
    id: string;
    revision: number;
    name: string;
  } | null>(null);
  async function mutate(task: () => Promise<unknown>, message: string) {
    setBusy(true);
    setError("");
    setNotice("");
    setReveal(null);
    try {
      await task();
      setNotice(message);
      setAction(null);
      await client.invalidateQueries({ queryKey: key });
    } catch (failure) {
      setError(failureMessage(failure));
    } finally {
      setBusy(false);
    }
  }
  async function createEndpoint(event: FormEvent) {
    event.preventDefault();
    if (!confirmEndpoint) return;
    await mutate(async () => {
      const result = await automationApi.endpoint(org, endpointName, principal);
      setReveal(result);
      setEndpointPage(0);
      setConfirmEndpoint(false);
      setEndpointName("");
    }, "Endpoint created. No delivery or execution was started.");
  }
  return (
    <>
      <section className="page-heading">
        <div>
          <p className="eyebrow">SCOPED AUTOMATION</p>
          <h1>Automations & webhooks</h1>
          <p>
            Signed deliveries run one pinned workflow version with explicit
            operation grants. Human approval remains independent.
          </p>
        </div>
        <Link to={`/organizations/${org}`}>Organization overview</Link>
      </section>
      {error && (
        <p role="alert">
          {error} Refresh persisted configuration before retrying a creation
          whose response was lost.
        </p>
      )}
      {notice && <p role="status">{notice}</p>}
      {reveal && (
        <SecretReveal
          key={`${reveal.endpoint.id}:${reveal.endpoint.revision}`}
          value={reveal}
          dismiss={() => setReveal(null)}
        />
      )}
      <section className="data-panel">
        <h2>Automation principals</h2>
        <p>
          Grants are derived from an exact published webhook workflow.
          Configuring users cannot approve its protected operations. Revocation
          stops future work and retains history.
        </p>
        {principals.isPending ? (
          <p role="status">Loading automation principals…</p>
        ) : principals.isError ? (
          <Problem
            error={principals.error}
            retry={() => void principals.refetch()}
          />
        ) : (
          <>
            {!principals.data.items.length && <p>No automation principals.</p>}
            {principals.data.items.map((p) => (
              <article className="data-panel" key={p.id}>
                <h3>{p.name}</h3>
                <p>
                  {p.status} · revision {p.revision}
                </p>
                <p>
                  <Link to={`/organizations/${org}/workflows/${p.workflowId}`}>
                    Pinned workflow
                  </Link>
                </p>
                <details>
                  <summary>Version and exact operation grants</summary>
                  <p>
                    Version <code>{p.versionId}</code>
                  </p>
                  <p>
                    SHA-256 <code>{p.definitionHash}</code>
                  </p>
                  <ul>
                    {p.grants.map((g) => (
                      <li key={g.nodeId}>
                        {g.operation} · {g.classification}
                        {g.integrationId && (
                          <>
                            {" "}
                            · connection <code>
                              {g.integrationId}
                            </code> revision {g.revision}
                          </>
                        )}
                      </li>
                    ))}
                  </ul>
                </details>
                {manage && p.status === "ACTIVE" && (
                  <button
                    className="button"
                    disabled={busy}
                    onClick={() =>
                      setAction({
                        kind: "principal",
                        id: p.id,
                        revision: p.revision,
                        name: p.name,
                      })
                    }
                  >
                    Revoke principal {p.name}
                  </button>
                )}
              </article>
            ))}
            <Pager
              page={principalPage}
              pages={principals.data.totalPages}
              change={setPrincipalPage}
            />
          </>
        )}
        {manage && permissions.includes("workflow.read") && (
          <PrincipalSetup
            user={user}
            org={org}
            disabled={busy}
            created={async () => {
              setPrincipalPage(0);
              await client.invalidateQueries({ queryKey: key });
            }}
          />
        )}
      </section>
      <section className="data-panel">
        <h2>Webhook endpoints</h2>
        <p>
          Only a valid signed delivery creates work. Creating or rotating a key
          starts nothing. Approval and internal notifications do not send SMTP
          email.
        </p>
        {manage && (
          <form onSubmit={(event) => void createEndpoint(event)}>
            <label>
              Endpoint name
              <input
                maxLength={120}
                required
                value={endpointName}
                onChange={(e) => setEndpointName(e.target.value)}
              />
            </label>
            <label>
              Active automation principal
              <select
                required
                value={principal}
                onChange={(e) => {
                  setPrincipal(e.target.value);
                  setConfirmEndpoint(false);
                }}
              >
                <option value="">Choose a pinned principal</option>
                {principals.data?.items
                  .filter((p) => p.status === "ACTIVE")
                  .map((p) => (
                    <option key={p.id} value={p.id}>
                      {p.name}
                    </option>
                  ))}
              </select>
            </label>
            <label>
              <input
                type="checkbox"
                checked={confirmEndpoint}
                onChange={(e) => setConfirmEndpoint(e.target.checked)}
              />
              Create this signed endpoint and reveal its key once.
            </label>
            <button
              className="button"
              disabled={busy || !confirmEndpoint || !principal}
            >
              Create webhook endpoint
            </button>
          </form>
        )}
        {endpoints.isPending ? (
          <p role="status">Loading webhook endpoints…</p>
        ) : endpoints.isError ? (
          <Problem
            error={endpoints.error}
            retry={() => void endpoints.refetch()}
          />
        ) : (
          <>
            {!endpoints.data.items.length && <p>No webhook endpoints.</p>}
            {endpoints.data.items.map((e) => (
              <article className="data-panel" key={e.id}>
                <h3>{e.name}</h3>
                <p>
                  {e.status} · revision {e.revision}
                </p>
                <label>
                  Callback path
                  <input readOnly value={`/api/v1/webhooks/${e.id}`} />
                </label>
                <details>
                  <summary>Pinned endpoint details</summary>
                  <p>
                    Principal <code>{e.principalId}</code>
                  </p>
                  <p>
                    Version <code>{e.versionId}</code>
                  </p>
                  <p>
                    SHA-256 <code>{e.definitionHash}</code>
                  </p>
                  <p>
                    Revision author <code>{e.revisionAuthor}</code>
                  </p>
                </details>
                {manage && e.status === "ACTIVE" && (
                  <>
                    <button
                      className="button"
                      disabled={busy}
                      onClick={() =>
                        setAction({
                          kind: "rotate",
                          id: e.id,
                          revision: e.revision,
                          name: e.name,
                        })
                      }
                    >
                      Rotate key for {e.name}
                    </button>
                    <button
                      className="button"
                      disabled={busy}
                      onClick={() =>
                        setAction({
                          kind: "endpoint",
                          id: e.id,
                          revision: e.revision,
                          name: e.name,
                        })
                      }
                    >
                      Revoke endpoint {e.name}
                    </button>
                  </>
                )}
                {permissions.includes("execution.read") && (
                  <button className="button" onClick={() => setSelected(e)}>
                    Delivery history for {e.name}
                  </button>
                )}
              </article>
            ))}
            <Pager
              page={endpointPage}
              pages={endpoints.data.totalPages}
              change={setEndpointPage}
            />
            <button
              className="button"
              disabled={endpoints.isFetching || busy}
              onClick={() => void client.invalidateQueries({ queryKey: key })}
            >
              Refresh automation configuration
            </button>
          </>
        )}
      </section>
      {action && (
        <section
          className="data-panel"
          role="region"
          aria-label="Confirm automation change"
        >
          <h2>
            {action.kind === "rotate"
              ? "Rotate signing key"
              : "Revoke automation authority"}
          </h2>
          <p>
            {action.name} · expected revision {action.revision}
          </p>
          <p>
            {action.kind === "rotate"
              ? "The previous key will stop accepting deliveries. Previously accepted work retains its original approval exclusions. Copy the new key when it appears."
              : "Future dispatch will stop. Completed effects and historical records remain."}
          </p>
          <button
            className="button"
            disabled={busy}
            onClick={() =>
              void mutate(
                async () => {
                  if (action.kind === "rotate")
                    setReveal(
                      await automationApi.rotate(
                        org,
                        action.id,
                        action.revision,
                      ),
                    );
                  else if (action.kind === "endpoint")
                    await automationApi.revokeEndpoint(
                      org,
                      action.id,
                      action.revision,
                    );
                  else
                    await automationApi.revoke(org, action.id, action.revision);
                },
                action.kind === "rotate"
                  ? "Signing key rotated. No execution was started."
                  : "Authority revoked; history retained.",
              )
            }
          >
            Confirm {action.kind === "rotate" ? "key rotation" : "revocation"}
          </button>
          <button
            className="button"
            disabled={busy}
            onClick={() => setAction(null)}
          >
            Keep current configuration
          </button>
        </section>
      )}
      {selected && (
        <DeliveryHistory
          key={selected.id}
          user={user}
          org={org}
          endpoint={selected}
          close={() => setSelected(null)}
        />
      )}
    </>
  );
}
function SecretReveal({
  value,
  dismiss,
}: {
  value: SigningReveal;
  dismiss: () => void;
}) {
  const [visible, setVisible] = useState(false),
    [copy, setCopy] = useState("");
  async function copyKey() {
    try {
      await navigator.clipboard.writeText(value.signingSecret);
      setCopy("Signing key copied.");
    } catch {
      setCopy("Clipboard unavailable. Reveal and copy the key manually.");
    }
  }
  return (
    <section className="data-panel" aria-label="One-time signing key">
      <h2>Copy the signing key once</h2>
      <p>
        This key will not appear in later reads. Store it securely with the
        sender. Dismissing or leaving this page clears this reveal.
      </p>
      <label>
        Signing key
        <input
          autoComplete="off"
          readOnly
          type={visible ? "text" : "password"}
          value={value.signingSecret}
        />
      </label>
      <label>
        <input
          type="checkbox"
          checked={visible}
          onChange={(e) => setVisible(e.target.checked)}
        />
        Show signing key
      </label>
      <button className="button" onClick={() => void copyKey()}>
        Copy signing key
      </button>
      <button className="button" onClick={dismiss}>
        Dismiss signing key
      </button>
      {copy && <p role="status">{copy}</p>}
      <details>
        <summary>Sender signing protocol</summary>
        <p>
          Send one each of X-BridgeOps-Timestamp (Unix seconds),
          X-BridgeOps-Delivery (unique ASCII ID), and X-BridgeOps-Signature
          (lowercase hex). Decode the base64url key, then HMAC-SHA-256 the exact
          UTF-8 prefix <code>{"v1\\nTIMESTAMP\\nDELIVERY\\n"}</code> followed by
          the raw JSON bytes. Each <code>\n</code> means one LF byte. Maximum
          skew is five minutes; maximum body is 256 KiB. Use{" "}
          <code>{'{"input":{...published fields...}}'}</code>. Acknowledgement
          means durable acceptance; inspect execution history for its result.
        </p>
      </details>
    </section>
  );
}
function PrincipalSetup({
  user,
  org,
  disabled,
  created,
}: {
  user: string;
  org: string;
  disabled: boolean;
  created: () => Promise<void>;
}) {
  const [page, setPage] = useState(0),
    [workflow, setWorkflow] = useState(""),
    [name, setName] = useState(""),
    [confirmed, setConfirmed] = useState(false),
    [busy, setBusy] = useState(false),
    [error, setError] = useState(""),
    [notice, setNotice] = useState("");
  const list = useQuery({
    queryKey: [user, "automation-workflows", org, page],
    queryFn: () => workflowApi.list(org, page),
    retry: false,
    gcTime: 0,
  });
  const snapshot = useQuery({
    queryKey: [user, "automation-snapshot", org, workflow],
    queryFn: () => workflowApi.get(org, workflow),
    enabled: !!workflow,
    retry: false,
    gcTime: 0,
  });
  const version = snapshot.data?.currentPublishedVersion?.id;
  const published = useQuery({
    queryKey: [user, "automation-published", org, workflow, version],
    queryFn: () => workflowApi.version(org, workflow, version!),
    enabled: !!version,
    retry: false,
    gcTime: 0,
  });
  const definition = published.data?.definition as
    { schemaVersion?: number; nodes?: { type?: string }[] } | undefined;
  const [consentedVersion, setConsentedVersion] = useState("");
  const compatible =
    definition?.schemaVersion === 2 &&
    definition.nodes?.some((n) => n.type === "webhookTrigger") &&
    snapshot.data?.workflow.status === "ACTIVE" &&
    published.data?.version;
  async function submit(event: FormEvent) {
    event.preventDefault();
    if (!confirmed || !compatible || consentedVersion !== compatible.id) return;
    setBusy(true);
    setError("");
    try {
      await automationApi.create(org, name, workflow, compatible.id);
      setConfirmed(false);
      setName("");
      setNotice(
        "Scoped principal created. No endpoint, signing key or execution was created.",
      );
      await created();
    } catch (failure) {
      setError(failureMessage(failure));
    } finally {
      setBusy(false);
    }
  }
  return (
    <details>
      <summary>Create a scoped automation principal</summary>
      <form onSubmit={(event) => void submit(event)}>
        <label>
          Principal name
          <input
            required
            maxLength={120}
            value={name}
            onChange={(e) => setName(e.target.value)}
          />
        </label>
        {list.isPending ? (
          <p role="status">Loading published workflows…</p>
        ) : list.isError ? (
          <Problem error={list.error} retry={() => void list.refetch()} />
        ) : (
          <>
            <label>
              Workflow to pin
              <select
                required
                value={workflow}
                onChange={(e) => {
                  setWorkflow(e.target.value);
                  setConfirmed(false);
                }}
              >
                <option value="">Choose a workflow</option>
                {list.data.items
                  .filter(
                    (w) => w.status === "ACTIVE" && w.currentPublishedVersionId,
                  )
                  .map((w) => (
                    <option value={w.id} key={w.id}>
                      {w.name}
                    </option>
                  ))}
              </select>
            </label>
          </>
        )}
        {(snapshot.isFetching || published.isFetching) && (
          <p role="status">Loading the saved published version…</p>
        )}
        {snapshot.isError && (
          <Problem
            error={snapshot.error}
            retry={() => void snapshot.refetch()}
          />
        )}
        {published.isError && (
          <Problem
            error={published.error}
            retry={() => void published.refetch()}
          />
        )}
        {workflow && published.data && !compatible && (
          <p role="alert">
            Choose a published schema-v2 workflow with a webhook trigger.
          </p>
        )}
        {compatible && (
          <>
            <p>
              Pin published version {compatible.versionNumber}; SHA-256{" "}
              <code>{compatible.definitionHash}</code>.
            </p>
            <label>
              <input
                type="checkbox"
                checked={confirmed && consentedVersion === compatible.id}
                onChange={(e) => {
                  setConfirmed(e.target.checked);
                  setConsentedVersion(compatible.id);
                }}
              />
              Grant only this immutable workflow's registered operations.
              Protected writes still require a different eligible human.
            </label>
          </>
        )}
        {error && <p role="alert">{error}</p>}
        {notice && <p role="status">{notice}</p>}
        <button
          className="button"
          disabled={
            disabled ||
            busy ||
            snapshot.isFetching ||
            published.isFetching ||
            !compatible ||
            !confirmed ||
            consentedVersion !== compatible.id
          }
        >
          Create automation principal
        </button>
      </form>
      {list.data && (
        <Pager
          page={page}
          pages={list.data.totalPages}
          change={(next) => {
            setPage(next);
            setWorkflow("");
            setConfirmed(false);
          }}
        />
      )}
    </details>
  );
}
function DeliveryHistory({
  user,
  org,
  endpoint,
  close,
}: {
  user: string;
  org: string;
  endpoint: WebhookEndpoint;
  close: () => void;
}) {
  const [page, setPage] = useState(0);
  const query = useQuery({
    queryKey: [user, "webhook-deliveries", org, endpoint.id, page],
    queryFn: () => automationApi.deliveries(org, endpoint.id, page),
    retry: false,
    gcTime: 0,
  });
  return (
    <section className="data-panel">
      <h2>Accepted deliveries for {endpoint.name}</h2>
      <p>
        Persisted acceptance history. Execution links show current outcomes;
        deliveries do not imply successful execution.
      </p>
      {query.isPending ? (
        <p role="status">Loading delivery history…</p>
      ) : query.isError ? (
        <Problem error={query.error} retry={() => void query.refetch()} />
      ) : (
        <>
          {!query.data.items.length && <p>No accepted deliveries.</p>}
          <ul>
            {query.data.items.map((d) => (
              <li key={d.deliveryId}>
                <Link to={`/organizations/${org}/executions/${d.executionId}`}>
                  {d.deliveryId}
                </Link>{" "}
                · accepted {new Date(d.acceptedAt).toLocaleString()} · endpoint
                revision {d.endpointRevision}
              </li>
            ))}
          </ul>
          <Pager page={page} pages={query.data.totalPages} change={setPage} />
        </>
      )}
      <button
        className="button"
        disabled={query.isFetching}
        onClick={() => void query.refetch()}
      >
        Refresh delivery history
      </button>
      <button className="button" onClick={close}>
        Close delivery history
      </button>
    </section>
  );
}
