import { useState } from "react";
import { NavLink, Route, Routes } from "react-router-dom";
import { useQuery } from "@tanstack/react-query";
import {
  Activity,
  ArrowRight,
  ArrowUpRight,
  Box,
  Check,
  ChevronRight,
  CircleHelp,
  Database,
  GitBranch,
  Layers3,
  Moon,
  RefreshCw,
  Server,
  ShieldCheck,
  Sun,
  Terminal,
  Waypoints,
} from "lucide-react";
import { ApiFailure, getInfo, getReadiness } from "../lib/api";

export function App() {
  const [dark, setDark] = useState(
    () => document.documentElement.dataset.theme === "dark",
  );
  function toggleTheme() {
    const next = !dark;
    document.documentElement.dataset.theme = next ? "dark" : "light";
    setDark(next);
  }
  const info = useQuery({
    queryKey: ["system-info"],
    queryFn: getInfo,
    refetchInterval: 30000,
  });
  const readiness = useQuery({
    queryKey: ["readiness"],
    queryFn: getReadiness,
    refetchInterval: 30000,
  });
  const ready = readiness.isSuccess && !readiness.isError;
  function refresh() {
    void info.refetch();
    void readiness.refetch();
  }
  return (
    <div className="workspace">
      <a href="#main" className="skip-link">
        Skip to content
      </a>
      <aside className="sidebar" aria-label="Workspace navigation">
        <a className="brand" href="/">
          <span className="brand-icon">
            <Waypoints size={22} />
          </span>
          BridgeOps<span className="brand-dot">.</span>
        </a>
        <div className="workspace-label">
          <span className="workspace-avatar">B</span>
          <div>
            <strong>Local workspace</strong>
            <span>Application foundation</span>
          </div>
          <Layers3 size={15} />
        </div>
        <p className="nav-label">WORKSPACE</p>
        <nav>
          <NavLink to="/" end>
            <Box size={17} />
            Overview
          </NavLink>
          <NavLink to="/runtime">
            <Activity size={17} />
            Runtime
            <span className={`nav-dot ${ready ? "online" : ""}`} />
          </NavLink>
        </nav>
        <div className="sidebar-bottom">
          <div className="build-note">
            <ShieldCheck size={18} />
            <strong>Built with boundaries.</strong>
            <p>A foundation for reliable, governed automation.</p>
          </div>
          <div className="local-badge">
            <span className="tiny-dot" />
            Local development<span className="mono">01A</span>
          </div>
        </div>
      </aside>
      <div className="workspace-body">
        <header className="topbar">
          <div className="breadcrumb">
            Workspace
            <ChevronRight size={14} />
            <span>Operations</span>
          </div>
          <div className="top-actions">
            <span className="foundation-badge">FOUNDATION</span>
            <button
              className="icon-button"
              onClick={toggleTheme}
              aria-label={
                dark ? "Switch to light theme" : "Switch to dark theme"
              }
            >
              {dark ? <Sun size={18} /> : <Moon size={18} />}
            </button>
            <span className="profile-mark" aria-label="Local installation">
              B
            </span>
          </div>
        </header>
        <main id="main" tabIndex={-1}>
          <Routes>
            <Route
              path="/"
              element={
                <>
                  <div className="page-heading">
                    <div>
                      <div className="eyebrow">YOUR OPERATIONS, CONNECTED</div>
                      <h1>A clear view of what’s running.</h1>
                      <p>
                        One workspace for integrations, workflows and the
                        decisions between them.
                      </p>
                    </div>
                    <button
                      className="button primary"
                      onClick={refresh}
                      disabled={info.isFetching || readiness.isFetching}
                    >
                      <RefreshCw
                        size={15}
                        className={
                          info.isFetching || readiness.isFetching ? "spin" : ""
                        }
                      />
                      Refresh status
                    </button>
                  </div>
                  <section
                    className="welcome-panel"
                    aria-labelledby="welcome-title"
                  >
                    <div className="welcome-content">
                      <span className="pill">
                        <span className="tiny-dot" />
                        Phase 1A · Technical foundation
                      </span>
                      <h2 id="welcome-title">
                        Good operations start
                        <br />
                        with a solid foundation.
                      </h2>
                      <p>
                        Your application services are connected here.
                        Authentication and organization setup are the next
                        implementation increment.
                      </p>
                      <NavLink to="/runtime" className="text-link">
                        Inspect runtime
                        <ArrowRight size={16} />
                      </NavLink>
                    </div>
                    <div
                      className="system-map"
                      aria-label="Architecture: browser connects to API, API connects to database"
                    >
                      <div className="map-node">
                        <Layers3 size={20} />
                        <span>React workspace</span>
                        <small>Interface</small>
                      </div>
                      <div className="map-connector" />
                      <div className="map-node featured">
                        <Waypoints size={24} />
                        <span>BridgeOps API</span>
                        <small>Application</small>
                      </div>
                      <div className="map-connector" />
                      <div className="map-node">
                        <Database size={20} />
                        <span>PostgreSQL</span>
                        <small>Persistence</small>
                      </div>
                    </div>
                  </section>
                  <div className="section-heading">
                    <div>
                      <h2>System connection</h2>
                      <p>
                        Live responses from this installation. Checked every 30
                        seconds.
                      </p>
                    </div>
                    <span className="live-label">
                      <span className="tiny-dot" />
                      LIVE CHECKS
                    </span>
                  </div>
                  <div className="connection-grid">
                    <section className="connection-card">
                      <div className="connection-icon">
                        <Server size={20} />
                      </div>
                      <div>
                        <h3>Application API</h3>
                        <p>
                          {info.data
                            ? `${info.data.name} · ${info.data.apiVersion}`
                            : "Waiting for the application"}
                        </p>
                      </div>
                      <Status pending={info.isPending} failed={info.isError} />
                    </section>
                    <section className="connection-card">
                      <div className="connection-icon teal">
                        <Database size={20} />
                      </div>
                      <div>
                        <h3>Dependency readiness</h3>
                        <p>Application and database health</p>
                      </div>
                      <Status
                        pending={readiness.isPending}
                        failed={readiness.isError}
                      />
                    </section>
                  </div>
                  {(info.isError || readiness.isError) && (
                    <div className="error-panel" role="alert">
                      <CircleHelp size={18} />
                      <div>
                        <strong>We couldn’t verify the connection.</strong>
                        <p>
                          The service may be starting or temporarily
                          unavailable. Refresh to try again.
                        </p>
                        {info.error instanceof ApiFailure &&
                          info.error.correlationId && (
                            <small className="mono">
                              Reference: {info.error.correlationId}
                            </small>
                          )}
                      </div>
                      <button className="button" onClick={refresh}>
                        Try again
                      </button>
                    </div>
                  )}
                  <section className="next-section">
                    <div className="section-heading">
                      <div>
                        <h2>What this foundation establishes</h2>
                        <p>The first layer of a larger operations platform.</p>
                      </div>
                      <span className="muted small">
                        IMPLEMENTED IN THIS INCREMENT
                      </span>
                    </div>
                    <div className="principle-grid">
                      <article>
                        <span className="principle-number">01</span>
                        <ShieldCheck size={20} />
                        <h3>Explicit access boundaries</h3>
                        <p>
                          Business routes stay denied until authentication and
                          authorization are implemented.
                        </p>
                      </article>
                      <article>
                        <span className="principle-number">02</span>
                        <Database size={20} />
                        <h3>Versioned persistence</h3>
                        <p>
                          PostgreSQL schema changes are applied and tracked
                          through Flyway migrations.
                        </p>
                      </article>
                      <article>
                        <span className="principle-number">03</span>
                        <Activity size={20} />
                        <h3>Observable services</h3>
                        <p>
                          Separate readiness and liveness probes make service
                          availability inspectable.
                        </p>
                      </article>
                    </div>
                  </section>
                  <div className="roadmap-note">
                    <GitBranch size={18} />
                    <div>
                      <strong>Next: identity & organization setup</strong>
                      <span>
                        Secure sessions, a protected first-account command and
                        real tenant membership.
                      </span>
                    </div>
                    <ArrowUpRight size={18} />
                  </div>
                </>
              }
            />
            <Route
              path="/runtime"
              element={
                <>
                  <div className="page-heading">
                    <div>
                      <div className="eyebrow">SERVICE OBSERVABILITY</div>
                      <h1>Runtime</h1>
                      <p>Minimal health signals from the actual application.</p>
                    </div>
                    <button className="button primary" onClick={refresh}>
                      <RefreshCw size={15} />
                      Check again
                    </button>
                  </div>
                  <section className="runtime-table">
                    <div>
                      <Terminal size={20} />
                      <h2>Connection diagnostics</h2>
                    </div>
                    <dl>
                      <dt>Application identity</dt>
                      <dd>
                        {info.data?.name ??
                          (info.isError ? "Unavailable" : "Checking…")}
                      </dd>
                      <dt>API contract</dt>
                      <dd className="mono">{info.data?.apiVersion ?? "—"}</dd>
                      <dt>Dependency readiness</dt>
                      <dd>
                        {readiness.isPending
                          ? "Checking…"
                          : ready
                            ? "Ready"
                            : "Unavailable"}
                      </dd>
                      <dt>Request routing</dt>
                      <dd>Same-origin reverse proxy</dd>
                    </dl>
                  </section>
                  <p className="muted runtime-note">
                    Readiness includes PostgreSQL. Liveness is independent of
                    database availability. No tenant or execution data is
                    available in this increment.
                  </p>
                </>
              }
            />
            <Route
              path="*"
              element={
                <div className="page-heading">
                  <div>
                    <h1>Page not found</h1>
                    <NavLink to="/">Return to overview</NavLink>
                  </div>
                </div>
              }
            />
          </Routes>
          <footer>
            <span>
              BridgeOps<span className="footer-separator">/</span>Operations,
              with clarity.
            </span>
            <span>Local installation · Foundation increment</span>
          </footer>
        </main>
      </div>
    </div>
  );
}

function Status({ pending, failed }: { pending: boolean; failed: boolean }) {
  return (
    <span
      className={`status ${pending ? "pending" : failed ? "unavailable" : "connected"}`}
    >
      {pending ? (
        <RefreshCw size={12} className="spin" />
      ) : failed ? (
        <CircleHelp size={12} />
      ) : (
        <Check size={12} />
      )}
      {pending ? "Checking" : failed ? "Unavailable" : "Connected"}
    </span>
  );
}
