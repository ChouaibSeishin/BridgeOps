import { useEffect, useRef, type ReactNode } from "react";
import { Activity, Check, Clock, Minus, TriangleAlert } from "lucide-react";

export function PageHeader({
  title,
  description,
  eyebrow,
  actions,
}: {
  title: string;
  description?: ReactNode;
  eyebrow?: string;
  actions?: ReactNode;
}) {
  return (
    <header className="page-heading">
      <div>
        {eyebrow && <p className="eyebrow">{eyebrow}</p>}
        <h1>{title}</h1>
        {description && <p>{description}</p>}
      </div>
      {actions && <div className="page-actions">{actions}</div>}
    </header>
  );
}

export function StatusBadge({
  value,
  children,
  live = false,
}: {
  value: string;
  children?: ReactNode;
  live?: boolean;
}) {
  const normalized = value.toUpperCase().replaceAll(" ", "_");
  const success = [
    "SUCCEEDED",
    "APPROVED",
    "ENABLED",
    "ACTIVE",
    "AVAILABLE",
    "DELIVERED_INTERNAL",
    "APPLIED",
    "VALID",
    "PUBLISHED",
    "RESOLVED",
  ].includes(normalized);
  const danger = [
    "FAILED",
    "REJECTED",
    "INVALIDATED",
    "UNAVAILABLE",
    "RECONCILIATION_REQUIRED",
    "URGENT",
  ].includes(normalized);
  const waiting =
    normalized.startsWith("WAITING") ||
    ["PENDING", "QUEUED", "EXPIRED"].includes(normalized);
  const tone = success
    ? "success"
    : danger
      ? "danger"
      : waiting
        ? "warning"
        : ["RUNNING", "IN_PROGRESS", "OPEN"].includes(normalized)
          ? "info"
          : "neutral";
  const Icon = success
    ? Check
    : danger
      ? TriangleAlert
      : waiting
        ? Clock
        : ["RUNNING", "IN_PROGRESS", "OPEN"].includes(normalized)
          ? Activity
          : Minus;
  return (
    <span
      className={`status-badge badge-${tone}`}
      role={live ? "status" : undefined}
    >
      <Icon size={14} aria-hidden="true" />
      <span>{children ?? value.replaceAll("_", " ")}</span>
    </span>
  );
}

export function EmptyState({
  title,
  children,
  action,
}: {
  title: string;
  children?: ReactNode;
  action?: ReactNode;
}) {
  return (
    <div className="empty-state">
      <span className="empty-state-symbol" aria-hidden="true">
        <Minus size={20} />
      </span>
      <div>
        <h3>{title}</h3>
        {children && <p>{children}</p>}
        {action}
      </div>
    </div>
  );
}

/** Native modal semantics provide inert background, keyboard containment and Escape. */
export function ConfirmationDialog({
  children,
  labelledBy,
  label,
  onCancel,
  busy = false,
  className = "",
  role = "dialog",
}: {
  children: ReactNode;
  labelledBy?: string;
  label?: string;
  onCancel: () => void;
  busy?: boolean;
  className?: string;
  role?: "dialog" | "alertdialog";
}) {
  const dialog = useRef<HTMLDialogElement>(null);
  useEffect(() => {
    const element = dialog.current;
    const previous =
      document.activeElement instanceof HTMLElement
        ? document.activeElement
        : null;
    if (!element) return;
    if (typeof element.showModal === "function") element.showModal();
    else element.setAttribute("open", ""); // jsdom retains the same accessible content.
    element.focus();
    return () => {
      if (element.open && typeof element.close === "function") element.close();
      if (previous?.isConnected) previous.focus();
    };
  }, []);
  return (
    <dialog
      ref={dialog}
      role={role}
      tabIndex={-1}
      aria-modal="true"
      aria-labelledby={labelledBy}
      aria-label={label}
      className={`confirmation-dialog ${className}`}
      onKeyDown={(event) => {
        if (event.key !== "Tab") return;
        const element = event.currentTarget;
        const controls = [
          ...element.querySelectorAll<HTMLElement>(
            'a[href],button:not(:disabled),input:not(:disabled),select:not(:disabled),textarea:not(:disabled),[tabindex]:not([tabindex="-1"])',
          ),
        ].filter(
          (control) =>
            control.getClientRects().length > 0 && !control.closest("[inert]"),
        );
        const first = controls[0],
          last = controls[controls.length - 1];
        if (!first || !last) {
          event.preventDefault();
          return;
        }
        if (
          event.shiftKey &&
          (document.activeElement === first ||
            document.activeElement === element)
        ) {
          event.preventDefault();
          last.focus();
        } else if (
          !event.shiftKey &&
          (document.activeElement === last ||
            document.activeElement === element)
        ) {
          event.preventDefault();
          first.focus();
        }
      }}
      onCancel={(event) => {
        event.preventDefault();
        if (!busy) onCancel();
      }}
    >
      {children}
    </dialog>
  );
}
