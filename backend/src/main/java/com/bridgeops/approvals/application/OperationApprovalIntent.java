package com.bridgeops.approvals.application;

import com.bridgeops.shared.domain.ActorReference;
import java.math.BigInteger;
import java.time.*;
import java.util.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Schema2 application-specific canonical intent. Does not itself authorize, persist or dispatch an effect. */
public record OperationApprovalIntent(UUID organization,UUID execution,String gateNode,String actionNode,UUID version,
    String definitionHash,ActorReference actor,UUID logicalOperation,UUID preparedIntent,String capability,String operation,
    String classification,Connection connection,long policyVersion,String policySnapshot,String prompt,
    Instant createdAt,Instant expiresAt,Instant deadlineAt,String arguments,String argumentHash,String canonical,String hash){
    public static final int MAX_ARGUMENT_BYTES=256*1024;
    public static final int MAX_CANONICAL_BYTES=MAX_ARGUMENT_BYTES+16384;
    private static final JsonMapper JSON=JsonMapper.builder().build();
    public record Connection(UUID id,long revision,int implementationVersion,String operationId){
        public Connection{if(id==null||revision<0||implementationVersion!=1||operationId==null||!operationId.matches("[A-Za-z][A-Za-z0-9_.-]{0,63}"))throw new IllegalArgumentException("Invalid connection reference.");}
    }
    public static OperationApprovalIntent create(UUID organization,UUID execution,String gate,String action,UUID version,
        String definitionHash,ActorReference actor,UUID logicalOperation,UUID preparedIntent,String capability,String operation,
        String classification,Connection connection,JsonNode arguments,long policyVersion,JsonNode policy,String prompt,
        Instant created,Instant expires,Instant deadline){
        if(organization==null||execution==null||version==null||actor==null||logicalOperation==null||preparedIntent==null
            ||gate==null||!gate.matches("[A-Za-z][A-Za-z0-9_-]{0,63}")||action==null||!action.matches("[A-Za-z][A-Za-z0-9_-]{0,63}")
            ||definitionHash==null||!definitionHash.matches("[a-f0-9]{64}")||policyVersion<0||prompt==null||prompt.isBlank()||prompt.length()>500
            ||classification==null||!Set.of("READ","WRITE").contains(classification)||created==null||expires==null||deadline==null)
            throw invalid();
        boolean supported=capability!=null&&operation!=null&&switch(capability){
            case "synthetic" -> operation.equals("record")&&classification.equals("WRITE");
            case "support" -> Set.of("list","get","assignBatch","addNote").contains(operation)&&classification.equals(Set.of("list","get").contains(operation)?"READ":"WRITE");
            case "teams" -> Set.of("list","get","resolve").contains(operation)&&classification.equals("READ");
            case "notifications" -> operation.equals("create")&&classification.equals("WRITE");
            case "rest" -> operation.matches("[A-Za-z][A-Za-z0-9_-]{0,63}"); default -> false;
        };
        if(!supported||(capability.equals("synthetic")?connection!=null:connection==null)||connection!=null&&!connection.operationId().equals(operation))throw invalid();
        if(policy==null||!policy.isObject()||!policy.propertyNames().equals(Set.of("required","lifetimeSeconds"))||!policy.path("required").isBoolean()||!integer(policy.get("lifetimeSeconds"),60,86400))throw invalid();
        created=created.truncatedTo(java.time.temporal.ChronoUnit.MICROS);expires=expires.truncatedTo(java.time.temporal.ChronoUnit.MICROS);deadline=deadline.truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        if(!expires.isAfter(created)||expires.isAfter(deadline)||expires.isAfter(created.plusSeconds(policy.path("lifetimeSeconds").asLong()))||!deadline.isAfter(created))throw invalid();
        String args=canonicalArguments(arguments),policyJson=JSON.writeValueAsString(sorted(policy));
        var fields=new TreeMap<String,Object>();fields.put("schemaVersion",2);fields.put("requestRevision",0);fields.put("operationRuleVersion",1);
        fields.put("organization",organization.toString());fields.put("execution",execution.toString());fields.put("gateNode",gate);fields.put("actionNode",action);fields.put("version",version.toString());fields.put("definitionHash",definitionHash);
        fields.put("actor",new TreeMap<>(Map.of("type",actor.type().name(),"id",actor.id().toString(),"configuredBy",actor.configuringHumans().stream().map(UUID::toString).toList())));
        fields.put("logicalOperation",logicalOperation.toString());fields.put("preparedIntent",preparedIntent.toString());fields.put("capability",capability);fields.put("operation",operation);fields.put("classification",classification);
        fields.put("connection",connection==null?null:new TreeMap<>(Map.of("id",connection.id().toString(),"revision",connection.revision(),"implementationVersion",connection.implementationVersion(),"operationId",connection.operationId())));
        fields.put("arguments",JSON.readTree(args));fields.put("policyVersion",policyVersion);fields.put("policySnapshot",JSON.readTree(policyJson));fields.put("prompt",prompt);fields.put("createdAt",created.toString());fields.put("expiresAt",expires.toString());fields.put("deadlineAt",deadline.toString());
        String canonical=JSON.writeValueAsString(fields);if(canonical.getBytes(java.nio.charset.StandardCharsets.UTF_8).length>MAX_CANONICAL_BYTES)throw invalid();
        return new OperationApprovalIntent(organization,execution,gate,action,version,definitionHash,actor,logicalOperation,preparedIntent,capability,operation,classification,connection,policyVersion,policyJson,prompt,created,expires,deadline,args,ApprovalIntent.digest(args),canonical,ApprovalIntent.digest(canonical));
    }
    public static String canonicalArguments(JsonNode arguments){
        if(arguments==null||!arguments.isObject()||JSON.writeValueAsBytes(arguments).length>MAX_ARGUMENT_BYTES)throw invalid();
        record Item(JsonNode value,int depth){}var work=new ArrayDeque<Item>();work.add(new Item(arguments,0));int count=0;
        while(!work.isEmpty()){
            var entry=work.remove();var value=entry.value();if(++count>16384||entry.depth()>16)throw invalid();
            if(value.isContainer()){if(value.isArray()&&value.size()>50||value.isObject()&&(value.size()>64||value.propertyNames().stream().anyMatch(k->!k.matches("[A-Za-z][A-Za-z0-9_]{0,63}"))))throw invalid();for(var child:value)work.add(new Item(child,entry.depth()+1));}
            else if(value.isString()){if(value.asString().length()>2000||value.asString().indexOf('\0')>=0)throw invalid();}
            else if(!value.isNull()&&!value.isBoolean()&&!integer(value,Long.MIN_VALUE,Long.MAX_VALUE))throw invalid();
        }
        return JSON.writeValueAsString(sorted(arguments));
    }
    private static JsonNode sorted(JsonNode node){if(node.isObject()){var object=JSON.createObjectNode();node.propertyNames().stream().sorted().forEach(k->object.set(k,sorted(node.get(k))));return object;}if(node.isArray()){var array=JSON.createArrayNode();node.forEach(v->array.add(sorted(v)));return array;}return node;}
    private static boolean integer(JsonNode value,long min,long max){return value!=null&&value.isIntegralNumber()&&value.bigIntegerValue().compareTo(BigInteger.valueOf(min))>=0&&value.bigIntegerValue().compareTo(BigInteger.valueOf(max))<=0;}
    private static IllegalArgumentException invalid(){return new IllegalArgumentException("Invalid operation approval intent.");}
    @Override public String toString(){return "OperationApprovalIntent[REDACTED]";}
}
