package com.bridgeops.integrations.application;

import com.bridgeops.integrations.persistence.*;
import com.bridgeops.shared.web.DomainFailure;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import tools.jackson.databind.JsonNode;

/** Package-private checks; public decision/worker entry points authorize and independently bind their context first. */
@Service
class BusinessPreconditions {
    private final BusinessStore business;private final BusinessMutationStore mutations;private final IntegrationWorkStore receipts;
    BusinessPreconditions(BusinessStore business,BusinessMutationStore mutations,IntegrationWorkStore receipts){this.business=business;this.mutations=mutations;this.receipts=receipts;}
    @Transactional(propagation=Propagation.MANDATORY,noRollbackFor=DomainFailure.class)
    public void check(UUID org,UUID logical,String node,String capability,String operation,String hash,JsonNode arguments){
        String name=capability+"."+operation;var committed=receipts.receipts(org,logical,name,hash);
        if(Set.of("support.list","support.get","teams.list","teams.get","teams.resolve").contains(name)&&!committed.isEmpty()){if(!Set.of(node).containsAll(committed.keySet()))throw new DomainFailure(409,"RECONCILIATION_REQUIRED");return;}
        switch(name){
            case "support.assignBatch" -> {
                var ids=new ArrayList<UUID>();var targets=new HashSet<UUID>();for(var item:arguments.path("assignments")){ids.add(uuid(item,"ticketId"));targets.add(uuid(item,"targetTeamId"));}
                if(!ids.stream().map(UUID::toString).toList().containsAll(committed.keySet()))throw new DomainFailure(409,"RECONCILIATION_REQUIRED");var tickets=business.snapshotTickets(org,ids);var teams=business.teamIds(org,List.copyOf(targets));
                for(var item:arguments.path("assignments")){UUID id=uuid(item,"ticketId");if(committed.containsKey(id.toString()))continue;var ticket=tickets.get(id);if(ticket==null||!teams.contains(uuid(item,"targetTeamId")))throw new DomainFailure(404,"NOT_FOUND");if(!matches(ticket,item)||!Set.of("OPEN","IN_PROGRESS").contains(ticket.status()))throw stale();}
            }
            case "support.addNote" -> {
                UUID id=uuid(arguments,"ticketId");if(!Set.of(id.toString()).containsAll(committed.keySet()))throw new DomainFailure(409,"RECONCILIATION_REQUIRED");if(committed.containsKey(id.toString()))return;
                if(mutations.hasNote(org,logical,id))throw new DomainFailure(409,"RECONCILIATION_REQUIRED");if(business.ticket(org,id).revision()!=arguments.path("expectedRevision").asLong())throw stale();
            }
            case "notifications.create" -> {if(!Set.of(node).containsAll(committed.keySet()))throw new DomainFailure(409,"RECONCILIATION_REQUIRED");if(committed.isEmpty()&&mutations.hasNotification(org,logical))throw new DomainFailure(409,"RECONCILIATION_REQUIRED");}
            case "support.get" -> business.ticket(org,uuid(arguments,"ticketId"));
            case "teams.get" -> business.team(org,uuid(arguments,"teamId"));
            case "teams.resolve" -> {
                var ids=new ArrayList<UUID>();arguments.path("tickets").forEach(t->ids.add(uuid(t,"ticketId")));var snapshots=business.snapshotTickets(org,ids);
                for(var expected:arguments.path("tickets")){var actual=snapshots.get(uuid(expected,"ticketId"));if(actual==null)throw new DomainFailure(404,"NOT_FOUND");boolean sameTeam=actual.assignedTeamId()==null?expected.path("currentTeamId").isNull():actual.assignedTeamId().toString().equals(expected.path("currentTeamId").asString());if(actual.revision()!=expected.path("revision").asLong()||!sameTeam||!actual.title().equals(expected.path("title").asString())||!actual.status().equals(expected.path("status").asString())||!actual.priority().equals(expected.path("priority").asString())||!actual.category().equals(expected.path("category").asString()))throw stale();}
            }
            case "support.list","teams.list" -> { }
            default -> throw new DomainFailure(422,"UNSUPPORTED_CAPABILITY_OPERATION");
        }
    }
    private boolean matches(BusinessStore.Ticket ticket,JsonNode item){return ticket.revision()==item.path("expectedRevision").asLong()&&Objects.equals(ticket.assignedTeamId(),item.path("currentTeamId").isNull()?null:uuid(item,"currentTeamId"));}
    private UUID uuid(JsonNode value,String key){return UUID.fromString(value.path(key).asString());}
    private DomainFailure stale(){return new DomainFailure(409,"STALE_TICKET_PLAN");}
}
