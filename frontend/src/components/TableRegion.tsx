import type { ReactNode } from "react";

/** Keeps dense records inside a keyboard-scrollable region, not the page. */
export function TableRegion({
  label,
  children,
}: {
  label: string;
  children: ReactNode;
}) {
  return (
    <div className="table-scroll" role="region" aria-label={label} tabIndex={0}>
      {children}
    </div>
  );
}
