import { useState } from "react";
import { Link } from "react-router-dom";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { failureMessage } from "../../lib/session";
import { Problem } from "../workflows/Feedback";
import { demoWorkflowApi } from "./demoWorkflowApi";

export function DemoWorkflows({
  user,
  org,
  canInstall,
}: {
  user: string;
  org: string;
  canInstall: boolean;
}) {
  const client = useQueryClient();
  const key = [user, "business", org, "demo-workflows"];
  const query = useQuery({
    queryKey: key,
    queryFn: () => demoWorkflowApi.get(org),
    retry: false,
    refetchOnWindowFocus: false,
    gcTime: 0,
  });
  const [confirm, setConfirm] = useState(false),
    [busy, setBusy] = useState(false),
    [error, setError] = useState(""),
    [notice, setNotice] = useState("");
  async function install() {
    setBusy(true);
    setError("");
    try {
      const result = await demoWorkflowApi.install(org);
      client.setQueryData(key, result);
      setConfirm(false);
      setNotice(
        result.created
          ? "Example workflows published. Nothing was executed and no automation authority or signing key was created."
          : "The original example references are retained. Existing edits were preserved.",
      );
      await client.invalidateQueries({ queryKey: [user, "business", org] });
    } catch (failure) {
      setError(failureMessage(failure));
    } finally {
      setBusy(false);
    }
  }
  if (query.isPending)
    return <p role="status">Checking example workflow setup…</p>;
  if (query.isError)
    return <Problem error={query.error} retry={() => void query.refetch()} />;
  const artifacts = query.data.artifacts;
  return (
    <section className="data-panel" aria-labelledby="example-workflow-title">
      <h2 id="example-workflow-title">Example triage workflows</h2>
      <p>
        Opt-in examples read unresolved high-priority tickets, resolve
        responsible teams, request exact approval, assign tickets and record an
        internal notification. A later no-work branch skips those effects.
        Internal delivery sends no email or SMS.
      </p>
      {notice && <p role="status">{notice}</p>}
      {error && <p role="alert">{error}</p>}
      {artifacts ? (
        <>
          <p>
            Provenance: {artifacts.provenance}. Original published references
            are retained; setup does not repair later connection or workflow
            edits. Inspect the saved scope before running.
          </p>
          <div className="action-row">
            <Link
              className="button"
              to={`/organizations/${org}/workflows/${artifacts.manual.workflowId}`}
            >
              Open manual triage example
            </Link>
            <Link
              className="button"
              to={`/organizations/${org}/workflows/${artifacts.webhook.workflowId}/versions/${artifacts.webhook.versionId}`}
            >
              Inspect webhook triage example
            </Link>
          </div>
          <p>
            For the manual example, enter a ticket limit from 1 to 50. Protected
            assignments wait for a different eligible human. The webhook
            definition requires separately configured automation authority and a
            signed endpoint; publishing it does not enable callbacks.
          </p>
          <details>
            <summary>Original example bindings</summary>
            <p>Created {new Date(artifacts.createdAt).toLocaleString()}</p>
            {(["support", "teams", "notifications"] as const).map((name) => (
              <p key={name}>
                {name}: <code>{artifacts[name].id}</code> · revision{" "}
                {artifacts[name].revision}
              </p>
            ))}
            <p>
              Manual version: <code>{artifacts.manual.versionId}</code> · hash{" "}
              <code>{artifacts.manual.definitionHash}</code>
            </p>
            <p>
              Webhook version: <code>{artifacts.webhook.versionId}</code> · hash{" "}
              <code>{artifacts.webhook.definitionHash}</code>
            </p>
          </details>
        </>
      ) : canInstall ? (
        confirm ? (
          <div>
            <p>
              Create dedicated support, team and internal-notification
              connections plus two published schema-v2 examples? Example
              business data is added once if absent. Existing data is retained.
              This creates no execution, automation principal, webhook endpoint
              or signing key.
            </p>
            <button
              className="button primary"
              disabled={busy}
              onClick={() => void install()}
            >
              {busy
                ? "Publishing example workflows…"
                : "Confirm setup triage workflows"}
            </button>
            <button
              className="button"
              disabled={busy}
              onClick={() => setConfirm(false)}
            >
              Keep existing workflows only
            </button>
          </div>
        ) : (
          <button className="button" onClick={() => setConfirm(true)}>
            Set up triage workflows
          </button>
        )
      ) : (
        <p>
          No example workflows are installed. Installation requires
          integration.manage, capability.write, workflow.edit and
          workflow.publish.
        </p>
      )}
    </section>
  );
}
