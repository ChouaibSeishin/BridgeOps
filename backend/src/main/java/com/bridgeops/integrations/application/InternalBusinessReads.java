package com.bridgeops.integrations.application;

import com.bridgeops.integrations.domain.InternalOperation;
import com.bridgeops.integrations.persistence.BusinessStore;
import com.bridgeops.organizations.application.TenantAuthorization;
import com.bridgeops.shared.web.DomainFailure;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** Registered read/planning handlers; the execution gateway separately binds connection, version and work fence. */
@Service
public class InternalBusinessReads {
    private final BusinessStore store;private final TenantAuthorization authority;
    private final JsonMapper json=JsonMapper.builder().build();
    public InternalBusinessReads(BusinessStore store,TenantAuthorization authority){this.store=store;this.authority=authority;}
    @Transactional(propagation=Propagation.MANDATORY,noRollbackFor=DomainFailure.class)
    public JsonNode read(UUID actor,UUID organization,String capability,String operation,JsonNode arguments){
        authority.trusted(actor,organization,"capability.read");
        var definition=InternalOperation.definition(capability,operation);if(!definition.classification().equals("READ"))throw new DomainFailure(422,"UNSUPPORTED_CAPABILITY_OPERATION");
        var input=json.readTree(InternalOperation.arguments(capability,operation,arguments));var output=json.createObjectNode();
        switch(capability+"."+operation){
            case "support.list" -> {
                var priorities=new ArrayList<String>();input.path("priorities").forEach(v->priorities.add(v.asString()));var statuses=new ArrayList<String>();input.path("statuses").forEach(v->statuses.add(v.asString()));
                var tickets=json.createArrayNode();store.filteredTickets(organization,priorities,statuses,input.path("limit").asInt()).forEach(t->tickets.add(ticket(t)));output.set("tickets",tickets);output.put("count",tickets.size());
            }
            case "support.get" -> output.set("ticket",ticket(store.ticket(organization,UUID.fromString(input.path("ticketId").asString()))));
            case "teams.list" -> {var teams=json.createArrayNode();store.teams(organization).stream().limit(50).forEach(t->teams.add(team(t)));output.set("teams",teams);output.put("count",teams.size());}
            case "teams.get" -> output.set("team",team(store.team(organization,UUID.fromString(input.path("teamId").asString()))));
            case "teams.resolve" -> {
                var responsibilities=new HashMap<String,UUID>();store.responsibilities(organization).forEach(r->responsibilities.put(r.category(),r.teamId()));
                var assignments=json.createArrayNode();var unmatched=json.createArrayNode();
                var ids=new ArrayList<UUID>();input.path("tickets").forEach(t->ids.add(UUID.fromString(t.path("ticketId").asString())));var snapshots=store.snapshotTickets(organization,ids);
                for(var snapshot:input.path("tickets")){
                    var actual=snapshots.get(UUID.fromString(snapshot.path("ticketId").asString()));if(actual==null)throw new DomainFailure(404,"NOT_FOUND");
                    boolean sameTeam=actual.assignedTeamId()==null?snapshot.path("currentTeamId").isNull():actual.assignedTeamId().toString().equals(snapshot.path("currentTeamId").asString());
                    if(actual.revision()!=snapshot.path("revision").asLong()||!sameTeam||!actual.title().equals(snapshot.path("title").asString())||!actual.status().equals(snapshot.path("status").asString())||!actual.priority().equals(snapshot.path("priority").asString())||!actual.category().equals(snapshot.path("category").asString()))throw new DomainFailure(409,"STALE_TICKET_PLAN");
                    if(!Set.of("OPEN","IN_PROGRESS").contains(actual.status()))continue;
                    var target=responsibilities.get(actual.category());
                    if(target==null){var item=json.createObjectNode();item.put("ticketId",actual.id().toString());item.put("category",actual.category());unmatched.add(item);continue;}
                    if(target.equals(actual.assignedTeamId()))continue;
                    var item=json.createObjectNode();item.put("ticketId",actual.id().toString());item.put("expectedRevision",actual.revision());if(actual.assignedTeamId()==null)item.putNull("currentTeamId");else item.put("currentTeamId",actual.assignedTeamId().toString());item.put("targetTeamId",target.toString());assignments.add(item);
                }
                output.set("assignments",assignments);output.set("unmatched",unmatched);output.put("count",assignments.size());
            }
            default -> throw new DomainFailure(422,"UNSUPPORTED_CAPABILITY_OPERATION");
        }
        return output;
    }
    private ObjectNode ticket(BusinessStore.Ticket t){var value=json.createObjectNode();value.put("ticketId",t.id().toString());value.put("title",t.title());value.put("revision",t.revision());if(t.assignedTeamId()==null)value.putNull("currentTeamId");else value.put("currentTeamId",t.assignedTeamId().toString());value.put("status",t.status());value.put("priority",t.priority());value.put("category",t.category());return value;}
    private ObjectNode team(BusinessStore.Team t){var value=json.createObjectNode();value.put("teamId",t.id().toString());value.put("name",t.name());return value;}
}
