import { useState } from "react";
import { Link, useNavigate, useParams } from "react-router-dom";
import { useQuery } from "@tanstack/react-query";
import { session } from "../../lib/session";
import type { User } from "../../lib/session";
import { ActionForm } from "../../app/Organizations";
import { workflowApi } from "./api";
import { emptyGraph } from "./model";
import type { Page } from "./model";
import { useWorkflowAccess } from "./useWorkflowAccess";
import { Pager, Problem } from "./Feedback";

export function WorkflowHome({ user }: { user: User }) {
  const [page, setPage] = useState(0);
  const organizations = useQuery({
    queryKey: [user.id, "workflow-organizations", page],
    queryFn: () =>
      session.request<Page<{ id: string; name: string; slug: string }>>(
        `/api/v1/organizations?page=${page}&size=10`,
      ),
    retry: false,
  });
  return (
    <>
      <div className="page-heading">
        <div>
          <h1>Workflows</h1>
          <p>
            Choose an organization to author and inspect its workflow
            definitions.
          </p>
        </div>
        <Link className="button" to="/">
          Manage organizations
        </Link>
      </div>
      <p className="phase-note">
        Publication freezes a version. Start supported internal executions
        explicitly from a published workflow.
      </p>
      {organizations.isPending ? (
        <p role="status">Loading organizations…</p>
      ) : organizations.isError ? (
        <Problem
          error={organizations.error}
          retry={() => void organizations.refetch()}
        />
      ) : (
        <section className="data-panel">
          <h2>Your organizations</h2>
          {organizations.data.items.length === 0 ? (
            <p>
              No active organization memberships. Create an organization if
              authorized, or accept an invitation.
            </p>
          ) : (
            <div className="organization-list">
              {organizations.data.items.map((organization) => (
                <Link
                  className="organization-row"
                  key={organization.id}
                  to={`/organizations/${organization.id}/workflows`}
                >
                  <strong>{organization.name}</strong>
                  <span>{organization.slug}</span>
                  <span aria-hidden="true">→</span>
                </Link>
              ))}
            </div>
          )}
          <Pager
            page={page}
            pages={organizations.data.totalPages}
            change={setPage}
          />
        </section>
      )}
    </>
  );
}
export function WorkflowList({ user }: { user: User }) {
  const { id = "" } = useParams();
  return <OrganizationWorkflows key={id} organization={id} user={user} />;
}
function OrganizationWorkflows({
  organization,
  user,
}: {
  organization: string;
  user: User;
}) {
  const [page, setPage] = useState(0);
  const navigate = useNavigate();
  const access = useWorkflowAccess(user.id, organization);
  const readable = access.data?.permissions.includes("workflow.read") ?? false;
  const list = useQuery({
    queryKey: [user.id, "workflows", organization, page],
    queryFn: () => workflowApi.list(organization, page),
    retry: false,
    enabled: readable,
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
  return (
    <>
      <div className="page-heading">
        <div>
          <h1>Organization workflows</h1>
          <p>
            Create editable drafts and inspect immutable published definitions.
          </p>
        </div>
        <div className="action-row">
          <Link className="button" to="/workflows">
            Change organization
          </Link>
          <Link className="button" to={`/organizations/${organization}`}>
            Organization settings
          </Link>
        </div>
      </div>
      <p className="phase-note">
        Publication does not start an execution. Explicit runs support internal
        records only; no external integration or approval decision is performed.
      </p>
      <section className="data-panel">
        <div className="section-heading">
          <h2>Workflow definitions</h2>
          {list.data && <span>{list.data.totalElements} total</span>}
        </div>
        {list.isPending ? (
          <p role="status">Loading workflows…</p>
        ) : list.isError ? (
          <Problem error={list.error} retry={() => void list.refetch()} />
        ) : (
          <>
            {list.data.items.length === 0 ? (
              <div className="empty-state">
                <h3>No workflows yet</h3>
                <p>
                  {access.data.permissions.includes("workflow.edit")
                    ? "Create your first draft below."
                    : "An authorized builder can create a workflow for this organization."}
                </p>
              </div>
            ) : (
              <div className="workflow-list">
                {list.data.items.map((workflow) => (
                  <article className="workflow-list-row" key={workflow.id}>
                    <div>
                      <Link
                        to={`/organizations/${organization}/workflows/${workflow.id}`}
                      >
                        <strong>{workflow.name}</strong>
                      </Link>
                      <p>{workflow.description || "No description"}</p>
                      <small>
                        Updated{" "}
                        <time dateTime={workflow.updatedAt}>
                          {new Date(workflow.updatedAt).toLocaleString()}
                        </time>{" "}
                        · revision {workflow.revision}
                      </small>
                    </div>
                    <span className="status-badge">
                      {workflow.status === "ARCHIVED"
                        ? "Archived"
                        : workflow.currentPublishedVersionId
                          ? "Published · editable draft"
                          : "Draft"}
                    </span>
                  </article>
                ))}
              </div>
            )}
            <Pager page={page} pages={list.data.totalPages} change={setPage} />
          </>
        )}
      </section>
      {access.data.permissions.includes("workflow.edit") && (
        <section className="data-panel">
          <h2>Create workflow</h2>
          <p>
            Start with a manual trigger and end. Add and configure supported
            definitions in the editor.
          </p>
          <ActionForm
            label="Create workflow"
            submit={async (data) => {
              const result = await workflowApi.create(
                organization,
                String(data.get("name")),
                String(data.get("description")),
                emptyGraph(),
              );
              navigate(
                `/organizations/${organization}/workflows/${result.workflow.id}`,
              );
            }}
          >
            <label>
              Workflow name
              <input name="name" maxLength={120} required />
            </label>
            <label>
              Description
              <textarea name="description" maxLength={2000} rows={3} />
            </label>
          </ActionForm>
        </section>
      )}
    </>
  );
}
