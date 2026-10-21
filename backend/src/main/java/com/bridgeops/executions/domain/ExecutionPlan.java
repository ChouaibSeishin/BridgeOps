package com.bridgeops.executions.domain;

import com.bridgeops.shared.web.DomainFailure;
import com.bridgeops.workflows.application.WorkflowDefinitions;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import tools.jackson.databind.JsonNode;

public final class ExecutionPlan {
    public record Node(String id,String type,String message,String argumentHash,String prompt) {}
    private ExecutionPlan() {}
    /** Separate v2 entry preserves the legacy sequential parser and its schema boundary. */
    public static List<Node> runtime(JsonNode graph,WorkflowDefinitions definitions){
        if(graph.path("schemaVersion").asInt()==1)return from(graph,definitions);
        if(!definitions.valid(graph))throw new DomainFailure(422,"INVALID_WORKFLOW");
        var nodes=definitions.orderedV2(graph);
        if(nodes.stream().anyMatch(n->!Set.of("manualTrigger","syntheticAction","approval","condition","transform","delay","end").contains(n.path("type").asString())))throw new DomainFailure(422,"UNSUPPORTED_RUNTIME_NODE");
        return nodes.stream().map(n->new Node(n.path("id").asString(),n.path("type").asString(),n.path("config").path("message").asString(""),hash(definitions.stableValue(n)),n.path("config").path("prompt").asString(""))).toList();
    }
    public static List<Node> from(JsonNode graph,WorkflowDefinitions definitions) {
        if(!definitions.valid(graph))throw new DomainFailure(422,"INVALID_WORKFLOW");
        if(graph.path("schemaVersion").asInt()!=1)throw new DomainFailure(422,"UNSUPPORTED_RUNTIME_SCHEMA");
        Map<String,JsonNode> nodes=new HashMap<>();Map<String,String> next=new HashMap<>();String start=null;
        for(var node:graph.path("nodes")) {
            String type=node.path("type").asString();
            if(!Set.of("manualTrigger","syntheticAction","approval","end").contains(type))throw new DomainFailure(422,"UNSUPPORTED_RUNTIME_NODE");
            nodes.put(node.path("id").asString(),node);if(type.equals("manualTrigger"))start=node.path("id").asString();
        }
        for(var edge:graph.path("edges"))next.put(edge.path("source").asString(),edge.path("target").asString());
        List<Node> ordered=new ArrayList<>();String id=start;
        while(id!=null&&ordered.size()<50){var n=nodes.get(id);ordered.add(new Node(id,n.path("type").asString(),n.path("config").path("message").asString(""),hash(id+"\n"+n.path("config").toString()),n.path("config").path("prompt").asString("")));id=next.get(id);}
        if(id!=null||ordered.size()!=nodes.size())throw new DomainFailure(422,"INVALID_WORKFLOW");
        for(int i=0;i<ordered.size();i++)if(ordered.get(i).type().equals("approval")&&(i+1>=ordered.size()||!ordered.get(i+1).type().equals("syntheticAction")))throw new DomainFailure(422,"INVALID_APPROVAL_TARGET");
        return List.copyOf(ordered);
    }
    public static String hash(String text) {
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));}
        catch(java.security.NoSuchAlgorithmException impossible){throw new IllegalStateException("SHA-256 unavailable.");}
    }
}
