import { useState, type FormEvent } from "react";
import { Link, useNavigate, useParams } from "react-router-dom";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { failureMessage, type User } from "../../lib/session";
import { useWorkflowAccess } from "../workflows/useWorkflowAccess";
import { Pager, Problem } from "../workflows/Feedback";
import {
  ConfirmationDialog,
  EmptyState,
  PageHeader,
  StatusBadge,
} from "../../components/ui";
import {
  connectionApi,
  type ManagedConnection,
  type ConnectionInput,
} from "./connectionApi";
const exampleOperations = [
  {
    id: "health",
    method: "GET",
    path: "/health",
    pathParameters: [],
    requestFields: {},
    responseFields: { message: "STRING" },
    classification: "READ",
    timeoutSeconds: 5,
    maximumResponseBytes: 4096,
    remoteIdempotencyKey: false,
  },
];
export function Connections({ user }: { user: User }) {
  const { id = "" } = useParams();
  const access = useWorkflowAccess(user.id, id);
  if (access.isPending)
    return <p role="status">Checking integration access…</p>;
  if (access.isError)
    return <Problem error={access.error} retry={() => void access.refetch()} />;
  if (!access.data.permissions.includes("integration.read"))
    return <p role="alert">Integration reading is not permitted.</p>;
  return (
    <ConnectionList
      key={id}
      user={user}
      org={id}
      manage={access.data.permissions.includes("integration.manage")}
    />
  );
}
function ConnectionList({
  user,
  org,
  manage,
}: {
  user: User;
  org: string;
  manage: boolean;
}) {
  const [page, setPage] = useState(0);
  const [create, setCreate] = useState(false);
  const navigate = useNavigate();
  const list = useQuery({
    queryKey: [user.id, "connections", org, page],
    queryFn: () => connectionApi.list(org, page),
    retry: false,
    refetchOnWindowFocus: false,
    gcTime: 0,
  });
  const definitions = useQuery({
    queryKey: [user.id, "integration-definitions", org],
    queryFn: () => connectionApi.definitions(org),
    retry: false,
    refetchOnWindowFocus: false,
    gcTime: 0,
  });
  return (
    <>
      <PageHeader
        title="Integrations"
        eyebrow="REGISTERED CAPABILITIES"
        description="Organization-owned connections and their actual persisted status."
        actions={
          <>
            <Link className="button" to={`/organizations/${org}`}>
              Organization
            </Link>
            {manage && (
              <button
                className="button primary"
                onClick={() => setCreate((value) => !value)}
              >
                {create ? "Close connection setup" : "Configure a connection"}
              </button>
            )}
          </>
        }
      />
      <section className="data-panel">
        <h2>Integration catalog</h2>
        {definitions.isPending ? (
          <p>Loading catalog…</p>
        ) : definitions.isError ? (
          <Problem
            error={definitions.error}
            retry={() => void definitions.refetch()}
          />
        ) : (
          <div className="catalog-grid">
            {definitions.data.map((definition) => (
              <article key={definition.type}>
                <h3>{definition.name}</h3>
                <p>{definition.description}</p>
                <p>Implementation version {definition.implementationVersion}</p>
              </article>
            ))}
          </div>
        )}
      </section>
      <section className="data-panel">
        <div className="section-heading">
          <h2>Configured connections</h2>
          <button className="button" onClick={() => void list.refetch()}>
            Refresh connections
          </button>
        </div>
        {list.isPending ? (
          <p role="status">Loading connections…</p>
        ) : list.isError ? (
          <Problem error={list.error} retry={() => void list.refetch()} />
        ) : (
          <>
            {list.data.items.length === 0 ? (
              <EmptyState title="No connections are configured.">
                Connections become available only after an authorized person
                configures them.
              </EmptyState>
            ) : (
              list.data.items.map((connection) => (
                <article key={connection.id}>
                  <h3>
                    <Link
                      to={`/organizations/${org}/integrations/${connection.id}`}
                    >
                      {connection.name}
                    </Link>
                  </h3>
                  <p>{connection.description}</p>
                  <p>
                    {connection.type} ·{" "}
                    <StatusBadge
                      value={connection.enabled ? "ENABLED" : "DISABLED"}
                    />{" "}
                    · revision {connection.revision}
                  </p>
                  <p>
                    Connection check:{" "}
                    <StatusBadge value={connection.health.status} />
                    {connection.health.checkedAt
                      ? ` · ${new Date(connection.health.checkedAt).toLocaleString()}`
                      : ""}
                  </p>
                </article>
              ))
            )}
            <Pager page={page} pages={list.data.totalPages} change={setPage} />
          </>
        )}
      </section>
      {create && manage && (
        <ConnectionForm
          key="create"
          org={org}
          finish={(value) =>
            navigate(
              `/organizations/${org}/integrations/${value.connection.id}`,
            )
          }
        />
      )}
    </>
  );
}
export function ConnectionDetail({ user }: { user: User }) {
  const { id = "", connectionId = "" } = useParams();
  const access = useWorkflowAccess(user.id, id);
  if (access.isPending) return <p role="status">Checking connection access…</p>;
  if (access.isError)
    return <Problem error={access.error} retry={() => void access.refetch()} />;
  if (!access.data.permissions.includes("integration.read"))
    return <p role="alert">Connection reading is not permitted.</p>;
  return (
    <ConnectionData
      key={`${id}/${connectionId}`}
      user={user}
      org={id}
      connectionId={connectionId}
      manage={access.data.permissions.includes("integration.manage")}
    />
  );
}
function ConnectionData({
  user,
  org,
  connectionId,
  manage,
}: {
  user: User;
  org: string;
  connectionId: string;
  manage: boolean;
}) {
  const client = useQueryClient();
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const [notice, setNotice] = useState("");
  const summaryKey = [user.id, "connection", org, connectionId];
  const [checkRevision, setCheckRevision] = useState<number | null>(null);
  const managedKey = [user.id, "connection-configuration", org, connectionId];
  const summary = useQuery({
    queryKey: summaryKey,
    queryFn: () => connectionApi.get(org, connectionId),
    retry: false,
    refetchOnWindowFocus: false,
    gcTime: 0,
  });
  const operations = useQuery({
    queryKey: [user.id, "connection-operations", org, connectionId],
    queryFn: () => connectionApi.operations(org, connectionId),
    retry: false,
    refetchOnWindowFocus: false,
    gcTime: 0,
  });
  const managed = useQuery({
    queryKey: managedKey,
    queryFn: () => connectionApi.configuration(org, connectionId),
    enabled: manage,
    retry: false,
    refetchOnWindowFocus: false,
    gcTime: 0,
  });
  async function check() {
    if (checkRevision === null) return;
    setBusy(true);
    setError("");
    setNotice("");
    try {
      const result = await connectionApi.check(
        org,
        connectionId,
        checkRevision,
      );
      client.setQueryData(summaryKey, result);
      setNotice(`Recorded connection check: ${result.health.status}.`);
      setCheckRevision(null);
    } catch (failure) {
      setError(failureMessage(failure));
    } finally {
      setBusy(false);
    }
  }
  if (summary.isPending) return <p role="status">Loading connection…</p>;
  if (summary.isError)
    return (
      <Problem error={summary.error} retry={() => void summary.refetch()} />
    );
  return (
    <>
      <PageHeader
        title={summary.data.name}
        eyebrow="CONFIGURED INTEGRATION"
        description={summary.data.description}
        actions={
          <Link className="button" to={`/organizations/${org}/integrations`}>
            All integrations
          </Link>
        }
      />
      <section className="data-panel">
        <h2>Connection state</h2>
        <p>
          {summary.data.type} · implementation v
          {summary.data.implementationVersion} · revision{" "}
          {summary.data.revision} ·{" "}
          <StatusBadge value={summary.data.enabled ? "ENABLED" : "DISABLED"} />
        </p>
        <p>
          Health: <StatusBadge value={summary.data.health.status} />
          {summary.data.health.code ? ` · ${summary.data.health.code}` : ""}
        </p>
        {summary.data.health.checkedAt && (
          <p>
            Checked {new Date(summary.data.health.checkedAt).toLocaleString()}{" "}
            against revision {summary.data.health.revision}.
          </p>
        )}
        {summary.data.health.status === "STALE" && (
          <p role="status">
            This check belongs to an older connection revision. Check the
            current saved configuration again.
          </p>
        )}
        {manage && (
          <button
            className="button"
            disabled={
              busy ||
              !summary.data.enabled ||
              !managed.data ||
              managed.isError ||
              managed.data.connection.revision !== summary.data.revision ||
              (summary.data.type === "REST" &&
                !managed.data.configuration.healthOperation)
            }
            onClick={() => setCheckRevision(summary.data.revision)}
          >
            Check saved connection
          </button>
        )}
        <button className="button" onClick={() => void summary.refetch()}>
          Refresh connection state
        </button>
        {error && checkRevision === null && <p role="alert">{error}</p>}
        {notice && <p role="status">{notice}</p>}
        {checkRevision !== null && (
          <ConfirmationDialog
            role="alertdialog"
            label="Confirm saved connection check"
            onCancel={() => setCheckRevision(null)}
            busy={busy}
          >
            <h3>Check this saved revision?</h3>
            {error && <p role="alert">{error}</p>}
            <p>Connection revision {checkRevision}.</p>
            {summary.data.type === "REST" ? (
              <>
                <p>
                  Destination:{" "}
                  {String(managed.data?.configuration.baseDestination ?? "")}
                </p>
                <p>
                  Registered read operation:{" "}
                  {String(managed.data?.configuration.healthOperation ?? "")}
                </p>
                <p>
                  This contacts the configured external API using its stored
                  credential when configured. It is a real request.
                </p>
              </>
            ) : (
              <p>
                This checks the internal BridgeOps service. No external provider
                is contacted.
              </p>
            )}
            <button
              className="button primary"
              disabled={busy}
              onClick={() => void check()}
            >
              Confirm connection check
            </button>
            <button
              className="button"
              disabled={busy}
              onClick={() => setCheckRevision(null)}
            >
              Cancel connection check
            </button>
          </ConfirmationDialog>
        )}
      </section>
      <section className="data-panel">
        <h2>Registered operations</h2>
        {operations.isPending ? (
          <p>Loading operations…</p>
        ) : operations.isError ? (
          <Problem
            error={operations.error}
            retry={() => void operations.refetch()}
          />
        ) : operations.data.length === 0 ? (
          <p>No operations are registered for this connection.</p>
        ) : (
          operations.data.map((operation) => (
            <article key={operation.id}>
              <h3>{operation.id}</h3>
              <p>
                {operation.classification} ·{" "}
                {operation.requiresApproval
                  ? "Distinct human approval required"
                  : "Subject to the operation's central policy rule"}
              </p>
              <details>
                <summary>Operation input and output fields</summary>
                <pre className="definition-json">
                  {JSON.stringify(
                    {
                      input: operation.requestFields,
                      output: operation.responseFields,
                    },
                    null,
                    2,
                  )}
                </pre>
              </details>
            </article>
          ))
        )}
      </section>
      {manage &&
        (managed.isPending ? (
          <p>Loading saved configuration…</p>
        ) : managed.isError ? (
          <Problem error={managed.error} retry={() => void managed.refetch()} />
        ) : (
          <ConnectionForm
            key={connectionId}
            org={org}
            initial={managed.data}
            finish={(value) => {
              client.setQueryData(summaryKey, value.connection);
              client.setQueryData(managedKey, value);
              void operations.refetch();
            }}
          />
        ))}
    </>
  );
}
function ConnectionForm({
  org,
  initial,
  finish,
}: {
  org: string;
  initial?: ManagedConnection;
  finish: (value: ManagedConnection) => void;
}) {
  const [expected, setExpected] = useState(initial?.connection.revision ?? 0);
  const [name, setName] = useState(initial?.connection.name ?? "");
  const [description, setDescription] = useState(
    initial?.connection.description ?? "",
  );
  const [type, setType] = useState(initial?.connection.type ?? "SUPPORT");
  const [enabled, setEnabled] = useState(initial?.connection.enabled ?? true);
  const [base, setBase] = useState(
    typeof initial?.configuration.baseDestination === "string"
      ? initial.configuration.baseDestination
      : "",
  );
  const [health, setHealth] = useState(
    typeof initial?.configuration.healthOperation === "string"
      ? initial.configuration.healthOperation
      : "",
  );
  const [operations, setOperations] = useState(
    JSON.stringify(
      initial?.configuration.operations ?? exampleOperations,
      null,
      2,
    ),
  );
  const [credential, setCredential] = useState("");
  const [remove, setRemove] = useState(false);
  const [configured, setConfigured] = useState(
    initial?.credentialConfigured ?? false,
  );
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const [notice, setNotice] = useState("");
  const [reload, setReload] = useState(false);
  function adopt(value: ManagedConnection) {
    setExpected(value.connection.revision);
    setName(value.connection.name);
    setDescription(value.connection.description);
    setType(value.connection.type);
    setEnabled(value.connection.enabled);
    setConfigured(value.credentialConfigured);
    setBase(
      typeof value.configuration.baseDestination === "string"
        ? value.configuration.baseDestination
        : "",
    );
    setHealth(
      typeof value.configuration.healthOperation === "string"
        ? value.configuration.healthOperation
        : "",
    );
    setOperations(
      JSON.stringify(
        value.configuration.operations ?? exampleOperations,
        null,
        2,
      ),
    );
    setCredential("");
    setRemove(false);
    setReload(false);
    finish(value);
  }
  async function submit(event: FormEvent) {
    event.preventDefault();
    setBusy(true);
    setError("");
    setNotice("");
    try {
      let configuration: Record<string, unknown> = {};
      if (type === "REST") {
        const registered: unknown = JSON.parse(operations);
        if (
          !Array.isArray(registered) ||
          registered.length < 1 ||
          registered.length > 20
        )
          throw new Error("Invalid operation configuration");
        configuration = {
          baseDestination: base,
          healthOperation: health || null,
          operations: registered,
        };
      }
      const value: ConnectionInput = {
        name,
        description,
        type,
        configuration,
        ...(credential ? { credential } : {}),
      };
      const result = initial
        ? await connectionApi.update(org, initial.connection.id, {
            name,
            description,
            configuration,
            expectedRevision: expected,
            enabled,
            removeCredential: remove,
            ...(credential ? { credential } : {}),
          })
        : await connectionApi.create(org, value);
      adopt(result);
      setNotice("Connection configuration saved. No operation was executed.");
    } catch (failure) {
      setError(failureMessage(failure));
    } finally {
      setCredential("");
      setBusy(false);
    }
  }
  async function reloadSaved() {
    if (!initial) return;
    setBusy(true);
    setError("");
    try {
      adopt(await connectionApi.configuration(org, initial.connection.id));
      setNotice("Loaded the current saved configuration.");
    } catch (failure) {
      setError(failureMessage(failure));
    } finally {
      setBusy(false);
    }
  }
  return (
    <section className="data-panel">
      <h2>{initial ? "Manage connection" : "New connection"}</h2>
      <p>
        Names and descriptions are visible to organization members. Destinations
        and configuration require integration-management permission.
      </p>
      {error && (
        <p role="alert">
          {error} Configuration edits are retained; credential input is cleared
          after each submission. Reload the saved connection to check its
          current state before retrying.
        </p>
      )}
      {notice && <p role="status">{notice}</p>}
      <form onSubmit={(event) => void submit(event)}>
        <label>
          Connection name
          <input
            required
            maxLength={120}
            value={name}
            onChange={(e) => setName(e.target.value)}
            disabled={busy}
          />
        </label>
        <label>
          Connection description
          <textarea
            maxLength={1000}
            value={description}
            onChange={(e) => setDescription(e.target.value)}
            disabled={busy}
          />
        </label>
        <label>
          Integration type
          <select
            value={type}
            disabled={busy || Boolean(initial)}
            onChange={(e) => {
              setType(e.target.value);
              setCredential("");
              setRemove(false);
            }}
          >
            {["SUPPORT", "TEAMS", "NOTIFICATIONS", "REST"].map((value) => (
              <option key={value}>{value}</option>
            ))}
          </select>
        </label>
        {initial && (
          <label>
            <input
              type="checkbox"
              checked={enabled}
              disabled={busy}
              onChange={(e) => setEnabled(e.target.checked)}
            />
            Connection enabled
          </label>
        )}
        {type === "REST" && (
          <>
            <p>
              HTTPS on an operator-approved public host is required. Workflow
              nodes cannot supply arbitrary URLs, methods or headers. Registered
              writes require approval.
            </p>
            <label>
              HTTPS base destination
              <input
                type="url"
                required
                value={base}
                disabled={busy}
                onChange={(e) => setBase(e.target.value)}
                placeholder="https://api.example.com"
              />
            </label>
            <label>
              Registered operations (JSON)
              <textarea
                className="definition-json"
                rows={16}
                maxLength={60000}
                value={operations}
                disabled={busy}
                onChange={(e) => setOperations(e.target.value)}
              />
            </label>
            <p>
              Up to 20 operations. Declare a fixed method/path, path-parameter
              names, flat STRING/INTEGER/BOOLEAN request and response fields,
              READ/WRITE classification, 1–10 second timeout, 256–1048576 byte
              response limit and remote-idempotency-key support. No credentials
              belong in this JSON.
            </p>
            <label>
              Read-only health operation ID
              <input
                maxLength={48}
                value={health}
                disabled={busy}
                onChange={(e) => setHealth(e.target.value)}
              />
            </label>
            <p>
              Optional; it must identify a GET operation with no required path
              parameters.
            </p>
            <fieldset className="connection-credential">
              <legend>Credential entry</legend>
              <label>
                New bearer credential
                <input
                  type="password"
                  autoComplete="off"
                  maxLength={4096}
                  value={credential}
                  disabled={busy || remove}
                  onChange={(e) => setCredential(e.target.value)}
                />
              </label>
              <p>
                {configured
                  ? "A credential is stored. Leave this empty to retain it; saved credentials are never returned."
                  : "No credential is stored. Leave empty for an unauthenticated registered operation."}
              </p>
              {initial && (
                <label>
                  <input
                    type="checkbox"
                    checked={remove}
                    disabled={busy || Boolean(credential)}
                    onChange={(e) => setRemove(e.target.checked)}
                  />
                  Remove the stored credential reference
                </label>
              )}
            </fieldset>
          </>
        )}
        <button className="button primary" disabled={busy}>
          Save connection configuration
        </button>
      </form>
      {initial &&
        (reload ? (
          <div>
            <p>Discard unapplied edits and load the current saved revision?</p>
            <button
              className="button"
              disabled={busy}
              onClick={() => void reloadSaved()}
            >
              Confirm reload connection
            </button>
            <button
              className="button"
              disabled={busy}
              onClick={() => setReload(false)}
            >
              Keep connection edits
            </button>
          </div>
        ) : (
          <button
            className="button"
            disabled={busy}
            onClick={() => setReload(true)}
          >
            Reload saved connection
          </button>
        ))}
    </section>
  );
}
