package com.bridgeops.integrations.application;

import com.bridgeops.audit.application.BusinessAudit;
import com.bridgeops.executions.application.*;
import com.bridgeops.integrations.persistence.*;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** Not a public application entry point. The independently fenced/approved gateway alone exposes these handlers. */
@Service
class InternalBusinessWrites {
    private final BusinessMutationStore business;private final IntegrationWorkStore receipts;private final BusinessAudit audit;private final Clock clock;
    private final JsonMapper json=JsonMapper.builder().build();
    InternalBusinessWrites(BusinessMutationStore business,IntegrationWorkStore receipts,BusinessAudit audit,Clock clock){this.business=business;this.receipts=receipts;this.audit=audit;this.clock=clock;}
    /** Unexpected exceptions mark the entire transaction for rollback; known per-item conflicts return honest partial results. */
    @Transactional(propagation=Propagation.MANDATORY)
    public CapabilityDispatch.Result perform(ExecutionEffects.Context context,CapabilityDispatch.Binding binding){
        var arguments=json.readTree(binding.arguments());String operation=binding.capability()+"."+binding.operation();
        if(operation.equals("support.assignBatch"))return batch(context,binding,arguments);
        String item=operation.equals("support.addNote")?arguments.path("ticketId").asString():context.node();var existing=receipts.receipt(context.organization(),context.logicalOperation(),operation,item,binding.argumentHash());if(existing.isPresent())return new CapabilityDispatch.Result(existing.get(),false,null,true);
        if(!context.deadline().isAfter(clock.instant()))return failed("DEADLINE_EXCEEDED");Instant now=clock.instant();var result=json.createObjectNode();
        if(operation.equals("support.addNote")){
            UUID ticket=UUID.fromString(item);if(business.hasNote(context.organization(),context.logicalOperation(),ticket))return failed("RECONCILIATION_REQUIRED");long revision=arguments.path("expectedRevision").asLong();if(!business.advanceNoteRevision(context.organization(),ticket,revision,now))return failed("STALE_TICKET_PLAN");
            UUID note=business.note(context.organization(),context.logicalOperation(),ticket,arguments.path("note").asString(),now);result.put("noteId",note.toString());result.put("ticketId",item);result.put("revision",revision+1);audit.write(context.organization(),context.requester(),context.execution(),context.version(),context.hash(),BusinessAudit.Action.TICKET_NOTE_ADDED,ticket,revision+1,now);
        }else if(operation.equals("notifications.create")){
            if(business.hasNotification(context.organization(),context.logicalOperation()))return failed("RECONCILIATION_REQUIRED");UUID notification=business.notification(context.organization(),context.logicalOperation(),arguments.path("message").asString(),now);result.put("notificationId",notification.toString());result.put("status","DELIVERED_INTERNAL");audit.write(context.organization(),context.requester(),context.execution(),context.version(),context.hash(),BusinessAudit.Action.INTERNAL_NOTIFICATION_RECORDED,notification,0,now);
        }else throw new IllegalArgumentException("Unsupported internal write handler.");
        return new CapabilityDispatch.Result(receipts.commit(context.organization(),context.execution(),context.logicalOperation(),operation,item,binding.argumentHash(),result.toString(),now),false,null,false);
    }
    private CapabilityDispatch.Result batch(ExecutionEffects.Context c,CapabilityDispatch.Binding binding,JsonNode args){
        var committed=receipts.receipts(c.organization(),c.logicalOperation(),"support.assignBatch",binding.argumentHash());var items=json.createArrayNode();int applied=0,index=0;String failure=null;boolean reused=false;
        for(var item:args.path("assignments")){
            UUID ticket=UUID.fromString(item.path("ticketId").asString()),target=UUID.fromString(item.path("targetTeamId").asString());long expected=item.path("expectedRevision").asLong();var result=json.createObjectNode();result.put("ticketId",ticket.toString());result.put("targetTeamId",target.toString());Instant now=clock.instant();
            if(failure!=null){result.put("status","NOT_ATTEMPTED");items.add(result);index++;continue;}
            if(committed.containsKey(ticket.toString())){result=(ObjectNode)json.readTree(committed.get(ticket.toString()));result.put("status","RECEIPT_REUSED");items.add(result);applied++;reused=true;business.batchItem(c.organization(),c.execution(),c.logicalOperation(),index++,ticket,expected,target,"RECEIPT_REUSED","RECEIPT_REUSED",now);continue;}
            if(!c.deadline().isAfter(now)){failure="DEADLINE_EXCEEDED";result.put("status","NOT_ATTEMPTED");result.put("code",failure);items.add(result);index++;continue;}
            UUID previous=item.path("currentTeamId").isNull()?null:UUID.fromString(item.path("currentTeamId").asString());
            if(!business.assign(c.organization(),ticket,expected,previous,target,now)){failure="STALE_TICKET_PLAN";result.put("status","CONFLICT");result.put("code",failure);items.add(result);business.batchItem(c.organization(),c.execution(),c.logicalOperation(),index++,ticket,expected,target,"CONFLICT",failure,now);continue;}
            result.put("status","APPLIED");result.put("revision",expected+1);receipts.commit(c.organization(),c.execution(),c.logicalOperation(),"support.assignBatch",ticket.toString(),binding.argumentHash(),result.toString(),now);business.batchItem(c.organization(),c.execution(),c.logicalOperation(),index++,ticket,expected,target,"APPLIED","APPLIED",now);audit.write(c.organization(),c.requester(),c.execution(),c.version(),c.hash(),BusinessAudit.Action.TICKET_ASSIGNED,ticket,expected+1,now);items.add(result);applied++;
        }
        var output=json.createObjectNode();output.set("items",items);output.put("applied",applied);output.put("count",args.path("assignments").size());return new CapabilityDispatch.Result(output.toString(),failure!=null,failure,reused);
    }
    private CapabilityDispatch.Result failed(String code){return new CapabilityDispatch.Result("{}",true,code,false);}
}
