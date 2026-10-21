package com.bridgeops.executions.application;

import com.bridgeops.executions.domain.ExecutionPlan;
import com.bridgeops.executions.persistence.ExecutionStore;
import com.bridgeops.executions.persistence.ExecutionStore.Execution;
import com.bridgeops.shared.web.DomainFailure;
import com.bridgeops.workflows.application.WorkflowDefinitions;
import java.time.*;
import java.util.*;
import tools.jackson.databind.JsonNode;
import static com.bridgeops.executions.persistence.ExecutionStore.time;

/** Package-private v2 persistence helper. Runtime holds tenant, execution and current fencing locks. */
final class ExecutionGraphData {
    private final ExecutionStore store; private final WorkflowDefinitions graphs; private final Clock clock;
    ExecutionGraphData(ExecutionStore store,WorkflowDefinitions graphs,Clock clock){this.store=store;this.graphs=graphs;this.clock=clock;}
    boolean v2(JsonNode graph){return graph.path("schemaVersion").asInt()==2;}
    JsonNode node(JsonNode graph,String id){for(var node:graph.path("nodes"))if(node.path("id").asString().equals(id))return node;throw new DomainFailure(409,"RECONCILIATION_REQUIRED");}
    JsonNode inputs(Execution e,JsonNode graph,String id){return inputs(e,graph,id,null);}
    JsonNode inputs(Execution e,JsonNode graph,String id,String previousOverride){
        var saved=store.jdbc().queryForList("SELECT arguments_json,argument_hash FROM execution_step_inputs WHERE organization_id=? AND execution_id=? AND node_id=?",e.organization(),e.id(),id);
        if(!saved.isEmpty()){
            String text=(String)saved.getFirst().get("arguments_json");if(!ExecutionPlan.hash(text).equals(saved.getFirst().get("argument_hash")))throw new DomainFailure(409,"RECONCILIATION_REQUIRED");
            return graphs.parse(text);
        }
        var outputs=new HashMap<String,JsonNode>();for(var step:store.steps(e.organization(),e.id()))if(step.state().equals("SUCCEEDED")&&step.output()!=null)outputs.put(step.node(),graphs.parse(step.output()));
        String text=graphs.stableValue(graphs.inputsV2(node(graph,id),outputs));String previous=previousOverride==null?previous(e,id):previousOverride;
        store.jdbc().update("INSERT INTO execution_step_inputs(organization_id,execution_id,node_id,previous_node_id,arguments_json,argument_hash,prepared_at) VALUES(?,?,?,?,?,?,?)",e.organization(),e.id(),id,previous,text,ExecutionPlan.hash(text),time(clock.instant()));
        return graphs.parse(text);
    }
    String previous(Execution e,String id){var parents=store.jdbc().queryForList("SELECT node_id FROM execution_step_routes WHERE organization_id=? AND execution_id=? AND next_node_id=?",String.class,e.organization(),e.id(),id);return parents.isEmpty()?null:parents.getFirst();}
    ExecutionPlan.Node gate(Execution e,JsonNode graph,List<ExecutionPlan.Node> plan,int position){
        var current=plan.get(position);if(current.type().equals("approval"))return current;
        if(!v2(graph))return position>0&&plan.get(position-1).type().equals("approval")?plan.get(position-1):current;
        String parent=previous(e,current.id());return plan.stream().filter(n->n.id().equals(parent)&&n.type().equals("approval")).findFirst().orElse(current);
    }
    ExecutionPlan.Node action(JsonNode graph,List<ExecutionPlan.Node> plan,int position){
        var current=plan.get(position);if(!current.type().equals("approval"))return current;
        if(!v2(graph))return plan.get(position+1);
        String next=graphs.nextV2(graph,current.id(),null);return plan.stream().filter(n->n.id().equals(next)).findFirst().orElseThrow(()->new DomainFailure(409,"RECONCILIATION_REQUIRED"));
    }
    Instant delay(Execution e,JsonNode graph,String id){
        var saved=store.jdbc().queryForList("SELECT due_at FROM execution_step_delays WHERE organization_id=? AND execution_id=? AND node_id=?",java.sql.Timestamp.class,e.organization(),e.id(),id);
        if(!saved.isEmpty())return saved.getFirst().toInstant();
        Instant now=clock.instant(),due=now.plusSeconds(node(graph,id).path("config").path("seconds").asLong());
        store.jdbc().update("INSERT INTO execution_step_delays(organization_id,execution_id,node_id,started_at,due_at) VALUES(?,?,?,?,?)",e.organization(),e.id(),id,time(now),time(due));return due;
    }
    String output(Execution e,JsonNode graph,ExecutionStore.Step step){
        var inputs=inputs(e,graph,step.node());var node=node(graph,step.node());
        return switch(step.type()){
            case "manualTrigger" -> graphs.stableValue(graphs.triggerV2(graph,graphs.parse(e.input())));
            case "transform" -> graphs.stableValue(inputs);
            case "condition" -> "{\"matched\":"+graphs.conditionV2(node.path("config"),inputs)+"}";
            case "delay" -> "{\"resumedAt\":\""+clock.instant()+"\"}";
            case "end" -> "{}";
            default -> throw new DomainFailure(422,"UNSUPPORTED_RUNTIME_NODE");
        };
    }
    void advance(Execution e,JsonNode graph,List<ExecutionPlan.Node> plan){
        var current=plan.get(e.position());Boolean branch=null;
        if(current.type().equals("condition")){var output=store.steps(e.organization(),e.id()).get(e.position()).output();var result=output==null?null:graphs.parse(output).get("matched");if(result==null||!result.isBoolean())throw new DomainFailure(409,"RECONCILIATION_REQUIRED");branch=result.asBoolean();}
        String next=graphs.nextV2(graph,current.id(),branch);int position=next==null?plan.size():java.util.stream.IntStream.range(0,plan.size()).filter(i->plan.get(i).id().equals(next)).findFirst().orElseThrow(()->new DomainFailure(409,"RECONCILIATION_REQUIRED"));
        store.jdbc().update("INSERT INTO execution_step_routes(organization_id,execution_id,node_id,next_node_id,selected_branch,committed_at) VALUES(?,?,?,?,?,?)",e.organization(),e.id(),current.id(),next,branch,time(clock.instant()));
        if(branch!=null){
            String other=graphs.nextV2(graph,current.id(),!branch);var skipped=reachable(graph,other);skipped.removeAll(reachable(graph,next));
            for(var step:store.steps(e.organization(),e.id()))if(skipped.contains(step.node())){
                if(!Set.of("PENDING","SKIPPED").contains(step.state()))throw new DomainFailure(409,"RECONCILIATION_REQUIRED");
                skip(e,step.node(),clock.instant());
            }
        }
        if(store.jdbc().update("UPDATE workflow_executions SET current_position=?,revision=revision+1 WHERE organization_id=? AND id=? AND revision=?",position,e.organization(),e.id(),e.revision())!=1)throw new DomainFailure(409,"STALE_REVISION");
    }
    void skipped(Execution e,Instant now){
        if(!Boolean.TRUE.equals(store.jdbc().queryForObject("SELECT EXISTS(SELECT FROM execution_step_inputs WHERE organization_id=? AND execution_id=?)",Boolean.class,e.organization(),e.id())))return;
        for(var step:store.steps(e.organization(),e.id()))if(step.state().equals("PENDING")){
            skip(e,step.node(),now);
        }
    }
    private Set<String> reachable(JsonNode graph,String first){var reached=new HashSet<String>();var queue=new ArrayDeque<String>();if(first!=null)queue.add(first);while(!queue.isEmpty()){String id=queue.remove();if(!reached.add(id))continue;for(var edge:graph.path("edges"))if(edge.path("source").asString().equals(id))queue.add(edge.path("target").asString());}return reached;}
    private void skip(Execution e,String node,Instant now){if(store.jdbc().update("UPDATE execution_steps SET status='SKIPPED',completed_at=?,error_code='BRANCH_NOT_SELECTED' WHERE organization_id=? AND execution_id=? AND node_id=? AND status='PENDING'",time(now),e.organization(),e.id(),node)==1)store.event(e,"BRANCH_SKIPPED",node,now);}
}
