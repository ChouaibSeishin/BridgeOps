/** Browser edits must not silently round signed 64-bit integers accepted by the server. */
export function requiresExactNumbers(value: unknown): boolean {
  if (typeof value === "number")
    return (
      !Number.isFinite(value) ||
      (Number.isInteger(value) && !Number.isSafeInteger(value))
    );
  if (Array.isArray(value)) return value.some(requiresExactNumbers);
  return Boolean(
    value &&
    typeof value === "object" &&
    Object.values(value).some(requiresExactNumbers),
  );
}
