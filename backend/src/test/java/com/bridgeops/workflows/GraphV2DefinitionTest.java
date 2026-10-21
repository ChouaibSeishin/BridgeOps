package com.bridgeops.workflows;

import com.bridgeops.workflows.domain.*;
import com.bridgeops.shared.web.DomainFailure;
import java.util.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.*;
import static org.assertj.core.api.Assertions.*;

public class GraphV2DefinitionTest {
    final GraphDefinition graphs = new GraphDefinition();
    public static final String GRAPH = """
        {"schemaVersion":2,"nodes":[
          {"id":"start","type":"manualTrigger","typeVersion":1,"config":{"inputFields":{"count":"INTEGER"}},"inputs":{}},
          {"id":"copy","type":"transform","typeVersion":1,"config":{},"inputs":{"count":{"from":"start","path":"count"}}},
          {"id":"choose","type":"condition","typeVersion":1,"config":{"operator":"GT","compare":0},"inputs":{"value":{"from":"copy","path":"count"}}},
          {"id":"positive","type":"syntheticAction","typeVersion":1,"config":{"operation":"record","message":"Work exists"},"inputs":{}},
          {"id":"empty","type":"syntheticAction","typeVersion":1,"config":{"operation":"record","message":"No work"},"inputs":{}},
          {"id":"finish","type":"end","typeVersion":1,"config":{},"inputs":{}}
        ],"edges":[
          {"source":"start","target":"copy"},{"source":"copy","target":"choose"},
          {"source":"choose","target":"positive","branch":"true"},{"source":"choose","target":"empty","branch":"false"},
          {"source":"positive","target":"finish"},{"source":"empty","target":"finish"}]}
        """;
    ObjectNode graph() { return (ObjectNode) graphs.parse(GRAPH); }
    ObjectNode node(ObjectNode graph, String id) { for (var node : graph.path("nodes")) if (node.path("id").asString().equals(id)) return (ObjectNode) node; throw new AssertionError(); }
    void code(JsonNode graph, String code) { assertThat(graphs.validate(graph).valid()).isFalse(); assertThat(graphs.validate(graph).errors()).extracting(GraphDefinition.Error::code).contains(code); }

