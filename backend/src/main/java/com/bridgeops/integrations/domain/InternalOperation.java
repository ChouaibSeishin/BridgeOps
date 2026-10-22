package com.bridgeops.integrations.domain;

import com.bridgeops.shared.web.DomainFailure;
import java.math.BigInteger;
import java.util.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Fixed internal schemas. These definitions confer neither tenant authority nor permission to dispatch. */
public final class InternalOperation {
    public record Definition(String capability,String id,String classification,boolean requiresApproval,
        Map<String,String> inputs,Map<String,String> outputs){
        public Definition{inputs=Map.copyOf(inputs);outputs=Map.copyOf(outputs);}
    }
    private static final JsonMapper JSON=JsonMapper.builder().build();
    private static final Set<String> PRIORITIES=Set.of("LOW","NORMAL","HIGH","URGENT");
    private static final Set<String> STATUSES=Set.of("OPEN","IN_PROGRESS","RESOLVED","CLOSED");
    private static final Set<String> CATEGORIES=Set.of("BILLING","PRODUCT","ACCESS","OTHER");
    private static final List<Definition> DEFINITIONS=List.of(
        new Definition("support","list","READ",false,Map.of("limit","INTEGER","priorities","ARRAY","statuses","ARRAY"),Map.of("tickets","ARRAY","count","INTEGER")),
        new Definition("support","get","READ",false,Map.of("ticketId","STRING"),Map.of("ticket","OBJECT")),
        new Definition("support","assignBatch","WRITE",true,Map.of("assignments","ARRAY"),Map.of("items","ARRAY","applied","INTEGER","count","INTEGER")),
        new Definition("support","addNote","WRITE",true,Map.of("ticketId","STRING","expectedRevision","INTEGER","note","STRING"),Map.of("noteId","STRING","ticketId","STRING","revision","INTEGER")),
        new Definition("teams","list","READ",false,Map.of(),Map.of("teams","ARRAY","count","INTEGER")),
        new Definition("teams","get","READ",false,Map.of("teamId","STRING"),Map.of("team","OBJECT")),
        new Definition("teams","resolve","READ",false,Map.of("tickets","ARRAY"),Map.of("assignments","ARRAY","unmatched","ARRAY","count","INTEGER")),
        new Definition("notifications","create","WRITE",false,Map.of("message","STRING"),Map.of("notificationId","STRING","status","STRING"))
    );
    private InternalOperation(){}
    public static Definition definition(String capability,String operation){return DEFINITIONS.stream().filter(d->d.capability().equals(capability)&&d.id().equals(operation)).findFirst().orElseThrow(()->new DomainFailure(422,"UNSUPPORTED_CAPABILITY_OPERATION"));}
    public static List<Definition> definitions(String capability){return DEFINITIONS.stream().filter(d->d.capability().equals(capability)).toList();}
    /** Canonical output includes list defaults, sorted filter sets, normalized UUIDs and stable batch order. */
    public static String arguments(String capability,String operation,JsonNode input){
        definition(capability,operation);
        if(input==null||!input.isObject()||JSON.writeValueAsBytes(input).length>262144)throw invalid();
        var result=JSON.createObjectNode();
        switch(capability+"."+operation){
            case "support.list" -> {
                if(!Set.of("limit","priorities","statuses").containsAll(input.propertyNames()))throw invalid();
                result.put("limit",input.has("limit")?integer(input.get("limit"),1,50):50);
                result.set("priorities",filter(input.get("priorities"),PRIORITIES));result.set("statuses",filter(input.get("statuses"),STATUSES));
            }
            case "support.get" -> {exact(input,Set.of("ticketId"));result.put("ticketId",uuid(input.get("ticketId")));}
            case "teams.get" -> {exact(input,Set.of("teamId"));result.put("teamId",uuid(input.get("teamId")));}
            case "teams.list" -> exact(input,Set.of());
            case "notifications.create" -> {exact(input,Set.of("message"));result.put("message",text(input.get("message"),500));}
            case "support.addNote" -> {
                exact(input,Set.of("ticketId","expectedRevision","note"));result.put("ticketId",uuid(input.get("ticketId")));result.put("expectedRevision",integer(input.get("expectedRevision"),0,Long.MAX_VALUE-1));result.put("note",text(input.get("note"),500));
            }
            case "support.assignBatch" -> {
                exact(input,Set.of("assignments"));var items=input.get("assignments");array(items,1);var seen=new HashSet<String>();var out=JSON.createArrayNode();
                for(var item:items){exact(item,Set.of("ticketId","expectedRevision","currentTeamId","targetTeamId"));var value=JSON.createObjectNode();String id=uuid(item.get("ticketId"));if(!seen.add(id))throw invalid();value.put("ticketId",id);value.put("expectedRevision",integer(item.get("expectedRevision"),0,Long.MAX_VALUE-1));nullableUuid(value,"currentTeamId",item.get("currentTeamId"));value.put("targetTeamId",uuid(item.get("targetTeamId")));out.add(value);}result.set("assignments",out);
            }
            case "teams.resolve" -> {
                exact(input,Set.of("tickets"));var items=input.get("tickets");array(items,0);var seen=new HashSet<String>();var out=JSON.createArrayNode();
                for(var item:items){exact(item,Set.of("ticketId","title","revision","currentTeamId","status","priority","category"));var value=JSON.createObjectNode();String id=uuid(item.get("ticketId"));if(!seen.add(id))throw invalid();value.put("ticketId",id);value.put("title",text(item.get("title"),200));value.put("revision",integer(item.get("revision"),0,Long.MAX_VALUE));nullableUuid(value,"currentTeamId",item.get("currentTeamId"));value.put("status",enumeration(item.get("status"),STATUSES));value.put("priority",enumeration(item.get("priority"),PRIORITIES));value.put("category",enumeration(item.get("category"),CATEGORIES));out.add(value);}result.set("tickets",out);
            }
            default -> throw new DomainFailure(422,"UNSUPPORTED_CAPABILITY_OPERATION");
        }
        return JSON.writeValueAsString(sorted(result));
    }
    private static JsonNode sorted(JsonNode node){if(node.isObject()){var out=JSON.createObjectNode();node.propertyNames().stream().sorted().forEach(k->out.set(k,sorted(node.get(k))));return out;}if(node.isArray()){var out=JSON.createArrayNode();node.forEach(v->out.add(sorted(v)));return out;}return node;}
    private static JsonNode filter(JsonNode value,Set<String> allowed){var items=new TreeSet<String>();if(value==null)items.addAll(allowed);else{array(value,1);for(var item:value)if(!items.add(enumeration(item,allowed)))throw invalid();}var out=JSON.createArrayNode();items.forEach(out::add);return out;}
    private static void array(JsonNode value,int minimum){if(value==null||!value.isArray()||value.size()<minimum||value.size()>50)throw invalid();}
    private static String enumeration(JsonNode value,Set<String> allowed){if(value==null||!value.isString()||!allowed.contains(value.asString()))throw invalid();return value.asString();}
    private static void exact(JsonNode value,Set<String> fields){if(value==null||!value.isObject()||!value.propertyNames().equals(fields))throw invalid();}
    private static long integer(JsonNode value,long minimum,long maximum){if(value==null||!value.isIntegralNumber()||value.bigIntegerValue().compareTo(BigInteger.valueOf(minimum))<0||value.bigIntegerValue().compareTo(BigInteger.valueOf(maximum))>0)throw invalid();return value.asLong();}
    private static String uuid(JsonNode value){if(value==null||!value.isString()||!value.asString().matches("[a-fA-F0-9]{8}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{12}"))throw invalid();return UUID.fromString(value.asString()).toString();}
    private static void nullableUuid(tools.jackson.databind.node.ObjectNode target,String field,JsonNode value){if(value!=null&&value.isNull())target.putNull(field);else target.put(field,uuid(value));}
    private static String text(JsonNode value,int maximum){if(value==null||!value.isString()||value.asString().isBlank()||value.asString().length()>maximum||value.asString().codePoints().anyMatch(c->Character.isISOControl(c)&&c!='\n'&&c!='\t'))throw invalid();return value.asString();}
    private static DomainFailure invalid(){return new DomainFailure(422,"INVALID_CAPABILITY_INPUT");}
}
