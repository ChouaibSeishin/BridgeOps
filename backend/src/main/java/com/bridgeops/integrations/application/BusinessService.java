package com.bridgeops.integrations.application;

import com.bridgeops.audit.application.IntegrationAudit;
import com.bridgeops.integrations.persistence.BusinessStore;
import com.bridgeops.organizations.application.TenantAuthorization;
import com.bridgeops.shared.web.DomainFailure;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class BusinessService {
    public record Ticket(UUID id,String title,String status,String priority,String category,UUID assignedTeamId,long revision,String provenance,Instant updatedAt) {}
    public record Team(UUID id,String name,String provenance) {}
    public record Responsibility(String category,UUID teamId) {}
    public record Notification(UUID id,String message,String status,String provenance,Instant createdAt) {}
    public record Attempt(int number,String outcome,Instant occurredAt) {}
    public record Page<T>(List<T> items,int page,int size,long totalElements,long totalPages) {}
    public record DemoResult(boolean created,int teams,int tickets,int notifications,String provenance) {}
    public record Assignment(UUID ticketId,long expectedRevision,UUID currentTeamId,UUID targetTeamId) {}
    private final BusinessStore store;private final TenantAuthorization authority;private final IntegrationAudit audit;private final Clock clock;
    public BusinessService(BusinessStore store,TenantAuthorization authority,IntegrationAudit audit,Clock clock){this.store=store;this.authority=authority;this.audit=audit;this.clock=clock;}
    @Transactional(readOnly=true,timeout=5)
    public Page<Ticket> tickets(UUID actor,UUID family,UUID org,String status,String priority,String category,int page,int size){
        authority.read(actor,family,org,"capability.read");bounds(page,size);filter(status,Set.of("OPEN","IN_PROGRESS","RESOLVED","CLOSED"));filter(priority,Set.of("LOW","NORMAL","HIGH","URGENT"));filter(category,Set.of("BILLING","PRODUCT","ACCESS","OTHER"));
        long total=store.count(org,status,priority,category);return page(store.tickets(org,status,priority,category,size,Math.multiplyExact(page,size)).stream().map(this::ticket).toList(),page,size,total);
    }
    @Transactional(readOnly=true,timeout=5) public Ticket ticket(UUID actor,UUID family,UUID org,UUID id){authority.read(actor,family,org,"capability.read");return ticket(store.ticket(org,id));}
    @Transactional(readOnly=true,timeout=5) public List<Team> teams(UUID actor,UUID family,UUID org){authority.read(actor,family,org,"capability.read");return store.teams(org).stream().map(t->new Team(t.id(),t.name(),t.provenance())).toList();}
    @Transactional(readOnly=true,timeout=5) public Team team(UUID actor,UUID family,UUID org,UUID id){authority.read(actor,family,org,"capability.read");var t=store.team(org,id);return new Team(t.id(),t.name(),t.provenance());}
    @Transactional(readOnly=true,timeout=5) public List<Responsibility> responsibilities(UUID actor,UUID family,UUID org){authority.read(actor,family,org,"capability.read");return store.responsibilities(org).stream().map(r->new Responsibility(r.category(),r.teamId())).toList();}
    @Transactional(readOnly=true,timeout=5) public Page<Notification> notifications(UUID actor,UUID family,UUID org,int page,int size){authority.read(actor,family,org,"capability.read");bounds(page,size);return page(store.notifications(org,size,page*size).stream().map(this::notification).toList(),page,size,store.notificationCount(org));}
    @Transactional(readOnly=true,timeout=5) public Notification notification(UUID actor,UUID family,UUID org,UUID id){authority.read(actor,family,org,"capability.read");return notification(store.notification(org,id));}
    @Transactional(readOnly=true,timeout=5) public List<Attempt> attempts(UUID actor,UUID family,UUID org,UUID id){authority.read(actor,family,org,"capability.read");return store.attempts(org,id).stream().map(a->new Attempt(a.number(),a.outcome(),a.occurredAt())).toList();}
    @Transactional(timeout=10) public DemoResult demo(UUID actor,UUID family,UUID org,boolean confirmed){
        authority.write(actor,family,org,"integration.manage");authority.write(actor,family,org,"capability.write");if(!confirmed)throw new DomainFailure(422,"DEMO_CONFIRMATION_REQUIRED");
        boolean created=store.seed(org,actor,clock.instant());if(created)audit.write(org,actor,IntegrationAudit.Action.DEMO_DATA_INSTALLED,org,0,clock.instant());
        return new DemoResult(created,store.teams(org).size(),Math.toIntExact(store.count(org,null,null,null)),Math.toIntExact(store.notificationCount(org)),"SEEDED_EXAMPLE");
    }
    private Ticket ticket(BusinessStore.Ticket t){return new Ticket(t.id(),t.title(),t.status(),t.priority(),t.category(),t.assignedTeamId(),t.revision(),t.provenance(),t.updatedAt());}
    private Notification notification(BusinessStore.Notification n){return new Notification(n.id(),n.message(),n.status(),n.provenance(),n.createdAt());}
    private static void filter(String value,Set<String> allowed){if(value!=null&&!allowed.contains(value))throw new DomainFailure(400,"INVALID_REQUEST");}
    private static void bounds(int page,int size){if(page<0||page>100000||size<1||size>100)throw new DomainFailure(400,"INVALID_REQUEST");}
    private static <T> Page<T> page(List<T> items,int page,int size,long total){return new Page<>(items,page,size,total,(total+size-1)/size);}
}
