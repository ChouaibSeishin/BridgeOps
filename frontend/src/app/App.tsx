import { WorkspaceShell } from "./WorkspaceShell";
import {
  useEffect,
  useState,
  useSyncExternalStore,
  type FormEvent,
} from "react";
import { Link, Navigate, Route, Routes, useNavigate } from "react-router-dom";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { Waypoints } from "lucide-react";
import { getInfo, getReadiness } from "../lib/api";
import { failureMessage, session } from "../lib/session";

import {
  Organizations,
  OrganizationDetail,
  InvitationJourney,
} from "./Organizations";
import { Recovery, PasswordSettings } from "./Recovery";
import { WorkflowHome, WorkflowList } from "../features/workflows/WorkflowList";
import {
  WorkflowEditor,
  PublishedVersion,
} from "../features/workflows/WorkflowEditor";
import {
  ExecutionList,
  ExecutionDetail,
} from "../features/executions/Executions";
import {
  ApprovalInbox,
  ApprovalDetail,
  ApprovalPolicy,
} from "../features/approvals/Approvals";
import { BusinessWorkspace } from "../features/integrations/BusinessWorkspace";
import { Automations } from "../features/integrations/Automations";
import {
  Connections,
  ConnectionDetail,
} from "../features/integrations/Connections";
export function App() {
  const navigate = useNavigate();
  const state = useSyncExternalStore(session.subscribe, session.snapshot);
  const [dark, setDark] = useState(
    () => document.documentElement.dataset.theme === "dark",
  );
  const queryClient = useQueryClient();
  useEffect(() => {
    if (state.status !== "authenticated") queryClient.clear();
  }, [state.status, queryClient]);
  const [notice, setNotice] = useState("");
  useEffect(() => {
    void session.initialize();
  }, []);
  function passwordFinished() {
    setNotice("Password updated. Sign in again to continue.");
    session.invalidate();
    navigate("/sign-in", { replace: true });
  }
  function theme() {
    document.documentElement.dataset.theme = dark ? "light" : "dark";
    setDark(!dark);
  }
  async function logout(all = false) {
    try {
      await session.logout(all);
      setNotice("Signed out.");
    } catch (error) {
      setNotice(
        `Local session cleared. Server sign-out could not be confirmed. ${failureMessage(error)}`,
      );
    }
  }
  if (state.status === "loading")
    return (
      <main className="auth-layout" aria-busy="true">
        <p role="status">Restoring your session…</p>
      </main>
    );
  if (state.status === "unavailable")
    return (
      <main className="auth-layout">
        <section className="auth-card">
          <h1>Unable to restore your session</h1>
          <p role="alert">{state.error}</p>
          <button
            className="button primary"
            onClick={() => void session.retry()}
          >
            Try again
          </button>
        </section>
      </main>
    );
  if (state.status !== "authenticated")
    return (
      <main className="auth-layout">
        <Routes>
          <Route
            path="/forgot"
            element={<Recovery onReset={passwordFinished} />}
          />
          <Route
            path="/reset"
            element={<Recovery reset onReset={passwordFinished} />}
          />
          <Route path="/sign-in" element={<SignIn notice={notice} />} />
          <Route
            path="/invitations/enroll"
            element={<InvitationJourney enroll />}
          />
          <Route path="*" element={<Navigate to="/sign-in" replace />} />
        </Routes>
      </main>
    );
  return (
    <WorkspaceShell
      user={state.user!}
      dark={dark}
      theme={theme}
      logout={() => void logout()}
    >
      <Routes>
        <Route
          path="/forgot"
          element={<Recovery onReset={passwordFinished} />}
        />
        <Route
          path="/reset"
          element={<Recovery reset onReset={passwordFinished} />}
        />
        <Route path="/" element={<Organizations user={state.user!} />} />
        <Route
          path="/organizations/:id/integrations"
          element={<Connections user={state.user!} />}
        />
        <Route
          path="/organizations/:id/automations"
          element={<Automations user={state.user!} />}
        />
        <Route
          path="/organizations/:id/integrations/:connectionId"
          element={<ConnectionDetail user={state.user!} />}
        />
        <Route
          path="/organizations/:id/business"
          element={<BusinessWorkspace user={state.user!} />}
        />
        <Route
          path="/organizations/:id/approvals"
          element={<ApprovalInbox user={state.user!} />}
        />
        <Route
          path="/organizations/:id/approvals/:approvalId"
          element={<ApprovalDetail user={state.user!} />}
        />
        <Route
          path="/organizations/:id/approval-policy"
          element={<ApprovalPolicy user={state.user!} />}
        />
        <Route
          path="/organizations/:id/executions"
          element={<ExecutionList user={state.user!} />}
        />
        <Route
          path="/organizations/:id/executions/:executionId"
          element={<ExecutionDetail user={state.user!} />}
        />
        <Route
          path="/workflows"
          element={<WorkflowHome user={state.user!} />}
        />
        <Route
          path="/organizations/:id/workflows"
          element={<WorkflowList user={state.user!} />}
        />
        <Route
          path="/organizations/:id/workflows/:workflowId"
          element={<WorkflowEditor user={state.user!} />}
        />
        <Route
          path="/organizations/:id/workflows/:workflowId/versions/:versionId"
          element={<PublishedVersion user={state.user!} />}
        />
        <Route
          path="/organizations/:id"
          element={<OrganizationDetail user={state.user!} />}
        />
        <Route path="/invitations/accept" element={<InvitationJourney />} />
        <Route
          path="/invitations/enroll"
          element={<InvitationJourney enroll />}
        />
        <Route
          path="/account"
          element={
            <>
              <div className="page-heading">
                <div>
                  <h1>Account & sessions</h1>
                  <p>Your current identity and sign-out controls.</p>
                </div>
              </div>
              <PasswordSettings onChanged={passwordFinished} />
              <section className="auth-card">
                <h2>{state.user?.displayName}</h2>
                <p>{state.user?.email}</p>
                <p>
                  Access credentials are kept in memory. Your browser restores
                  the session using its protected cookie.
                </p>
                <div className="action-row">
                  <button className="button" onClick={() => void logout()}>
                    Sign out this browser
                  </button>
                  <button
                    className="button danger"
                    onClick={() => void logout(true)}
                  >
                    Sign out all sessions
                  </button>
                </div>
              </section>
            </>
          }
        />
        <Route path="/runtime" element={<Runtime />} />
        <Route path="/sign-in" element={<Navigate to="/" replace />} />
        <Route
          path="*"
          element={
            <>
              <h1>Page not found</h1>
              <Link to="/">Return to overview</Link>
            </>
          }
        />
      </Routes>
    </WorkspaceShell>
  );
}
function SignIn({ notice }: { notice: string }) {
  const [pending, setPending] = useState(false);
  const [error, setError] = useState("");
  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const form = event.currentTarget;
    const data = new FormData(form);
    setPending(true);
    setError("");
    try {
      await session.login(
        String(data.get("email")),
        String(data.get("password")),
      );
    } catch (failure) {
      setError(failureMessage(failure));
    } finally {
      (form.elements.namedItem("password") as HTMLInputElement).value = "";
      setPending(false);
    }
  }
  return (
    <section className="auth-card">
      <Link className="brand" to="/">
        <span className="brand-icon">
          <Waypoints size={22} />
        </span>
        BridgeOps
      </Link>
      <div className="eyebrow">OPERATIONS, WITH CLARITY</div>
      <h1>Sign in to your workspace</h1>
      <p className="muted">
        Use your provisioned account or an accepted invitation.
      </p>
      {notice && <p role="status">{notice}</p>}
      <form onSubmit={(event) => void submit(event)}>
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
          Password
          <input
            name="password"
            type="password"
            autoComplete="current-password"
            required
            maxLength={256}
          />
        </label>
        {error && (
          <p role="alert" className="form-error">
            {error}
          </p>
        )}
        <button className="button primary" disabled={pending}>
          {pending ? "Signing in…" : "Sign in"}
        </button>
      </form>
      <p>
        <Link className="text-link" to="/invitations/enroll">
          Join with an invitation
        </Link>
      </p>
      <p>
        <Link className="text-link" to="/forgot">
          Forgot your password?
        </Link>
      </p>
      <p className="muted small">
        New installations require the offline bootstrap command. Public
        registration is unavailable.
      </p>
    </section>
  );
}
function Runtime() {
  const info = useQuery({
    queryKey: ["system-info"],
    queryFn: getInfo,
    retry: false,
  });
  const readiness = useQuery({
    queryKey: ["readiness"],
    queryFn: getReadiness,
    retry: false,
  });
  return (
    <>
      <div className="page-heading">
        <div>
          <div className="eyebrow">SERVICE OBSERVABILITY</div>
          <h1>Runtime</h1>
          <p>Live signals from this installation.</p>
        </div>
        <button
          className="button"
          onClick={() => {
            void info.refetch();
            void readiness.refetch();
          }}
        >
          Check again
        </button>
      </div>
      <section className="auth-card">
        <h2>Connection diagnostics</h2>
        <dl>
          <dt>Application identity</dt>
          <dd>
            {info.data?.name ?? (info.isError ? "Unavailable" : "Checking…")}
          </dd>
          <dt>API contract</dt>
          <dd>{info.data?.apiVersion ?? "—"}</dd>
          <dt>Dependency readiness</dt>
          <dd>
            {readiness.isPending
              ? "Checking…"
              : readiness.isError
                ? "Unavailable"
                : "Ready"}
          </dd>
        </dl>
        {(info.isError || readiness.isError) && (
          <p role="alert">
            The service could not be verified. Check again to retry.
          </p>
        )}
      </section>
    </>
  );
}
