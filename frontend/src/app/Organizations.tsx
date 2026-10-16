import { useState, type FormEvent, type ReactNode } from "react";
import { Link, useNavigate, useParams } from "react-router-dom";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { session, failureMessage, type User } from "../lib/session";
interface Organization {
  id: string;
  name: string;
  slug: string;
  revision: number;
}
interface Role {
  id: string;
  name: string;
  assignable: boolean;
}
interface Member {
  id: string;
  userId: string;
  status: string;
  revision: number;
  roles: Role[];
}
interface Invitation {
  id: string;
  email: string;
  expiresAt: string;
  status: string;
  roleIds: string[];
}
interface Page<T> {
  items: T[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
}
interface Access {
  membershipId: string;
  permissions: string[];
}
export function ActionForm({
  children,
  submit,
  label,
  onSuccess,
}: {
  children: ReactNode;
  submit: (data: FormData) => Promise<unknown>;
  label: string;
  onSuccess?: () => void;
}) {
  const [pending, setPending] = useState(false);
  const [error, setError] = useState("");
  const [done, setDone] = useState(false);
  async function send(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const form = event.currentTarget;
    setPending(true);
    setError("");
    setDone(false);
    try {
      await submit(new FormData(form));
      setDone(true);
      onSuccess?.();
    } catch (failure) {
      setError(failureMessage(failure));
    } finally {
      for (const input of form.querySelectorAll<HTMLInputElement>(
        'input[type="password"],input[name="token"]',
      ))
        input.value = "";
      setPending(false);
    }
  }
  return (
    <form onSubmit={(event) => void send(event)}>
      {children}
      {error && (
        <p role="alert" className="form-error">
          {error}
        </p>
      )}
      {done && <p role="status">Completed successfully.</p>}
      <button className="button primary" disabled={pending}>
        {pending ? "Submitting…" : label}
      </button>
    </form>
  );
}
function Problem({ error, retry }: { error: unknown; retry: () => void }) {
  return (
    <div className="error-panel" role="alert">
      <p>{failureMessage(error)}</p>
      <button className="button" onClick={retry}>
        Try again
      </button>
    </div>
  );
}
function Paging({
  page,
  total,
  setPage,
}: {
  page: number;
  total: number;
  setPage: (page: number) => void;
}) {
  return (
    <div className="action-row pagination">
      <button
        className="button"
        disabled={page === 0}
        onClick={() => setPage(page - 1)}
      >
        Previous page
      </button>
      <span>
        Page {page + 1} of {Math.max(1, total)}
      </span>
      <button
        className="button"
        disabled={page + 1 >= total}
        onClick={() => setPage(page + 1)}
      >
        Next page
      </button>
    </div>
  );
}
const json = (body: unknown) => ({
  method: "POST",
  headers: { "Content-Type": "application/json" },
  body: JSON.stringify(body),
});
export function Organizations({ user }: { user: User }) {
  const [page, setPage] = useState(0);
  const navigate = useNavigate();
  const client = useQueryClient();
  const organizations = useQuery({
    queryKey: [user.id, "organizations", page],
    queryFn: () =>
      session.request<Page<Organization>>(
        `/api/v1/organizations?page=${page}&size=10`,
      ),
    retry: false,
  });
  return (
    <>
      <div className="page-heading">
        <div>
          <div className="eyebrow">YOUR OPERATIONS, CONNECTED</div>
          <h1>Organizations</h1>
          <p>
            Select an organization to view members and manage available access.
          </p>
        </div>
        <Link className="button" to="/invitations/accept">
          Accept an invitation
        </Link>
      </div>
      {organizations.isPending ? (
        <p role="status">Loading organizations…</p>
      ) : organizations.isError ? (
        <Problem
          error={organizations.error}
          retry={() => void organizations.refetch()}
        />
      ) : (
        <>
          <section className="data-panel">
            <div className="section-heading">
              <h2>Your organizations</h2>
              <span>{organizations.data.totalElements} total</span>
            </div>
            {organizations.data.items.length === 0 ? (
              <div className="empty-state">
                <h3>Your workspace starts here</h3>
                <p>
                  You have no active organization memberships. Create an
                  organization if your account is authorized, or accept an
                  invitation sent to your email.
                </p>
              </div>
            ) : (
              <div className="organization-list">
                {organizations.data.items.map((org) => (
                  <Link
                    key={org.id}
                    className="organization-row"
                    to={`/organizations/${org.id}`}
                  >
                    <span className="workspace-avatar">
                      {org.name.charAt(0)}
                    </span>
                    <span>
                      <strong>{org.name}</strong>
                      <small>{org.slug}</small>
                    </span>
                    <span aria-hidden="true">→</span>
                  </Link>
                ))}
              </div>
            )}
          </section>
          <Paging
            page={page}
            total={organizations.data.totalPages}
            setPage={setPage}
          />
        </>
      )}
      {user.canCreateOrganizations && (
        <section className="data-panel">
          <h2>Create an organization</h2>
          <p className="muted">
            Your account is authorized to create an organization. You will
            become its first Owner.
          </p>
          <ActionForm
            label="Create organization"
            submit={async (data) => {
              const org = await session.request<Organization>(
                "/api/v1/organizations",
                json({
                  name: String(data.get("name")),
                  slug: String(data.get("slug")),
                }),
              );
              await client.invalidateQueries({
                queryKey: [user.id, "organizations"],
              });
              navigate(`/organizations/${org.id}`);
            }}
          >
            <label>
              Organization name
              <input name="name" required maxLength={120} />
            </label>
            <label>
              Organization slug
              <input
                name="slug"
                required
                minLength={3}
                maxLength={64}
                pattern="[a-z0-9]+(-[a-z0-9]+)*"
                aria-describedby="slug-help"
              />
            </label>
            <small id="slug-help">
              At least 3 lowercase letters, numbers or single hyphens. Must be
              unique.
            </small>
          </ActionForm>
        </section>
      )}
      <p className="muted">
        Workflow execution, integrations and approvals will be available in
        later phases.
      </p>
    </>
  );
}
export function OrganizationDetail({ user }: { user: User }) {
  const { id = "" } = useParams();
  const org = encodeURIComponent(id);
  const [page, setPage] = useState(0);
  const [invitePage, setInvitePage] = useState(0);
  const client = useQueryClient();
  const key = [user.id, "organization", id];
  const detail = useQuery({
    queryKey: [...key, "detail"],
    queryFn: () =>
      session.request<Organization>(`/api/v1/organizations/${org}`),
    retry: false,
  });
  const access = useQuery({
    queryKey: [...key, "access"],
    queryFn: () =>
      session.request<Access>(`/api/v1/organizations/${org}/access`),
    retry: false,
  });
  const permissions = access.data?.permissions ?? [];
  const readable = permissions.includes("membership.read");
  const invite = permissions.includes("membership.invite");
  const members = useQuery({
    queryKey: [...key, "members", page],
    queryFn: () =>
      session.request<Page<Member>>(
        `/api/v1/organizations/${org}/members?page=${page}&size=10`,
      ),
    enabled: readable,
    retry: false,
  });
  const roles = useQuery({
    queryKey: [...key, "roles"],
    queryFn: () =>
      session.request<Role[]>(`/api/v1/organizations/${org}/roles`),
    enabled: readable,
    retry: false,
  });
  const invitations = useQuery({
    queryKey: [...key, "invitations", invitePage],
    queryFn: () =>
      session.request<Page<Invitation>>(
        `/api/v1/organizations/${org}/invitations?page=${invitePage}&size=10`,
      ),
    enabled: invite,
    retry: false,
  });
  const [error, setError] = useState("");
  const [busy, setBusy] = useState(false);
  const [notice, setNotice] = useState("");
  const reload = () => {
    void client.invalidateQueries({ queryKey: key });
  };
  async function revoke(invitation: Invitation) {
    setBusy(true);
    setError("");
    try {
      await session.request(
        `/api/v1/organizations/${org}/invitations/${invitation.id}`,
        { method: "DELETE" },
      );
      setNotice("Invitation revoked.");
      reload();
    } catch (failure) {
      setError(failureMessage(failure));
    } finally {
      setBusy(false);
    }
  }
  if (detail.isError || access.isError)
    return (
      <Problem
        error={detail.error ?? access.error}
        retry={() => {
          void detail.refetch();
          void access.refetch();
        }}
      />
    );
  if (!detail.data || !access.data)
    return <p role="status">Loading organization…</p>;
  return (
    <>
      <Link className="text-link" to="/">
        ← All organizations
      </Link>
      <div className="page-heading">
        <div>
          <div className="eyebrow">ORGANIZATION</div>
          <h1>{detail.data.name}</h1>
          <p>
            {detail.data.slug} · <span className="mono">{detail.data.id}</span>
          </p>
        </div>
        <button className="button" onClick={reload}>
          Reload access & data
        </button>
      </div>
      {notice && <p role="status">{notice}</p>}
      {error && (
        <p role="alert" className="form-error">
          {error}
        </p>
      )}
      <section className="data-panel">
        <div className="section-heading">
          <h2>Members</h2>
          <span>{members.data?.totalElements ?? "—"} total</span>
        </div>
        {!readable ? (
          <p>Member information is unavailable to your current permissions.</p>
        ) : members.isError ? (
          <Problem error={members.error} retry={() => void members.refetch()} />
        ) : !members.data ? (
          <p role="status">Loading members…</p>
        ) : (
          <>
            {members.data.items.map((member) => (
              <MemberRow
                key={`${member.id}-${member.revision}`}
                org={org}
                member={member}
                roles={roles.data ?? []}
                manage={
                  permissions.includes("membership.manage") &&
                  (permissions.includes("membership.grant_privileged") ||
                    !member.roles.some((role) =>
                      ["OWNER", "ADMIN"].includes(role.name),
                    ))
                }
                reload={reload}
              />
            ))}
            <Paging
              page={page}
              total={members.data.totalPages}
              setPage={setPage}
            />
          </>
        )}
      </section>
      {readable && (
        <section className="data-panel">
          <h2>Tenant roles</h2>
          {roles.isError ? (
            <Problem error={roles.error} retry={() => void roles.refetch()} />
          ) : (
            <p>
              {roles.data?.map((role) => role.name).join(" · ") ??
                "Loading roles…"}
            </p>
          )}
          <p className="muted">
            Only roles within your current delegation ceiling are selectable.
            All changes are rechecked by the server.
          </p>
        </section>
      )}
      {invite && (
        <section className="data-panel">
          <h2>Invite a person</h2>
          <p>
            Invitations are bound to the recipient email and expire after 48
            hours. External SMTP must be configured.
          </p>
          <ActionForm
            label="Send invitation"
            onSuccess={reload}
            submit={(data) =>
              session.request(
                `/api/v1/organizations/${org}/invitations`,
                json({
                  email: String(data.get("email")),
                  roleIds: data.getAll("roleIds"),
                }),
              )
            }
          >
            <label>
              Recipient email
              <input name="email" type="email" required maxLength={254} />
            </label>
            <RoleChoices roles={roles.data ?? []} />
          </ActionForm>
          <h2>Invitations</h2>
          {invitations.isError ? (
            <Problem
              error={invitations.error}
              retry={() => void invitations.refetch()}
            />
          ) : !invitations.data ? (
            <p role="status">Loading invitations…</p>
          ) : (
            <>
              {invitations.data.items.length === 0 && (
                <p>No invitations have been issued.</p>
              )}
              <div className="table-scroll">
                <table>
                  <thead>
                    <tr>
                      <th>Recipient</th>
                      <th>Status</th>
                      <th>Expires</th>
                      <th>Action</th>
                    </tr>
                  </thead>
                  <tbody>
                    {invitations.data.items.map((item) => (
                      <tr key={item.id}>
                        <td>{item.email}</td>
                        <td>{item.status}</td>
                        <td>{new Date(item.expiresAt).toLocaleString()}</td>
                        <td>
                          {item.status === "PENDING" && (
                            <button
                              className="button danger"
                              disabled={
                                busy ||
                                !item.roleIds.every((role) =>
                                  roles.data?.some(
                                    (r) => r.id === role && r.assignable,
                                  ),
                                )
                              }
                              onClick={() => void revoke(item)}
                            >
                              Revoke invitation
                            </button>
                          )}
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
              <Paging
                page={invitePage}
                total={invitations.data.totalPages}
                setPage={setInvitePage}
              />
            </>
          )}
        </section>
      )}
    </>
  );
}
function RoleChoices({
  roles,
  selected = [],
}: {
  roles: Role[];
  selected?: string[];
}) {
  return (
    <fieldset>
      <legend>Assigned roles (select at least one)</legend>
      {roles
        .filter((role) => role.assignable)
        .map((role) => (
          <label className="check-label" key={role.id}>
            <input
              type="checkbox"
              name="roleIds"
              value={role.id}
              defaultChecked={selected.includes(role.id)}
            />
            {role.name}
          </label>
        ))}
    </fieldset>
  );
}
function MemberRow({
  org,
  member,
  roles,
  manage,
  reload,
}: {
  org: string;
  member: Member;
  roles: Role[];
  manage: boolean;
  reload: () => void;
}) {
  const [editing, setEditing] = useState(false);
  const [confirm, setConfirm] = useState(false);
  const [error, setError] = useState("");
  const [busy, setBusy] = useState(false);
  async function remove() {
    setBusy(true);
    setError("");
    try {
      await session.request(
        `/api/v1/organizations/${org}/members/${member.id}`,
        { method: "DELETE", headers: { "If-Match": `"${member.revision}"` } },
      );
      reload();
    } catch (failure) {
      setError(failureMessage(failure));
    } finally {
      setBusy(false);
    }
  }
  return (
    <article className="member-row">
      <div>
        <strong className="mono">{member.userId}</strong>
        <p>
          {member.roles.map((role) => role.name).join(", ")} · {member.status} ·
          Revision {member.revision}
        </p>
      </div>
      {manage && (
        <div className="action-row">
          <button className="button" onClick={() => setEditing(!editing)}>
            {editing ? "Cancel editing" : "Edit member"}
          </button>
          <button
            className="button danger"
            onClick={() => setConfirm(!confirm)}
          >
            Remove member
          </button>
        </div>
      )}
      {error && <p role="alert">{error}</p>}
      {confirm && (
        <div className="confirmation">
          <p>
            Remove this membership? This removes tenant access, not the person's
            account. At least one active Owner must remain.
          </p>
          <button
            className="button danger"
            disabled={busy}
            onClick={() => void remove()}
          >
            Confirm removal
          </button>
          <button className="button" onClick={() => setConfirm(false)}>
            Cancel removal
          </button>
        </div>
      )}
      {editing && (
        <ActionForm
          label="Save membership"
          onSuccess={reload}
          submit={(data) =>
            session.request(
              `/api/v1/organizations/${org}/members/${member.id}`,
              {
                ...json({
                  expectedRevision: member.revision,
                  status: String(data.get("status")),
                  roleIds: data.getAll("roleIds"),
                }),
                method: "PATCH",
              },
            )
          }
        >
          <label>
            Membership status
            <select name="status" defaultValue={member.status}>
              <option>ACTIVE</option>
              <option>SUSPENDED</option>
            </select>
          </label>
          <RoleChoices
            roles={roles}
            selected={member.roles.map((role) => role.id)}
          />
          <small>
            Changes may be rejected if the revision changed or the last active
            Owner would be removed.
          </small>
        </ActionForm>
      )}
    </article>
  );
}
export function InvitationJourney({ enroll = false }: { enroll?: boolean }) {
  const [done, setDone] = useState(false);
  return (
    <section className="auth-card">
      <h1>{enroll ? "Join by invitation" : "Accept your invitation"}</h1>
      {done ? (
        <>
          <p role="status">
            {enroll
              ? "Your account is ready. Sign in explicitly to continue."
              : "Invitation accepted. Your organization is now available."}
          </p>
          <Link className="button primary" to={enroll ? "/sign-in" : "/"}>
            {enroll ? "Continue to sign in" : "View organizations"}
          </Link>
        </>
      ) : (
        <>
          <p>
            {enroll
              ? "Create your first account using the token emailed to your address."
              : "Sign in using the invited account, then enter the token from your invitation email."}{" "}
            Tokens never belong in a URL.
          </p>
          <ActionForm
            label={enroll ? "Create invited account" : "Accept invitation"}
            onSuccess={() => setDone(true)}
            submit={(data) =>
              enroll
                ? session.publicRequest("/api/v1/invitations/enroll", {
                    token: String(data.get("token")),
                    email: String(data.get("email")),
                    displayName: String(data.get("displayName")),
                    password: String(data.get("password")),
                  })
                : session.request(
                    "/api/v1/invitations/accept",
                    json({ token: String(data.get("token")) }),
                  )
            }
          >
            <label>
              Invitation token
              <input
                name="token"
                autoComplete="off"
                required
                minLength={43}
                maxLength={43}
              />
            </label>
            {enroll && (
              <>
                <label>
                  Email
                  <input
                    name="email"
                    type="email"
                    autoComplete="username"
                    required
                    maxLength={254}
                  />
                </label>
                <label>
                  Display name
                  <input
                    name="displayName"
                    autoComplete="name"
                    required
                    maxLength={120}
                  />
                </label>
                <label>
                  Password
                  <input
                    name="password"
                    type="password"
                    autoComplete="new-password"
                    required
                    minLength={12}
                    maxLength={72}
                  />
                </label>
                <small>At least 12 characters, at most 72 UTF-8 bytes.</small>
              </>
            )}
          </ActionForm>
          <p className="muted">
            Invalid, expired, revoked, already-used or mismatched invitations
            share a safe rejection. Ask the sender for a new invitation when
            needed.
          </p>
        </>
      )}
      <Link className="text-link" to="/sign-in">
        Back to sign in
      </Link>
    </section>
  );
}
