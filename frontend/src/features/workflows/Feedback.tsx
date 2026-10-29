import { failureMessage } from "../../lib/session";
import type { Validation } from "./model";
import { isCanvasGraph, canvasLabels } from "./canvasModel";

export function Problem({
  error,
  retry,
}: {
  error: unknown;
  retry: () => void;
}) {
  return (
    <div className="error-panel" role="alert">
      <p>{failureMessage(error)}</p>
      <button className="button" onClick={retry}>
        Try again
      </button>
    </div>
  );
}
export function Pager({
  page,
  pages,
  change,
}: {
  page: number;
  pages: number;
  change: (page: number) => void;
}) {
  if (pages <= 1) return null;
  return (
    <nav className="action-row" aria-label="Pagination">
      <button
        className="button"
        disabled={page === 0}
        onClick={() => change(page - 1)}
      >
        Previous page
      </button>
      <span>
        Page {page + 1} of {pages}
      </span>
      <button
        className="button"
        disabled={page + 1 >= pages}
        onClick={() => change(page + 1)}
      >
        Next page
      </button>
    </nav>
  );
}
export function ValidationResults({
  result,
  definition,
}: {
  result?: Validation;
  definition?: unknown;
}) {
  if (!result) return null;
  const labels = isCanvasGraph(definition)
    ? canvasLabels(definition)
    : new Map<string, string>();
  return (
    <section
      className="validation-results"
      aria-label="Saved definition validation"
      aria-live="polite"
    >
      <h2>
        {result.valid
          ? "Saved definition is valid"
          : "Saved definition needs changes"}
      </h2>
      {result.valid ? (
        <p>
          The server validated the persisted revision. Publication validates it
          again.
        </p>
      ) : (
        <ul>
          {result.errors.map((error, index) => (
            <li key={index}>
              {error.nodeId ? (
                <a href={`#node-${error.nodeId}`}>
                  {labels.get(error.nodeId) ?? "Workflow node"}
                </a>
              ) : (
                <strong>Definition</strong>
              )}
              : {error.message}{" "}
              <small className="mono">
                {error.path} · {error.code}
              </small>
              {error.nodeId && (
                <details>
                  <summary>Validation technical details</summary>
                  <code>{error.nodeId}</code>
                </details>
              )}
            </li>
          ))}
        </ul>
      )}
    </section>
  );
}
