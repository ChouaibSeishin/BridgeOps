package com.bridgeops.workflows;

import com.bridgeops.workflows.domain.GraphDefinition;
import com.bridgeops.shared.web.DomainFailure;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.*;
import static org.assertj.core.api.Assertions.*;

class GraphDefinitionTest {
    final GraphDefinition graphs = new GraphDefinition();
    static final String GRAPH = """
        {"schemaVersion":1,"nodes":[
          {"id":"start","type":"manualTrigger","typeVersion":1,"config":{}},
          {"id":"prepare","type":"syntheticAction","typeVersion":1,"config":{"operation":"record","message":"Prepare review"}},
          {"id":"review","type":"approval","typeVersion":1,"config":{"prompt":"Review proposed changes"}},
          {"id":"record","type":"syntheticAction","typeVersion":1,"config":{"operation":"record","message":"Record approved intent"}},
          {"id":"finish","type":"end","typeVersion":1,"config":{}}
        ],"edges":[{"source":"start","target":"prepare"},{"source":"prepare","target":"review"},{"source":"review","target":"record"},{"source":"record","target":"finish"}]}
        """;
    @Test void validFirstBusinessDefinitionDoesNotRequireRuntimeOrConnectorData() {
        assertThat(graphs.validate(graphs.parse(GRAPH)).valid()).isTrue();
    }
    static Stream<Arguments> invalid() {
        return Stream.of(
            Arguments.of(GRAPH.replace("\"schemaVersion\":1", "\"schemaVersion\":2"), "UNSUPPORTED_SCHEMA"),
            Arguments.of(GRAPH.replace("\"schemaVersion\":1", "\"schemaVersion\":1.0"), "UNSUPPORTED_SCHEMA"),
            Arguments.of(GRAPH.replace("\"schemaVersion\":1", "\"schemaVersion\":18446744073709551617"), "UNSUPPORTED_SCHEMA"),
            Arguments.of(GRAPH.replace("manualTrigger", "httpRequest"), "UNKNOWN_TYPE"),
            Arguments.of(GRAPH.replace("\"typeVersion\":1", "\"typeVersion\":99"), "UNSUPPORTED_TYPE_VERSION"),
            Arguments.of(GRAPH.replace("\"id\":\"prepare\"", "\"id\":\"start\""), "DUPLICATE_NODE_ID"),
            Arguments.of(GRAPH.replace("\"id\":\"prepare\"", "\"id\":\"../secret\""), "INVALID_NODE_ID"),
            Arguments.of(GRAPH.replace("\"target\":\"prepare\"", "\"target\":\"missing\""), "MISSING_NODE"),
            Arguments.of(GRAPH.replace("\"target\":\"finish\"", "\"target\":\"prepare\""), "CYCLE"),
            Arguments.of(GRAPH.replace("\"type\":\"manualTrigger\"", "\"type\":\"end\""), "TRIGGER_COUNT"),
            Arguments.of(GRAPH.replace("\"type\":\"end\"", "\"type\":\"manualTrigger\""), "END_COUNT"),
            Arguments.of(GRAPH.replace("\"operation\":\"record\"", "\"operation\":\"executeSQL\""), "INVALID_OPERATION"),
            Arguments.of(GRAPH.replace("\"message\":\"Prepare review\"", "\"message\":42"), "INVALID_TEXT"),
            Arguments.of(GRAPH.replace("\"prompt\":\"Review proposed changes\"", "\"prompt\":\"\""), "INVALID_TEXT"),
            Arguments.of(GRAPH.replace("\"prompt\":\"Review proposed changes\"", "\"policy\":\"allow\""), "UNKNOWN_FIELD"),
            Arguments.of(GRAPH.replace("\"config\":{}", "\"config\":{\"script\":\"fixture-canary\"}"), "UNKNOWN_FIELD"),
            Arguments.of(GRAPH.replace("\"config\":{}", "\"config\":[]"), "CONFIG_OBJECT"),
            Arguments.of(GRAPH.replace("\"id\":\"start\"", "\"position\":{},\"id\":\"start\""), "UNKNOWN_FIELD"),
            Arguments.of(GRAPH.replace("{\"source\":\"start\",\"target\":\"prepare\"}", "{\"source\":\"start\",\"target\":\"prepare\"},{\"source\":\"start\",\"target\":\"prepare\"}"), "DUPLICATE_EDGE"),
            Arguments.of(GRAPH.replace("{\"source\":\"review\",\"target\":\"record\"},", ""), "UNREACHABLE"),
            Arguments.of(GRAPH.replace("{\"source\":\"record\",\"target\":\"finish\"}", "{\"source\":\"review\",\"target\":\"finish\"}"), "INVALID_CONNECTION"),
            Arguments.of("{\"schemaVersion\":1,\"nodes\":[],\"edges\":[]}", "NODE_LIMIT")
        );
    }
    @ParameterizedTest @MethodSource("invalid") void rejectsAmbiguousOrUnsafeDefinitions(String text, String code) {
        var definition = graphs.parse(text); var result = graphs.validate(definition);
        assertThat(result.valid()).isFalse(); assertThat(result.errors()).extracting(GraphDefinition.Error::code).contains(code);
        assertThat(result).isEqualTo(graphs.validate(definition));
        assertThat(result.toString()).doesNotContain("fixture-canary", "../secret", "executeSQL");
        assertThatThrownBy(() -> graphs.canonical(definition)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void rejectsGraphCountLimitsAndUnknownRootFields() {
        var graph = (ObjectNode) graphs.parse(GRAPH); var nodes = (ArrayNode) graph.get("nodes");
        for (int i = 0; i < 50; i++) nodes.add(nodes.get(0));
        assertThat(graphs.validate(graph).errors()).extracting(GraphDefinition.Error::code).contains("NODE_LIMIT");
        final var tooManyNodes = graph;
        assertThatThrownBy(() -> graphs.bounded(tooManyNodes)).isInstanceOf(DomainFailure.class);
        graph = (ObjectNode) graphs.parse(GRAPH); var edges = (ArrayNode) graph.get("edges");
        for (int i = 0; i < 100; i++) edges.add(edges.get(0));
        assertThat(graphs.validate(graph).errors()).extracting(GraphDefinition.Error::code).contains("EDGE_LIMIT");
        final var tooManyEdges = graph;
        assertThatThrownBy(() -> graphs.bounded(tooManyEdges)).isInstanceOf(DomainFailure.class);
        graph = (ObjectNode) graphs.parse(GRAPH); graph.put("organizationId", "not-authority");
        assertThat(graphs.validate(graph).errors()).extracting(GraphDefinition.Error::code).contains("UNKNOWN_FIELD");
    }
    @Test void validatesEveryRegisteredConfigurationAndBoundedText() {
        for (String field : List.of("message", "prompt")) for (String value : List.of(" ", "x".repeat(501), "bad\ntext")) {
            var graph = (ObjectNode) graphs.parse(GRAPH); var node = (ObjectNode) graph.path("nodes").get(field.equals("message") ? 1 : 2);
            ((ObjectNode) node.path("config")).put(field, value);
            assertThat(graphs.validate(graph).errors()).extracting(GraphDefinition.Error::code).contains("INVALID_TEXT");
        }
    }
    @Test void canonicalBytesAndHashAreIndependentOfObjectNodeAndEdgeOrder() throws Exception {
        var first = graphs.parse(GRAPH); var second = (ObjectNode) graphs.parse(GRAPH);
        for (String key : List.of("nodes", "edges")) {
            var values = new ArrayList<JsonNode>(); second.path(key).forEach(values::add); Collections.reverse(values);
            var replacement = new ArrayNode(tools.jackson.databind.node.JsonNodeFactory.instance); values.forEach(replacement::add); second.set(key, replacement);
        }
        var snapshot = graphs.canonical(first);
        assertThat(snapshot).isEqualTo(graphs.canonical(second));
        assertThat(snapshot.hash()).isEqualTo(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(snapshot.json().getBytes(StandardCharsets.UTF_8))));
        assertThat(snapshot.json()).startsWith("{\"edges\":[{\"source\":\"prepare\",\"target\":\"review\"}");
        assertThat(snapshot.canonicalizationVersion()).isEqualTo(1);
        ((ObjectNode) second.path("nodes").get(3).path("config")).put("message", "Changed meaning");
        assertThat(graphs.canonical(second).hash()).isNotEqualTo(snapshot.hash());
    }
    @Test void boundedDraftStorageRetainsSemanticErrorsButRejectsExcessiveJson() {
        var invalid = graphs.parse(GRAPH.replace("manualTrigger", "unregistered"));
        assertThat(graphs.parse(graphs.bounded(invalid))).isEqualTo(invalid);
        assertThatThrownBy(() -> graphs.bounded(graphs.parse("[]"))).isInstanceOf(DomainFailure.class);
        assertThatThrownBy(() -> graphs.bounded(graphs.parse("{\"x\":\"" + "a".repeat(2001) + "\"}"))).isInstanceOf(DomainFailure.class);
        assertThatThrownBy(() -> graphs.bounded(graphs.parse("{\"x\":".repeat(20) + "1" + "}".repeat(20)))).isInstanceOf(DomainFailure.class);
        var graph = (ObjectNode) graphs.parse("{}"); for (int i = 0; i < 40; i++) graph.put("x" + i, "字".repeat(2000));
        assertThatThrownBy(() -> graphs.bounded(graph)).isInstanceOfSatisfying(DomainFailure.class, error -> assertThat(error.status()).isEqualTo(413));
    }
    @Test void parserRejectsDuplicateKeysAndExcessiveDepthBeforeBinding() {
        assertThatThrownBy(() -> graphs.checkRequest("{\"x\":1,\"x\":2}".getBytes(StandardCharsets.UTF_8))).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> graphs.checkRequest(("[".repeat(40) + "0" + "]".repeat(40)).getBytes(StandardCharsets.UTF_8))).isInstanceOf(RuntimeException.class);
    }
}
