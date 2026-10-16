import { useState } from "react";
import { Link } from "react-router-dom";
import { ActionForm } from "./Organizations";
import { session, InputFailure } from "../lib/session";
function replacement(data: FormData) {
  const password = String(data.get("newPassword"));
  if (password !== data.get("confirmPassword"))
    throw new InputFailure("The new passwords do not match.");
  if (
    Array.from(password).length < 12 ||
    new TextEncoder().encode(password).length > 72
  )
    throw new InputFailure(
      "Use at least 12 characters and no more than 72 UTF-8 bytes.",
    );
  return password;
}
function NewPassword() {
  return (
    <>
      <label>
        New password
        <input
          name="newPassword"
          type="password"
          autoComplete="new-password"
          required
          maxLength={256}
        />
      </label>
      <label>
        Confirm new password
        <input
          name="confirmPassword"
          type="password"
          autoComplete="new-password"
          required
          maxLength={256}
        />
      </label>
      <small>
        At least 12 Unicode characters, at most 72 UTF-8 bytes. Changing or
        resetting your password signs out existing sessions.
      </small>
    </>
  );
}
export function PasswordSettings({ onChanged }: { onChanged: () => void }) {
  return (
    <section className="data-panel">
      <h2>Change password</h2>
      <p>
        Confirm your current password. You will need to sign in again on every
        browser.
      </p>
      <ActionForm
        label="Change password"
        onSuccess={onChanged}
        submit={(data) =>
          session.request("/api/v1/auth/password/change", {
            method: "POST",
            headers: { "Content-Type": "application/json" },
            body: JSON.stringify({
              currentPassword: String(data.get("currentPassword")),
              newPassword: replacement(data),
            }),
          })
        }
      >
        <label>
          Current password
          <input
            name="currentPassword"
            type="password"
            autoComplete="current-password"
            required
            maxLength={256}
          />
        </label>
        <NewPassword />
      </ActionForm>
    </section>
  );
}
export function Recovery({
  reset = false,
  onReset,
}: {
  reset?: boolean;
  onReset: () => void;
}) {
  const [requested, setRequested] = useState(false);
  return (
    <section className="auth-card">
      <div className="eyebrow">ACCOUNT RECOVERY</div>
      <h1>{reset ? "Choose a new password" : "Recover your account"}</h1>
      {reset ? (
        <>
          <p>
            Paste the one-use token from your recovery email. It expires after
            15 minutes. Never put the token in a URL.
          </p>
          <ActionForm
            label="Reset password"
            onSuccess={onReset}
            submit={(data) =>
              session.publicRequest("/api/v1/auth/reset", {
                token: String(data.get("token")),
                newPassword: replacement(data),
              })
            }
          >
            <label>
              Recovery token
              <input
                name="token"
                autoComplete="off"
                required
                minLength={43}
                maxLength={43}
              />
            </label>
            <NewPassword />
          </ActionForm>
          <p className="muted">
            Invalid, expired or used tokens share a safe rejection. Request
            recovery again if needed.
          </p>
          <Link className="text-link" to="/forgot">
            Request recovery
          </Link>
        </>
      ) : (
        <>
          <p>
            Recovery is available only for eligible verified accounts and
            requires configured email delivery.
          </p>
          {requested ? (
            <p role="status">
              Recovery requested. If recovery is available for this account,
              instructions may arrive. Delivery is not guaranteed; contact your
              operator if no message arrives.
            </p>
          ) : (
            <ActionForm
              label="Request recovery"
              onSuccess={() => setRequested(true)}
              submit={(data) =>
                session.publicRequest("/api/v1/auth/forgot", {
                  email: String(data.get("email")),
                })
              }
            >
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
            </ActionForm>
          )}
          <p>
            <Link className="text-link" to="/reset">
              I have a recovery token
            </Link>
          </p>
        </>
      )}
      <p>
        <Link className="text-link" to="/sign-in">
          Back to sign in
        </Link>
      </p>
    </section>
  );
}
