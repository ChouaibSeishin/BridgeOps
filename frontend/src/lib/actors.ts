export interface ActorReference {
  type: "USER" | "AUTOMATION";
  id: string;
  configuringHumans: string[];
}
export interface AutomationOrigin {
  principal: string;
  principalCreator: string;
  endpoint: string;
  endpointRevision: number;
  endpointAuthor: string;
}
