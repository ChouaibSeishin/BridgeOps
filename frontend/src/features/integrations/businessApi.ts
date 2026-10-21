import { session } from "../../lib/session";
import type { Page } from "../workflows/model";
export interface Ticket {
  id: string;
  title: string;
  status: string;
  priority: string;
  category: string;
  assignedTeamId: string | null;
  revision: number;
  provenance: string;
  updatedAt: string;
}
export interface Team {
  id: string;
  name: string;
  provenance: string;
}
export interface InternalNotification {
  id: string;
  message: string;
  status: string;
  provenance: string;
  createdAt: string;
}
export interface DeliveryAttempt {
  number: number;
  outcome: string;
  occurredAt: string;
}
const base = (org: string) =>
  `/api/v1/organizations/${encodeURIComponent(org)}`;
export const businessApi = {
  tickets: (
    org: string,
    page: number,
    status: string,
    priority: string,
    category: string,
  ) => {
    const query = new URLSearchParams({ page: String(page), size: "10" });
    if (status) query.set("status", status);
    if (priority) query.set("priority", priority);
    if (category) query.set("category", category);
    return session.request<Page<Ticket>>(
      `${base(org)}/support/tickets?${query}`,
    );
  },
  teams: (org: string) => session.request<Team[]>(`${base(org)}/teams`),
  responsibilities: (org: string) =>
    session.request<{ category: string; teamId: string }[]>(
      `${base(org)}/teams/responsibilities`,
    ),
  notifications: (org: string, page: number) =>
    session.request<Page<InternalNotification>>(
      `${base(org)}/notifications?page=${page}&size=10`,
    ),
  attempts: (org: string, id: string) =>
    session.request<DeliveryAttempt[]>(
      `${base(org)}/notifications/${encodeURIComponent(id)}/attempts`,
    ),
  seed: (org: string) =>
    session.request<{
      created: boolean;
      teams: number;
      tickets: number;
      notifications: number;
      provenance: string;
    }>(`${base(org)}/demo-data`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ confirmed: true }),
    }),
};
