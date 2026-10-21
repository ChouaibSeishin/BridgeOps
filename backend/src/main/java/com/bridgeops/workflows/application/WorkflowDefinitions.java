package com.bridgeops.workflows.application;

import com.bridgeops.workflows.domain.GraphDefinition;
import com.bridgeops.workflows.domain.GraphV2Definition;
import com.bridgeops.workflows.domain.GraphValues;
import java.util.*;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

/** Public schema boundary; consumers do not import workflow domain or persistence internals. */
@Service
public class WorkflowDefinitions {
    private final GraphDefinition definitions;
    public WorkflowDefinitions(GraphDefinition definitions){this.definitions=definitions;}
    public boolean valid(JsonNode graph){return definitions.validate(graph).valid();}
    public JsonNode parse(String json){return definitions.parse(json);}
    public List<JsonNode> orderedV2(JsonNode graph){var structure=GraphV2Definition.structure(graph);return structure.order().stream().map(structure.nodes()::get).toList();}
    public JsonNode triggerV2(JsonNode graph,JsonNode input){return GraphValues.trigger(graph,input);}
    public JsonNode inputsV2(JsonNode node,Map<String,JsonNode> outputs){return GraphValues.prepare(node,outputs);}
    public boolean conditionV2(JsonNode configuration,JsonNode inputs){return GraphValues.condition(configuration,inputs);}
    public String nextV2(JsonNode graph,String node,Boolean branch){return GraphValues.next(graph,node,branch);}
    public String stableValue(JsonNode value){return sorted(GraphValues.bounded(value,1024*1024)).toString();}
    private JsonNode sorted(JsonNode value){
        if(value.isObject()){var result=tools.jackson.databind.node.JsonNodeFactory.instance.objectNode();value.propertyNames().stream().sorted().forEach(k->result.set(k,sorted(value.get(k))));return result;}
        if(value.isArray()){var result=tools.jackson.databind.node.JsonNodeFactory.instance.arrayNode();value.forEach(v->result.add(sorted(v)));return result;}
        return value;
    }
}
