package com.bridgeops.executions.domain;

import com.bridgeops.shared.web.DomainFailure;
import com.bridgeops.workflows.application.WorkflowDefinitions;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import tools.jackson.databind.JsonNode;

public final class ExecutionPlan {
    public record Node(String id,String type,String message,String argumentHash) {}
    private ExecutionPlan() {}
    public static List<Node> from(JsonNode graph,WorkflowDefinitions definitions) {
        if(!definitions.valid(graph))throw new DomainFailure(422,"INVALID_WORKFLOW");
        Map<String,JsonNode> nodes=new HashMap<>();Map<String,String> next=new HashMap<>();String start=null;
        for(var node:graph.path("nodes")) {
            String type=node.path("type").asString();
            if(!Set.of("manualTrigger","syntheticAction","end").contains(type))throw new DomainFailure(422,"UNSUPPORTED_RUNTIME_NODE");
            nodes.put(node.path("id").asString(),node);if(type.equals("manualTrigger"))start=node.path("id").asString();
        }
        for(var edge:graph.path("edges"))next.put(edge.path("source").asString(),edge.path("target").asString());
        List<Node> ordered=new ArrayList<>();String id=start;
        while(id!=null&&ordered.size()<50){var n=nodes.get(id);ordered.add(new Node(id,n.path("type").asString(),n.path("config").path("message").asString(""),hash(id+"\n"+n.path("config").toString())));id=next.get(id);}
        if(id!=null||ordered.size()!=nodes.size())throw new DomainFailure(422,"INVALID_WORKFLOW");
        return List.copyOf(ordered);
    }
    public static String hash(String text) {
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));}
        catch(java.security.NoSuchAlgorithmException impossible){throw new IllegalStateException("SHA-256 unavailable.");}
    }
}
