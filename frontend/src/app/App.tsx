import { useEffect, useState, useSyncExternalStore, type FormEvent } from "react";
import { Link, NavLink, Navigate, Route, Routes } from "react-router-dom";
import { useQuery } from "@tanstack/react-query";
import { Activity, Box, Moon, Sun, Waypoints, LogOut, ShieldCheck } from "lucide-react";
import { getInfo, getReadiness } from "../lib/api";
import { failureMessage, session } from "../lib/session";

export function App() {
  const state=useSyncExternalStore(session.subscribe,session.snapshot);
  const [dark,setDark]=useState(()=>document.documentElement.dataset.theme==="dark");
  const [notice,setNotice]=useState("");
  useEffect(()=>{void session.initialize();},[]);
  function theme(){document.documentElement.dataset.theme=dark ? "light":"dark";setDark(!dark);}
  async function logout(all=false){try{await session.logout(all);setNotice("Signed out.");}catch(error){setNotice(`Local session cleared. Server sign-out could not be confirmed. ${failureMessage(error)}`);}}
  if(state.status==="loading")return <main className="auth-layout" aria-busy="true"><p role="status">Restoring your session…</p></main>;
  if(state.status==="unavailable")return <main className="auth-layout"><section className="auth-card"><h1>Unable to restore your session</h1><p role="alert">{state.error}</p><button className="button primary" onClick={()=>void session.retry()}>Try again</button></section></main>;
  if(state.status!=="authenticated")return <main className="auth-layout"><Routes><Route path="/sign-in" element={<SignIn notice={notice}/>} /><Route path="*" element={<Navigate to="/sign-in" replace/>}/></Routes></main>;
  return <div className="workspace">
    <a className="skip-link" href="#main">Skip to content</a>
    <aside className="sidebar" aria-label="Workspace navigation"><Link className="brand" to="/"><span className="brand-icon"><Waypoints size={22}/></span>BridgeOps<span className="brand-dot">.</span></Link>
      <div className="workspace-label"><ShieldCheck size={22}/><div><strong>Operations workspace</strong><span>Secure, governed access</span></div></div>
      <p className="nav-label">WORKSPACE</p><nav><NavLink to="/" end><Box size={17}/>Overview</NavLink><NavLink to="/runtime"><Activity size={17}/>Runtime</NavLink><NavLink to="/account">Account & sessions</NavLink></nav>
      <div className="sidebar-bottom"><p className="muted small">Organization access is checked by the server on every request.</p></div>
    </aside>
    <div className="workspace-body"><header className="topbar"><span className="breadcrumb">Operations / Workspace</span><div className="top-actions"><span>{state.user?.displayName}</span><button className="icon-button" aria-label={dark ? "Switch to light theme":"Switch to dark theme"} onClick={theme}>{dark ? <Sun size={18}/>:<Moon size={18}/>}</button><button className="button" onClick={()=>void logout()}><LogOut size={15}/>Sign out</button></div></header>
    <main id="main" tabIndex={-1}><Routes>
      <Route path="/" element={<><div className="page-heading"><div><div className="eyebrow">YOUR OPERATIONS, CONNECTED</div><h1>Welcome, {state.user?.displayName}.</h1><p>You are securely signed in to BridgeOps.</p></div></div><section className="auth-card"><h2>Your workspace starts here</h2><p>Organization onboarding is the next increment. Workflow execution, approvals and integrations are not available yet.</p><Link className="text-link" to="/account">Manage your browser session</Link></section></>}/>
      <Route path="/account" element={<><div className="page-heading"><div><h1>Account & sessions</h1><p>Your current identity and sign-out controls.</p></div></div><section className="auth-card"><h2>{state.user?.displayName}</h2><p>{state.user?.email}</p><p>Access credentials are kept in memory. Your browser restores the session using its protected cookie.</p><div className="action-row"><button className="button" onClick={()=>void logout()}>Sign out this browser</button><button className="button danger" onClick={()=>void logout(true)}>Sign out all sessions</button></div></section></>}/>
      <Route path="/runtime" element={<Runtime/>}/><Route path="/sign-in" element={<Navigate to="/" replace/>}/><Route path="*" element={<><h1>Page not found</h1><Link to="/">Return to overview</Link></>}/>
    </Routes></main></div>
  </div>;
}
function SignIn({notice}:{notice:string}){
  const [pending,setPending]=useState(false);const [error,setError]=useState("");
  async function submit(event:FormEvent<HTMLFormElement>){event.preventDefault();const form=event.currentTarget;const data=new FormData(form);setPending(true);setError("");try{await session.login(String(data.get("email")),String(data.get("password")));}catch(failure){setError(failureMessage(failure));}finally{(form.elements.namedItem("password") as HTMLInputElement).value="";setPending(false);}}
  return <section className="auth-card"><Link className="brand" to="/"><span className="brand-icon"><Waypoints size={22}/></span>BridgeOps</Link><div className="eyebrow">OPERATIONS, WITH CLARITY</div><h1>Sign in to your workspace</h1><p className="muted">Use your provisioned account or an accepted invitation.</p>{notice && <p role="status">{notice}</p>}<form onSubmit={event=>void submit(event)}><label>Email<input name="email" type="email" autoComplete="username" required maxLength={254}/></label><label>Password<input name="password" type="password" autoComplete="current-password" required maxLength={256}/></label>{error && <p role="alert" className="form-error">{error}</p>}<button className="button primary" disabled={pending}>{pending ? "Signing in…":"Sign in"}</button></form><p className="muted small">New installations require the offline bootstrap command. Public registration is unavailable.</p></section>;
}
function Runtime(){
  const info=useQuery({queryKey:["system-info"],queryFn:getInfo,retry:false});const readiness=useQuery({queryKey:["readiness"],queryFn:getReadiness,retry:false});
  return <><div className="page-heading"><div><div className="eyebrow">SERVICE OBSERVABILITY</div><h1>Runtime</h1><p>Live signals from this installation.</p></div><button className="button" onClick={()=>{void info.refetch();void readiness.refetch();}}>Check again</button></div><section className="auth-card"><h2>Connection diagnostics</h2><dl><dt>Application identity</dt><dd>{info.data?.name ?? (info.isError ? "Unavailable":"Checking…")}</dd><dt>API contract</dt><dd>{info.data?.apiVersion ?? "—"}</dd><dt>Dependency readiness</dt><dd>{readiness.isPending ? "Checking…":readiness.isError ? "Unavailable":"Ready"}</dd></dl>{(info.isError || readiness.isError) && <p role="alert">The service could not be verified. Check again to retry.</p>}</section></>;
}