    @Test void validatesSingleSelectedPathDagAndStableTopologicalPositions() {
        var root = graph(); assertThat(graphs.validate(root).valid()).isTrue();
        var structure = GraphV2Definition.structure(root);
        assertThat(structure.order()).containsExactly("start", "copy", "choose", "empty", "positive", "finish");
        assertThat(structure.dominators().get("finish")).containsExactlyInAnyOrder("start", "copy", "choose", "finish");
        assertThat(GraphValues.next(root, "choose", true)).isEqualTo("positive");
        assertThat(GraphValues.next(root, "choose", false)).isEqualTo("empty");
        assertThat(GraphValues.next(root, "finish", null)).isNull();
        assertThatThrownBy(() -> com.bridgeops.executions.domain.ExecutionPlan.from(root, new com.bridgeops.workflows.application.WorkflowDefinitions(graphs)))
            .isInstanceOfSatisfying(DomainFailure.class, failure -> assertThat(failure.code()).isEqualTo("UNSUPPORTED_RUNTIME_SCHEMA"));
    }
    @Test void v2CanonicalHashIsIndependentOfPresentationOrderAndSensitiveToBranchMeaning() {
        var a = graph(); var b = graph();
        for (String field : List.of("nodes", "edges")) { var values = new ArrayList<JsonNode>(); b.path(field).forEach(values::add); Collections.reverse(values); var replacement = (ArrayNode) b.withArray(field); replacement.removeAll(); values.forEach(replacement::add); }
        var canonical = graphs.canonical(a); assertThat(canonical).isEqualTo(graphs.canonical(b));
        assertThat(canonical.schemaVersion()).isEqualTo(2); assertThat(canonical.canonicalizationVersion()).isEqualTo(2);
        ((ObjectNode) b.path("edges").get(2)).put("branch", "true"); ((ObjectNode) b.path("edges").get(3)).put("branch", "false");
        assertThat(graphs.canonical(b).hash()).isNotEqualTo(canonical.hash());
        assertThat(graphs.canonical(graphs.parse(GraphDefinitionTest.GRAPH)).schemaVersion()).isEqualTo(1);
    }
    @Test void forbidsFutureSelfAndNonDominatingBranchReferences() {
        for (String source : List.of("positive", "empty", "finish", "missing")) {
            var root = graph(); var end = node(root, "finish"); end.put("type", "transform");
            // Keep the end node in place; the source validation runs even alongside degree errors.
            ((ObjectNode) end.path("inputs")).set("result", graphs.parse("{\"from\":\"" + source + "\",\"path\":\"message\"}"));
            code(root, "SOURCE_NOT_DOMINATING");
        }
        var root = graph(); ((ObjectNode) node(root, "copy").path("inputs")).set("count", graphs.parse("{\"from\":\"copy\",\"path\":\"count\"}")); code(root, "SOURCE_NOT_DOMINATING");
    }
    @Test void rejectsArbitraryPathsScriptsPresentationAndUnregisteredOutput() {
        for (String path : List.of("__proto__", "tickets[0]", "constructor.prototype", "response.token", "count.missing")) {
            var root = graph(); ((ObjectNode) node(root, "copy").path("inputs").path("count")).put("path", path);
            assertThat(graphs.validate(root).valid()).as(path).isFalse();
        }
        var root = graph(); node(root, "copy").put("position", "not executable"); code(root, "SCHEMA_FIELDS");
        root = graph(); ((ObjectNode) node(root, "copy").path("inputs")).set("count", graphs.parse("{\"script\":\"fixture-no-eval\"}")); code(root, "INVALID_MAPPING");
        assertThat(graphs.validate(root).toString()).doesNotContain("fixture-no-eval");
    }
    @Test void branchAndGraphRulesRejectAmbiguousRoutesCyclesAndUnreachableNodes() {
        var root = graph(); ((ObjectNode) root.path("edges").get(3)).put("branch", "true"); code(root, "INVALID_BRANCH");
        root = graph(); ((ObjectNode) root.path("edges").get(4)).put("target", "copy"); code(root, "CYCLE");
        root = graph(); ((ArrayNode) root.path("edges")).remove(0); code(root, "UNREACHABLE");
        root = graph(); ((ObjectNode) root.path("edges").get(0)).put("branch", "true"); code(root, "SCHEMA_FIELDS");
        root = graph(); ((ObjectNode) root.path("edges").get(3)).remove("branch"); code(root, "INVALID_BRANCH");
        root = graph(); ((ArrayNode) root.path("edges")).add(root.path("edges").get(0)); code(root, "DUPLICATE_EDGE");
    }
    @Test void delayIsAnExplicitBoundedV2NodeAndCannotBeSmuggledIntoV1() {
        var root = graph(); var n = node(root, "positive"); n.put("type", "delay"); n.set("config", graphs.parse("{\"seconds\":3600}"));
        assertThat(graphs.validate(root).valid()).isTrue();
        for (String seconds : List.of("-1", "3601", "1.0", "\"10\"")) { n.set("config", graphs.parse("{\"seconds\":" + seconds + "}")); code(root, "DELAY_LIMIT"); }
        code(graphs.parse(GraphDefinitionTest.GRAPH.replace("manualTrigger", "delay")), "UNKNOWN_TYPE");
    }
    @Test void webhookAndManualTriggerHaveExactFlatTypedSchema() {
        var root = graph(); node(root, "start").put("type", "webhookTrigger"); assertThat(graphs.validate(root).valid()).isTrue();
        assertThat(GraphValues.trigger(root, graphs.parse("{\"count\":2}")).path("count").asInt()).isEqualTo(2);
        for (String input : List.of("{}", "{\"count\":\"2\"}", "{\"count\":2.0}", "{\"count\":2,\"extra\":true}", "[]", "{\"count\":18446744073709551617}")) assertThatThrownBy(() -> GraphValues.trigger(root, graphs.parse(input))).isInstanceOf(DomainFailure.class);
        ((ObjectNode) node(root, "start").path("config").path("inputFields")).put("count", "OBJECT"); code(root, "INPUT_SCHEMA");
    }
    @Test void mappingsCopyDataAndInterpolatePrimitivesWithoutEvaluationOrMutation() {
        var root = graph(); var copy = node(root, "copy"); var input = graphs.parse("{\"count\":2}");
        var result = GraphValues.prepare(copy, Map.of("start", input)); assertThat(result).isEqualTo(input); assertThat(result).isNotSameAs(input);
        ((ObjectNode) copy.path("inputs")).set("summary", graphs.parse("{\"parts\":[{\"literal\":\"Count: \"},{\"from\":\"start\",\"path\":\"count\"}]}"));
        assertThat(GraphValues.prepare(copy, Map.of("start", input)).path("summary").asString()).isEqualTo("Count: 2");
        assertThatThrownBy(() -> GraphValues.prepare(copy, Map.of())).isInstanceOf(DomainFailure.class);
        assertThatThrownBy(() -> GraphValues.prepare(copy, Map.of("start", graphs.parse("{\"count\":[]}")))).isInstanceOf(DomainFailure.class);
    }
    @Test void conditionsNeverCoerceTypesAndSupportBoundedEmptyChecks() {
        var config = node(graph(), "choose").path("config");
        assertThat(GraphValues.condition(config, graphs.parse("{\"value\":1}"))).isTrue(); assertThat(GraphValues.condition(config, graphs.parse("{\"value\":0}"))).isFalse();
        for (String value : List.of("\"1\"", "true", "1.0", "null", "[]")) assertThatThrownBy(() -> GraphValues.condition(config, graphs.parse("{\"value\":" + value + "}"))).isInstanceOf(DomainFailure.class);
        for (String empty : List.of("[]", "{}", "\"\"")) assertThat(GraphValues.condition(graphs.parse("{\"operator\":\"EMPTY\"}"), graphs.parse("{\"value\":" + empty + "}"))).isTrue();
        assertThat(GraphValues.condition(graphs.parse("{\"operator\":\"NE\",\"compare\":false}"), graphs.parse("{\"value\":true}"))).isTrue();
        var longValue = graph(); longValue.removeAll(); longValue.put("value", 1L);
        assertThat(GraphValues.condition(graphs.parse("{\"operator\":\"EQ\",\"compare\":1}"), longValue)).isTrue();
    }
    @Test void boundsLiteralMappingsIntermediateDataAndInterpolation() {
        var root = graph(); var inputs = (ObjectNode) node(root, "copy").path("inputs");
        inputs.set("count", graphs.parse("{\"literal\":null}")); code(root, "LITERAL_LIMIT");
        inputs.set("count", graphs.parse("{\"literal\":1.25}")); code(root, "LITERAL_LIMIT");
        inputs.set("count", graphs.parse("{\"literal\":\"" + "x".repeat(2001) + "\"}")); code(root, "LITERAL_LIMIT");
        inputs.set("count", graphs.parse("{\"parts\":[{\"literal\":\"" + "x".repeat(1500) + "\"},{\"literal\":\"" + "y".repeat(1500) + "\"}]}"));
        assertThatThrownBy(() -> GraphValues.prepare(node(root, "copy"), Map.of())).isInstanceOf(DomainFailure.class);
        assertThatThrownBy(() -> GraphValues.bounded(graphs.parse("{\"message\":\"12345\"}"), 5)).isInstanceOf(DomainFailure.class);
    }
    @Test void connectionReferencesDoNotAdmitUrlOrCredentialConfiguration() {
        var root = graph(); var node = node(root, "positive"); node.put("type", "capabilityAction");
        node.set("config", graphs.parse("{\"integrationId\":\"11111111-1111-4111-8111-111111111111\",\"revision\":1,\"operation\":\"teams.list\"}"));
        assertThat(graphs.validate(root).valid()).isTrue();
        ((ObjectNode) node.path("config")).put("credential", "fixture-canary"); code(root, "SCHEMA_FIELDS");
        ((ObjectNode) node.path("config")).remove("credential"); ((ObjectNode) node.path("config")).put("operation", "executeSQL"); code(root, "INVALID_OPERATION");
        ((ObjectNode) node.path("config")).put("operation", "teams.list"); ((ObjectNode) node.path("config")).put("revision", 1.0); code(root, "CONNECTION_REFERENCE");
    }
    @Test void approvalCannotProtectEndOrProvideArgumentsToItsProtectedAction() {
        var root = graph(); var node = node(root, "positive"); node.put("type", "approval"); node.set("config", graphs.parse("{\"prompt\":\"Review exact action\"}")); code(root, "INVALID_APPROVAL_TARGET");
        root = graph(); node = node(root, "copy"); node.put("type", "approval"); node.set("config", graphs.parse("{\"prompt\":\"Review\"}")); node.set("inputs", graphs.parse("{}")); code(root, "UNREGISTERED_OUTPUT");
    }
}
