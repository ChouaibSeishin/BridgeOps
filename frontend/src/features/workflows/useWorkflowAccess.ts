import { useQuery } from "@tanstack/react-query";
import { session } from "../../lib/session";

export function useWorkflowAccess(user: string, organization: string) {
  return useQuery({
    queryKey: [user, "workflow-access", organization],
    queryFn: () =>
      session.request<{ membershipId: string; permissions: string[] }>(
        `/api/v1/organizations/${encodeURIComponent(organization)}/access`,
      ),
    retry: false,
    enabled: Boolean(organization),
    refetchOnWindowFocus: false,
  });
}
