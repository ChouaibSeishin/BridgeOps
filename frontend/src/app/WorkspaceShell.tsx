import { useState, type ReactNode } from "react";
import { Link, NavLink, useLocation, useMatch } from "react-router-dom";
import { useQuery } from "@tanstack/react-query";
import {
  Activity,
  ArrowLeftRight,
  Blocks,
  Building2,
  ChevronRight,
  CircleHelp,
  ClipboardCheck,
  GitBranch,
  Layers,
  LogOut,
  Menu,
  Moon,
  Settings,
  ShieldCheck,
  Sun,
  Ticket,
  Users,
  Waypoints,
  X,
} from "lucide-react";
import { session, type User } from "../lib/session";
import { useWorkflowAccess } from "../features/workflows/useWorkflowAccess";
import { ConfirmationDialog } from "../components/ui";

export function WorkspaceShell({
  user,
  dark,
  theme,
  logout,
  children,
}: {
  user: User;
  dark: boolean;
  theme: () => void;
  logout: () => void;
  children: ReactNode;
}) {
  const location = useLocation();
  const match = useMatch("/organizations/:id/*");
  const organization = match?.params.id ?? "";
  const access = useWorkflowAccess(user.id, organization);
  const detail = useQuery({
    queryKey: [user.id, "organization", organization, "detail"],
    queryFn: () =>
      session.request<{ id: string; name: string; slug: string }>(
        `/api/v1/organizations/${encodeURIComponent(organization)}`,
      ),
    enabled: Boolean(organization) && access.isSuccess && !access.isError,
    retry: false,
  });
  const permissions = access.isError ? [] : (access.data?.permissions ?? []);
  const [drawer, setDrawer] = useState(false);
  const orgName = access.isError
    ? "Organization unavailable"
    : (detail.data?.name ??
      (detail.isError ? "Organization unavailable" : "Organization workspace"));
  const base = `/organizations/${encodeURIComponent(organization)}`;
  const section =
    location.pathname.slice(base.length).split("/").filter(Boolean)[0] ?? "";
  const names: Record<string, string> = {
    workflows: "Workflows",
    executions: "Executions",
    integrations: "Integrations",
    business: "Support operations",
    approvals: "Approvals",
    "approval-policy": "Approval policy",
    automations: "Automations & webhooks",
  };
  const pageName = organization
    ? (names[section] ?? "Members & settings")
    : location.pathname === "/account"
      ? "Account & sessions"
      : location.pathname === "/runtime"
        ? "Runtime"
        : location.pathname === "/workflows"
          ? "Workflows"
          : location.pathname.startsWith("/invitations")
            ? "Invitations"
            : "Organizations";
  const items = [
    {
      label: "Workflows",
      path: "workflows",
      permission: "workflow.read",
      icon: GitBranch,
      group: "Operations",
    },
    {
      label: "Executions",
      path: "executions",
      permission: "execution.read",
      icon: Activity,
      group: "Operations",
    },
    {
      label: "Integrations",
      path: "integrations",
      permission: "integration.read",
      icon: Blocks,
      group: "Operations",
    },
    {
      label: "Support operations",
      path: "business",
      permission: "capability.read",
      icon: Ticket,
      group: "Operations",
    },
    {
      label: "Approvals",
      path: "approvals",
      permission: "approval.read",
      icon: ClipboardCheck,
      group: "Governance",
    },
    {
      label: "Automations & webhooks",
      path: "automations",
      permission: "integration.read",
      icon: ArrowLeftRight,
      group: "Governance",
    },
  ];
  function navigation() {
    return (
      <>
        <Link className="brand" to="/" onClick={() => setDrawer(false)}>
          <span className="brand-icon">
            <Waypoints size={21} aria-hidden="true" />
          </span>
          BridgeOps<span className="brand-dot">.</span>
        </Link>
        <Link
          to="/"
          className="organization-context"
          onClick={() => setDrawer(false)}
        >
          <span className="organization-context-icon">
            <Building2 size={19} aria-hidden="true" />
          </span>
          <span>
            <small>
              {organization ? "CURRENT ORGANIZATION" : "YOUR WORKSPACE"}
            </small>
            <strong title={organization ? orgName : "Choose an organization"}>
              {organization ? orgName : "Choose an organization"}
            </strong>
          </span>
          <ChevronRight size={15} aria-hidden="true" />
        </Link>
        <nav className="workspace-nav" aria-label="Primary navigation">
          <p className="navigation-group">Workspace</p>
          <NavLink to="/" end onClick={() => setDrawer(false)}>
            <Building2 size={18} aria-hidden="true" />
            Organizations
          </NavLink>
          {!organization && (
            <NavLink to="/workflows" onClick={() => setDrawer(false)}>
              <GitBranch size={18} aria-hidden="true" />
              Workflows
            </NavLink>
          )}
          {organization &&
            ["Operations", "Governance"].map((group) => (
              <div className="navigation-section" key={group}>
                {items.some(
                  (item) =>
                    item.group === group &&
                    permissions.includes(item.permission),
                ) && <p className="navigation-group">{group}</p>}
                {items
                  .filter(
                    (item) =>
                      item.group === group &&
                      permissions.includes(item.permission),
                  )
                  .map(({ label, path, icon: Icon }) => (
                    <NavLink
                      key={path}
                      to={`${base}/${path}`}
                      onClick={() => setDrawer(false)}
                    >
                      <Icon size={18} aria-hidden="true" />
                      <span>{label}</span>
                    </NavLink>
                  ))}
              </div>
            ))}
          {organization && access.isSuccess && !access.isError && (
            <NavLink to={base} end onClick={() => setDrawer(false)}>
              <Users size={18} aria-hidden="true" />
              Members & settings
            </NavLink>
          )}
          {organization && access.isPending && (
            <p className="navigation-message" role="status">
              Checking organization access…
            </p>
          )}
          {organization && access.isError && (
            <div className="navigation-message">
              <p>Organization navigation unavailable.</p>
              <button className="button" onClick={() => void access.refetch()}>
                Retry navigation
              </button>
            </div>
          )}
          <p className="navigation-group">Account</p>
          <NavLink to="/runtime" onClick={() => setDrawer(false)}>
            <Layers size={18} aria-hidden="true" />
            Runtime
          </NavLink>
          <NavLink to="/account" onClick={() => setDrawer(false)}>
            <Settings size={18} aria-hidden="true" />
            Account & sessions
          </NavLink>
        </nav>
        <div className="navigation-footer">
          <ShieldCheck size={17} aria-hidden="true" />
          <p>
            Governed operations
            <span>Live permissions. Explicit approvals.</span>
          </p>
        </div>
      </>
    );
  }
  return (
    <div className="application-shell">
      <a className="skip-link" href="#main">
        Skip to content
      </a>
      <aside className="workspace-sidebar" aria-label="Workspace navigation">
        {navigation()}
      </aside>
      {drawer && (
        <ConfirmationDialog
          label="Workspace navigation"
          className="navigation-drawer"
          onCancel={() => setDrawer(false)}
        >
          <button
            className="icon-button drawer-close"
            aria-label="Close navigation"
            onClick={() => setDrawer(false)}
          >
            <X size={20} />
          </button>
          {navigation()}
        </ConfirmationDialog>
      )}
      <div className="application-body">
        <header className="workspace-topbar">
          <button
            className="icon-button navigation-toggle"
            aria-label="Open navigation"
            aria-expanded={drawer}
            aria-haspopup="dialog"
            onClick={() => setDrawer(true)}
          >
            <Menu size={20} />
          </button>
          <nav className="workspace-breadcrumb" aria-label="Breadcrumb">
            <ol>
              <li>
                <Link to="/">All organizations</Link>
              </li>
              {organization && (
                <li>
                  <ChevronRight size={14} aria-hidden="true" />
                  <Link to={base} title={orgName}>
                    {orgName}
                  </Link>
                </li>
              )}
              <li>
                <ChevronRight size={14} aria-hidden="true" />
                <span aria-current="page">
                  {organization && (
                    <small className="mobile-organization" title={orgName}>
                      {orgName}
                    </small>
                  )}
                  {pageName}
                </span>
              </li>
            </ol>
          </nav>
          <div className="workspace-account">
            <span className="account-name" title={user.displayName}>
              {user.displayName}
            </span>
            <button
              className="icon-button"
              aria-label={
                dark ? "Switch to light theme" : "Switch to dark theme"
              }
              onClick={theme}
            >
              {dark ? <Sun size={18} /> : <Moon size={18} />}
            </button>
            <button className="button sign-out" onClick={logout}>
              <LogOut size={16} aria-hidden="true" />
              <span>Sign out</span>
            </button>
          </div>
        </header>
        <main
          id="main"
          tabIndex={-1}
          className={`workspace-main${organization && section === "workflows" && location.pathname !== `${base}/workflows` ? " workspace-main--editor" : ""}`}
        >
          {children}
        </main>
        <footer className="workspace-footer">
          <span>BridgeOps · Operations workspace</span>
          <span>
            <CircleHelp size={14} aria-hidden="true" />
            Current permissions apply to every action
          </span>
        </footer>
      </div>
    </div>
  );
}
