package com.bridgeops.workflows.application;

import com.bridgeops.workflows.domain.GraphDefinition;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

/** Public schema boundary; consumers do not import workflow domain or persistence internals. */
@Service
public class WorkflowDefinitions {
    private final GraphDefinition definitions;
    public WorkflowDefinitions(GraphDefinition definitions){this.definitions=definitions;}
    public boolean valid(JsonNode graph){return definitions.validate(graph).valid();}
    public JsonNode parse(String json){return definitions.parse(json);}
}
