import { useState } from "react";
import { Link, useParams } from "react-router-dom";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { failureMessage, type User } from "../../lib/session";
import { useWorkflowAccess } from "../workflows/useWorkflowAccess";
import { Pager, Problem } from "../workflows/Feedback";
import { businessApi } from "./businessApi";

export function BusinessWorkspace({ user }: { user: User }) {
  const { id = "" } = useParams();
  const access = useWorkflowAccess(user.id, id);
  if (access.isPending)
    return <p role="status">Checking business-data access…</p>;
  if (access.isError)
    return <Problem error={access.error} retry={() => void access.refetch()} />;
  if (!access.data.permissions.includes("capability.read"))
    return <p role="alert">Business-data reading is not permitted.</p>;
  return (
    <BusinessData
      key={id}
      user={user}
      org={id}
      canSeed={
        access.data.permissions.includes("integration.manage") &&
        access.data.permissions.includes("capability.write")
      }
    />
  );
}
function BusinessData({
  user,
  org,
  canSeed,
}: {
  user: User;
  org: string;
  canSeed: boolean;
}) {
  const client = useQueryClient();
  const [page, setPage] = useState(0);
  const [notificationPage, setNotificationPage] = useState(0);
  const [status, setStatus] = useState("");
  const [priority, setPriority] = useState("");
  const [category, setCategory] = useState("");
  const [selected, setSelected] = useState("");
  const [confirm, setConfirm] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const [notice, setNotice] = useState("");
  const key = [user.id, "business", org];
  const tickets = useQuery({
    queryKey: [...key, "tickets", page, status, priority, category],
    queryFn: () => businessApi.tickets(org, page, status, priority, category),
    retry: false,
    refetchOnWindowFocus: false,
    gcTime: 0,
  });
  const teams = useQuery({
    queryKey: [...key, "teams"],
    queryFn: () => businessApi.teams(org),
    retry: false,
    refetchOnWindowFocus: false,
    gcTime: 0,
  });
  const mapping = useQuery({
    queryKey: [...key, "responsibilities"],
    queryFn: () => businessApi.responsibilities(org),
    retry: false,
    refetchOnWindowFocus: false,
    gcTime: 0,
  });
  const notifications = useQuery({
    queryKey: [...key, "notifications", notificationPage],
    queryFn: () => businessApi.notifications(org, notificationPage),
    retry: false,
    refetchOnWindowFocus: false,
    gcTime: 0,
  });
  const attempts = useQuery({
    queryKey: [...key, "attempts", selected],
    queryFn: () => businessApi.attempts(org, selected),
    enabled: Boolean(selected),
    retry: false,
    refetchOnWindowFocus: false,
    gcTime: 0,
  });
  const teamName = (id: string | null) =>
    id
      ? (teams.data?.find((t) => t.id === id)?.name ??
        "Team details unavailable")
      : "Unassigned";
  async function seed() {
    setBusy(true);
    setError("");
    setNotice("");
    try {
      const result = await businessApi.seed(org);
      setNotice(
        result.created
          ? `Added ${result.tickets} example tickets, ${result.teams} teams and ${result.notifications} internal notification. No external message was sent.`
          : "Example data was already installed. Existing data was retained.",
      );
      setConfirm(false);
      await client.invalidateQueries({ queryKey: key });
    } catch (failure) {
      setError(failureMessage(failure));
    } finally {
      setBusy(false);
    }
  }
  return (
    <>
      <section className="page-heading">
        <div>
          <p className="eyebrow">PERSISTED BUSINESS DATA</p>
          <h1>Support operations</h1>
          <p>
            Inspect tickets, team responsibilities and internal notification
            history.
          </p>
        </div>
        <Link to={`/organizations/${org}`}>Organization</Link>
      </section>
      <div className="action-row">
        <Link className="button" to={`/organizations/${org}/workflows`}>
          Workflows
        </Link>
        <Link className="button" to={`/organizations/${org}/executions`}>
          Execution history
        </Link>
        <Link className="button" to={`/organizations/${org}/approvals`}>
          Approvals
        </Link>
        <button
          className="button"
          onClick={() => void client.invalidateQueries({ queryKey: key })}
        >
          Refresh business data
        </button>
      </div>
      {notice && <p role="status">{notice}</p>}
      {error && <p role="alert">{error}</p>}
      {canSeed && (
        <section className="data-panel">
          <h2>Example data setup</h2>
          <p>
            Optional examples have SEEDED_EXAMPLE provenance. Setup adds data
            once; it does not reset your organization. Workflow effects are
            recorded separately as LIVE_EXECUTION.
          </p>
          {confirm ? (
            <div>
              <p>
                Add the example tickets, teams, responsibility mappings and one
                internal demo notification?
              </p>
              <button
                className="button primary"
                disabled={busy}
                onClick={() => void seed()}
              >
                Confirm add example data
              </button>
              <button
                className="button"
                disabled={busy}
                onClick={() => setConfirm(false)}
              >
                Keep existing data only
              </button>
            </div>
          ) : (
            <button className="button" onClick={() => setConfirm(true)}>
              Add example data
            </button>
          )}
        </section>
      )}
      <section className="data-panel">
        <h2>Support tickets</h2>
        <div className="action-row">
          <label>
            Status
            <select
              value={status}
              onChange={(e) => {
                setStatus(e.target.value);
                setPage(0);
              }}
            >
              <option value="">All statuses</option>
              {["OPEN", "IN_PROGRESS", "RESOLVED", "CLOSED"].map((v) => (
                <option key={v}>{v}</option>
              ))}
            </select>
          </label>
          <label>
            Priority
            <select
              value={priority}
              onChange={(e) => {
                setPriority(e.target.value);
                setPage(0);
              }}
            >
              <option value="">All priorities</option>
              {["LOW", "NORMAL", "HIGH", "URGENT"].map((v) => (
                <option key={v}>{v}</option>
              ))}
            </select>
          </label>
          <label>
            Category
            <select
              value={category}
              onChange={(e) => {
                setCategory(e.target.value);
                setPage(0);
              }}
            >
              <option value="">All categories</option>
              {["BILLING", "PRODUCT", "ACCESS", "OTHER"].map((v) => (
                <option key={v}>{v}</option>
              ))}
            </select>
          </label>
        </div>
        {tickets.isPending ? (
          <p role="status">Loading tickets…</p>
        ) : tickets.isError ? (
          <Problem error={tickets.error} retry={() => void tickets.refetch()} />
        ) : (
          <>
            {tickets.data.items.length === 0 ? (
              <p>No tickets match these filters.</p>
            ) : (
              tickets.data.items.map((ticket) => (
                <article className="execution-step" key={ticket.id}>
                  <h3>{ticket.title}</h3>
                  <p>
                    {ticket.status} · {ticket.priority} · {ticket.category}
                  </p>
                  <p>
                    Assigned team: {teamName(ticket.assignedTeamId)} · Revision{" "}
                    {ticket.revision}
                  </p>
                  <p>Provenance: {ticket.provenance}</p>
                  <details>
                    <summary>Technical ticket details</summary>
                    <code>{ticket.id}</code>
                    <p>Updated {new Date(ticket.updatedAt).toLocaleString()}</p>
                  </details>
                </article>
              ))
            )}
            <Pager
              page={page}
              pages={tickets.data.totalPages}
              change={setPage}
            />
          </>
        )}
      </section>
      <section className="data-panel">
        <h2>Team directory</h2>
        {teams.isPending ? (
          <p role="status">Loading teams…</p>
        ) : teams.isError ? (
          <Problem error={teams.error} retry={() => void teams.refetch()} />
        ) : teams.data.length === 0 ? (
          <p>No teams are configured.</p>
        ) : (
          teams.data.map((team) => (
            <article key={team.id}>
              <h3>{team.name}</h3>
              <p>{team.provenance}</p>
              <details>
                <summary>Technical team details</summary>
                <code>{team.id}</code>
              </details>
            </article>
          ))
        )}
        <h3>Responsibility mappings</h3>
        {mapping.isPending ? (
          <p>Loading mappings…</p>
        ) : mapping.isError ? (
          <Problem error={mapping.error} retry={() => void mapping.refetch()} />
        ) : mapping.data.length === 0 ? (
          <p>No responsibility mappings are configured.</p>
        ) : (
          <ul>
            {mapping.data.map((item) => (
              <li key={item.category}>
                {item.category} → {teamName(item.teamId)}
              </li>
            ))}
          </ul>
        )}
      </section>
      <section className="data-panel">
        <h2>Internal notifications</h2>
        <p>
          Delivery means persisted availability inside BridgeOps. No email, SMS
          or third-party delivery is implied.
        </p>
        {notifications.isPending ? (
          <p role="status">Loading internal notifications…</p>
        ) : notifications.isError ? (
          <Problem
            error={notifications.error}
            retry={() => void notifications.refetch()}
          />
        ) : (
          <>
            {notifications.data.items.length === 0 ? (
              <p>No internal notifications yet.</p>
            ) : (
              notifications.data.items.map((notification) => (
                <article key={notification.id}>
                  <h3>{notification.message}</h3>
                  <p>
                    {notification.status} · {notification.provenance}
                  </p>
                  <p>{new Date(notification.createdAt).toLocaleString()}</p>
                  <button
                    className="button"
                    onClick={() => setSelected(notification.id)}
                  >
                    Inspect internal delivery
                  </button>
                </article>
              ))
            )}
            <Pager
              page={notificationPage}
              pages={notifications.data.totalPages}
              change={setNotificationPage}
            />
          </>
        )}
        {selected && (
          <div>
            <h3>Persisted delivery attempts</h3>
            {attempts.isPending ? (
              <p>Loading attempts…</p>
            ) : attempts.isError ? (
              <Problem
                error={attempts.error}
                retry={() => void attempts.refetch()}
              />
            ) : (
              attempts.data?.map((attempt) => (
                <p key={attempt.number}>
                  Attempt {attempt.number}: {attempt.outcome} ·{" "}
                  {new Date(attempt.occurredAt).toLocaleString()}
                </p>
              ))
            )}
            <button className="button" onClick={() => setSelected("")}>
              Close delivery details
            </button>
          </div>
        )}
      </section>
    </>
  );
}
