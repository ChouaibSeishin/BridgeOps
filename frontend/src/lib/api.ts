export class ApiFailure extends Error {
  constructor(public readonly correlationId?: string) {
    super("The service is unavailable. Try refreshing the connection.");
  }
}
export interface SystemInfo {
  name: string;
  apiVersion: string;
}
export interface Health {
  status: "UP" | "DOWN" | "OUT_OF_SERVICE" | "UNKNOWN";
}

async function request(path: string): Promise<unknown> {
  const response = await fetch(path, {
    headers: { Accept: "application/json" },
    signal: AbortSignal.timeout(5000),
  });
  if (!response.ok)
    throw new ApiFailure(response.headers.get("X-Correlation-ID") ?? undefined);
  return response.json() as Promise<unknown>;
}
export async function getInfo(): Promise<SystemInfo> {
  const value = await request("/api/v1/system/info");
  if (
    !value ||
    typeof value !== "object" ||
    !("name" in value) ||
    !("apiVersion" in value) ||
    typeof value.name !== "string" ||
    typeof value.apiVersion !== "string"
  )
    throw new ApiFailure();
  return { name: value.name, apiVersion: value.apiVersion };
}
export async function getReadiness(): Promise<Health> {
  const value = await request("/health/readiness");
  if (
    !value ||
    typeof value !== "object" ||
    !("status" in value) ||
    value.status !== "UP"
  )
    throw new ApiFailure();
  return { status: "UP" };
}
