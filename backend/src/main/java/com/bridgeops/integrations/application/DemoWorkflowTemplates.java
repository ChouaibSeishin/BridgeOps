package com.bridgeops.integrations.application;

import java.util.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Fixed, inspectable internal examples. Creating a definition never executes it or grants webhook authority. */
final class DemoWorkflowTemplates {
    private static final JsonMapper JSON=JsonMapper.builder().build();
    private DemoWorkflowTemplates(){}
    static JsonNode definition(boolean webhook,UUID support,long supportRevision,UUID teams,long teamsRevision,UUID notifications,long notificationRevision){
        var nodes=JSON.createArrayNode();
        nodes.add(node("start",webhook?"webhookTrigger":"manualTrigger",Map.of("inputFields",Map.of("limit","INTEGER")),Map.of()));
        nodes.add(node("read","capabilityAction",connection(support,supportRevision,"support.list"),Map.of("limit",from("start","limit"),"priorities",literal(List.of("HIGH","URGENT")),"statuses",literal(List.of("OPEN","IN_PROGRESS")))));
        nodes.add(node("resolve","capabilityAction",connection(teams,teamsRevision,"teams.resolve"),Map.of("tickets",from("read","tickets"))));
        nodes.add(node("hasWork","condition",Map.of("operator","GT","compare",0),Map.of("value",from("resolve","count"))));
        nodes.add(node("review","approval",Map.of("prompt","Review the exact ticket IDs, expected revisions, prior assignments and target teams before assignment."),Map.of()));
        nodes.add(node("assign","capabilityAction",connection(support,supportRevision,"support.assignBatch"),Map.of("assignments",from("resolve","assignments"))));
        nodes.add(node("notify","notification",Map.of("integrationId",notifications.toString(),"revision",notificationRevision),Map.of("message",Map.of("parts",List.of(literal("Assigned "),from("assign","applied"),literal(" tickets"))))));
        nodes.add(node("noWork","transform",Map.of(),Map.of("result",literal("No assignment needed"))));nodes.add(node("finish","end",Map.of(),Map.of()));
        var edges=JSON.createArrayNode();for(var pair:List.of(List.of("start","read"),List.of("read","resolve"),List.of("resolve","hasWork"),List.of("review","assign"),List.of("assign","notify"),List.of("notify","finish"),List.of("noWork","finish")))edges.add(JSON.valueToTree(Map.of("source",pair.get(0),"target",pair.get(1))));
        edges.add(JSON.valueToTree(Map.of("source","hasWork","target","review","branch","true")));edges.add(JSON.valueToTree(Map.of("source","hasWork","target","noWork","branch","false")));
        var root=JSON.createObjectNode();root.put("schemaVersion",2);root.set("nodes",nodes);root.set("edges",edges);return root;
    }
    private static Map<String,Object> connection(UUID id,long revision,String operation){return Map.of("integrationId",id.toString(),"revision",revision,"operation",operation);}
    private static Map<String,String> from(String node,String path){return Map.of("from",node,"path",path);}
    private static Map<String,Object> literal(Object value){return Map.of("literal",value);}
    private static JsonNode node(String id,String type,Map<String,?> config,Map<String,?> inputs){return JSON.valueToTree(Map.of("id",id,"type",type,"typeVersion",1,"config",config,"inputs",inputs));}
}
