package com.bridgeops.workflows.domain;

import com.bridgeops.shared.web.DomainFailure;
import java.nio.charset.StandardCharsets;
import java.util.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Explicit copies, primitive interpolation and typed comparisons. No expression evaluator. */
public final class GraphValues {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private GraphValues() {}
    public static JsonNode trigger(JsonNode graph, JsonNode input) {
        if (!GraphV2Definition.validate(graph).valid()) throw new DomainFailure(422, "INVALID_WORKFLOW");
        JsonNode schema = null;
        for (var node : graph.path("nodes")) if (Set.of("manualTrigger", "webhookTrigger").contains(node.path("type").asString())) schema = node.path("config").path("inputFields");
        if (schema == null || input == null || !input.isObject() || !input.propertyNames().equals(schema.propertyNames())) throw failure("INVALID_TRIGGER_INPUT");
        for (String field : schema.propertyNames()) {
            var value = input.get(field); boolean valid = switch (schema.get(field).asString()) {
                case "STRING" -> value.isString() && GraphV2Definition.primitive(value);
                case "INTEGER" -> value.isIntegralNumber() && GraphV2Definition.primitive(value);
                case "BOOLEAN" -> value.isBoolean(); default -> false;
            };
            if (!valid) throw failure("INVALID_TRIGGER_INPUT");
        }
        return bounded(input, 256 * 1024).deepCopy();
    }
    public static JsonNode prepare(JsonNode node, Map<String, JsonNode> outputs) {
        var result = JSON.createObjectNode();
        for (String field : node.path("inputs").propertyNames()) result.set(field, resolve(node.path("inputs").get(field), outputs));
        return bounded(result, 1024 * 1024);
    }
    private static JsonNode resolve(JsonNode binding, Map<String, JsonNode> outputs) {
        if (binding.has("literal")) return binding.get("literal").deepCopy();
        if (binding.has("parts")) {
            var text = new StringBuilder();
            for (var part : binding.path("parts")) {
                var value = resolve(part, outputs); if (!GraphV2Definition.primitive(value)) throw failure("MAPPING_TYPE_MISMATCH");
                text.append(value.isString() ? value.asString() : value.toString());
                if (text.length() > 2000) throw failure("MAPPING_OUTPUT_LIMIT");
            }
            return JSON.getNodeFactory().stringNode(text.toString());
        }
        if (!binding.has("from") || !binding.has("path")) throw failure("INVALID_MAPPING");
        var value = outputs.get(binding.path("from").asString());
        if(binding.path("path").asString().equals("$")){
            if(value==null||!value.isObject())throw failure("MISSING_STEP_OUTPUT");
            return value.deepCopy();
        }
        for (String segment : binding.path("path").asString().split("\\.")) {
            if (value == null || !value.isObject() || !value.has(segment)) throw failure("MISSING_STEP_OUTPUT");
            value = value.get(segment);
        }
        if (value == null || value.isNull()) throw failure("MISSING_STEP_OUTPUT");
        return value.deepCopy();
    }
    public static boolean condition(JsonNode config, JsonNode inputs) {
        var value = inputs.get("value"); var comparison = config.get("compare"); String operator = config.path("operator").asString();
        if (value == null) throw failure("MISSING_STEP_OUTPUT");
        if (Set.of("EMPTY", "NOT_EMPTY").contains(operator)) {
            if (!value.isContainer() && !value.isString()) throw failure("CONDITION_TYPE_MISMATCH");
            boolean empty = value.isString() ? value.asString().isEmpty() : value.isEmpty();
            return operator.equals("EMPTY") ? empty : !empty;
        }
        if (!GraphV2Definition.primitive(value) || !GraphV2Definition.primitive(comparison)
            || value.isString() != comparison.isString() || value.isBoolean() != comparison.isBoolean()
            || value.isIntegralNumber() != comparison.isIntegralNumber()) throw failure("CONDITION_TYPE_MISMATCH");
        boolean equal = value.isIntegralNumber() ? value.bigIntegerValue().equals(comparison.bigIntegerValue()) : value.equals(comparison);
        if (operator.equals("EQ")) return equal;
        if (operator.equals("NE")) return !equal;
        if (!value.isIntegralNumber()) throw failure("CONDITION_TYPE_MISMATCH");
        int order = value.bigIntegerValue().compareTo(comparison.bigIntegerValue());
        return switch (operator) { case "GT" -> order > 0; case "GTE" -> order >= 0; case "LT" -> order < 0; case "LTE" -> order <= 0; default -> throw failure("INVALID_COMPARISON"); };
    }
    public static String next(JsonNode graph, String current, Boolean selectedBranch) {
        String target = null;
        for (var edge : graph.path("edges")) if (edge.path("source").asString().equals(current)) {
            if (selectedBranch != null && !edge.path("branch").asString("").equals(selectedBranch.toString())) continue;
            if (target != null) throw failure("INVALID_BRANCH"); target = edge.path("target").asString();
        }
        return target;
    }
    public static JsonNode bounded(JsonNode value, int maximumBytes) {
        if (value == null || JSON.writeValueAsBytes(value).length > maximumBytes) throw failure("MAPPING_OUTPUT_LIMIT");
        record Item(JsonNode value, int depth) {} var todo = new ArrayDeque<Item>(); todo.add(new Item(value, 0)); int count = 0;
        while (!todo.isEmpty()) {
            var item = todo.remove(); if (++count > 16384 || item.depth() > 16) throw failure("MAPPING_OUTPUT_LIMIT");
            if (item.value().isContainer()) for (var child : item.value()) todo.add(new Item(child, item.depth() + 1));
            else if (item.value().isString() && item.value().asString().getBytes(StandardCharsets.UTF_8).length > maximumBytes) throw failure("MAPPING_OUTPUT_LIMIT");
        }
        return value;
    }
    private static DomainFailure failure(String code) { return new DomainFailure(422, code); }
}
