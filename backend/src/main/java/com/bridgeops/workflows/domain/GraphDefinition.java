package com.bridgeops.workflows.domain;

import com.bridgeops.shared.web.DomainFailure;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import org.springframework.stereotype.Component;
import tools.jackson.core.StreamReadConstraints;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Definition schemas only. Nothing in this registry dispatches an action or approval. */
@Component
public class GraphDefinition {
    public static final int MAX_BYTES = 128 * 1024;
    public static final Set<String> TYPES = Set.of("manualTrigger", "syntheticAction", "approval", "end");
    private final JsonMapper json = JsonMapper.builder(JsonFactory.builder()
        .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(32).maxStringLength(4096).maxNumberLength(20).build())
        .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build()).build();

    public record Error(String nodeId, String path, String code, String message) {}
    public record Validation(boolean valid, List<Error> errors) {}
    public record Canonical(String json, String hash, int schemaVersion, int canonicalizationVersion) {}

    public JsonNode parse(String text) { return json.readTree(text); }
    public JsonNode checkRequest(byte[] bytes) { return json.readTree(bytes); }

    /** Saves may retain semantic errors, but never unbounded JSON or presentation/authority metadata. */
    public String bounded(JsonNode definition) {
        if (definition == null || !definition.isObject()) throw new DomainFailure(400, "INVALID_DEFINITION");
        if ((definition.path("nodes").isArray() && definition.path("nodes").size() > 50)
            || (definition.path("edges").isArray() && definition.path("edges").size() > 100))
            throw new DomainFailure(413, "DEFINITION_TOO_LARGE");
        record Entry(JsonNode node, int depth) {}
        var work = new ArrayDeque<Entry>(); work.add(new Entry(definition, 0)); int count = 0;
        while (!work.isEmpty()) {
            var entry = work.remove(); var node = entry.node();
            if (++count > 4096 || entry.depth() > 16 || (node.isString() && node.asString().length() > 2000))
                throw new DomainFailure(400, "DEFINITION_LIMIT");
            if (node.isContainer()) for (var child : node) work.add(new Entry(child, entry.depth() + 1));
        }
        String text = json.writeValueAsString(definition);
        if (text.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) throw new DomainFailure(413, "DEFINITION_TOO_LARGE");
        return text;
    }

    public Validation validate(JsonNode root) {
        var errors = new ArrayList<Error>();
        if (root == null || !root.isObject()) return new Validation(false, List.of(new Error(null, "$", "OBJECT_REQUIRED", "Use a graph object.")));
        fields(root, Set.of("schemaVersion", "nodes", "edges"), "$", null, errors);
        if (!integer(root.get("schemaVersion"), 1)) add(errors, null, "$.schemaVersion", "UNSUPPORTED_SCHEMA", "Use graph schema version 1.");
        var nodes = root.path("nodes"); var edges = root.path("edges");
        if (!nodes.isArray() || nodes.isEmpty() || nodes.size() > 50) add(errors, null, "$.nodes", "NODE_LIMIT", "Provide between 1 and 50 nodes.");
        if (!edges.isArray() || edges.size() > 100) add(errors, null, "$.edges", "EDGE_LIMIT", "Provide at most 100 edges.");
        if (!nodes.isArray() || !edges.isArray() || nodes.size() > 50 || edges.size() > 100) return result(errors);
        var byId = new TreeMap<String, JsonNode>(); int index = 0;
        for (var node : nodes) {
            String path = "$.nodes[" + index++ + "]";
            if (!node.isObject()) { add(errors, null, path, "NODE_OBJECT", "Use a node object."); continue; }
            String id = node.path("id").isString() ? node.path("id").asString() : "";
            boolean validId = id.matches("[A-Za-z][A-Za-z0-9_-]{0,63}"); String safeId = validId ? id : null;
            fields(node, Set.of("id", "type", "typeVersion", "config"), path, safeId, errors);
            if (!validId) add(errors, null, path + ".id", "INVALID_NODE_ID", "Use a letter followed by up to 63 letters, digits, underscores or hyphens.");
            else if (byId.putIfAbsent(id, node) != null) add(errors, id, path + ".id", "DUPLICATE_NODE_ID", "Each node needs a unique ID.");
            String type = node.path("type").isString() ? node.path("type").asString() : "";
            if (!TYPES.contains(type)) add(errors, safeId, path + ".type", "UNKNOWN_TYPE", "Choose a registered definition type.");
            if (!integer(node.get("typeVersion"), 1)) add(errors, safeId, path + ".typeVersion", "UNSUPPORTED_TYPE_VERSION", "Use node type version 1.");
            var config = node.path("config");
            if (!config.isObject()) { add(errors, safeId, path + ".config", "CONFIG_OBJECT", "Provide a configuration object."); continue; }
            switch (type) {
                case "syntheticAction" -> {
                    fields(config, Set.of("operation", "message"), path + ".config", safeId, errors);
                    if (!config.path("operation").isString() || !config.path("operation").asString().equals("record"))
                        add(errors, safeId, path + ".config.operation", "INVALID_OPERATION", "Choose the record definition operation.");
                    text(config.get("message"), safeId, path + ".config.message", errors);
                }
                case "approval" -> { fields(config, Set.of("prompt"), path + ".config", safeId, errors); text(config.get("prompt"), safeId, path + ".config.prompt", errors); }
                case "manualTrigger", "end" -> fields(config, Set.of(), path + ".config", safeId, errors);
                default -> { /* Unknown type already rejected. */ }
            }
        }
        var outgoing = new TreeMap<String, List<String>>(); var incoming = new TreeMap<String, Integer>();
        byId.keySet().forEach(id -> { outgoing.put(id, new ArrayList<>()); incoming.put(id, 0); });
        var unique = new HashSet<String>(); index = 0;
        for (var edge : edges) {
            String path = "$.edges[" + index++ + "]";
            if (!edge.isObject()) { add(errors, null, path, "EDGE_OBJECT", "Use an edge object."); continue; }
            fields(edge, Set.of("source", "target"), path, null, errors);
            String source = edge.path("source").isString() ? edge.path("source").asString() : "";
            String target = edge.path("target").isString() ? edge.path("target").asString() : "";
            if (!byId.containsKey(source) || !byId.containsKey(target)) { add(errors, null, path, "MISSING_NODE", "Connect existing source and target nodes."); continue; }
            if (!unique.add(source + ":" + target)) { add(errors, null, path, "DUPLICATE_EDGE", "Keep only one copy of each connection."); continue; }
            outgoing.get(source).add(target); incoming.merge(target, 1, Integer::sum);
        }
        var triggers = byId.entrySet().stream().filter(e -> e.getValue().path("type").asString("").equals("manualTrigger")).map(Map.Entry::getKey).toList();
        var ends = byId.entrySet().stream().filter(e -> e.getValue().path("type").asString("").equals("end")).map(Map.Entry::getKey).toList();
        if (triggers.size() != 1) add(errors, null, "$.nodes", "TRIGGER_COUNT", "Provide exactly one manual trigger.");
        if (ends.size() != 1) add(errors, null, "$.nodes", "END_COUNT", "Provide exactly one end node.");
        for (var id : byId.keySet()) {
            String type = byId.get(id).path("type").asString("");
            if (incoming.get(id) != (type.equals("manualTrigger") ? 0 : 1) || outgoing.get(id).size() != (type.equals("end") ? 0 : 1))
                add(errors, id, "$.edges", "INVALID_CONNECTION", "Use a single path: trigger has one exit, end one entry, other nodes one of each.");
        }
        var degrees = new TreeMap<>(incoming); var queue = new ArrayDeque<String>(); degrees.forEach((id, n) -> { if (n == 0) queue.add(id); }); int visited = 0;
        while (!queue.isEmpty()) { String id = queue.remove(); visited++; for (var next : outgoing.get(id)) if (degrees.merge(next, -1, Integer::sum) == 0) queue.add(next); }
        if (visited != byId.size()) add(errors, null, "$.edges", "CYCLE", "Remove the cycle; loops are unsupported.");
        if (triggers.size() == 1) {
            var reached = new HashSet<String>(); queue.add(triggers.getFirst());
            while (!queue.isEmpty()) { String id = queue.remove(); if (reached.add(id)) queue.addAll(outgoing.get(id)); }
            byId.keySet().stream().filter(id -> !reached.contains(id)).forEach(id -> add(errors, id, "$.edges", "UNREACHABLE", "Connect this node to the trigger path."));
            if (ends.stream().noneMatch(reached::contains)) add(errors, null, "$.edges", "NO_END_PATH", "Connect the trigger to an end.");
        }
        return result(errors);
    }

    public Canonical canonical(JsonNode definition) {
        bounded(definition);
        if (!validate(definition).valid()) throw new IllegalArgumentException("A valid graph is required.");
        var root = json.createObjectNode(); root.put("schemaVersion", 1);
        var nodes = json.createArrayNode(); var sortedNodes = new ArrayList<JsonNode>(); definition.path("nodes").forEach(sortedNodes::add);
        sortedNodes.sort(Comparator.comparing(n -> n.path("id").asString())); sortedNodes.forEach(n -> nodes.add(sorted(n))); root.set("nodes", nodes);
        var edges = json.createArrayNode(); var sortedEdges = new ArrayList<JsonNode>(); definition.path("edges").forEach(sortedEdges::add);
        sortedEdges.sort(Comparator.comparing((JsonNode n) -> n.path("source").asString()).thenComparing(n -> n.path("target").asString())); sortedEdges.forEach(n -> edges.add(sorted(n))); root.set("edges", edges);
        String text = json.writeValueAsString(sorted(root));
        try { return new Canonical(text, HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8))), 1, 1); }
        catch (java.security.NoSuchAlgorithmException unavailable) { throw new IllegalStateException("SHA-256 unavailable."); }
    }
    private JsonNode sorted(JsonNode node) {
        if (node.isObject()) { var result = json.createObjectNode(); node.propertyNames().stream().sorted().forEach(key -> result.set(key, sorted(node.get(key)))); return result; }
        if (node.isArray()) { var result = json.createArrayNode(); node.forEach(child -> result.add(sorted(child))); return result; }
        return node;
    }
    private static boolean integer(JsonNode node, int value) { return node != null && node.isIntegralNumber() && node.bigIntegerValue().equals(java.math.BigInteger.valueOf(value)); }
    private static void text(JsonNode node, String id, String path, List<Error> errors) {
        if (node == null || !node.isString() || node.asString().isBlank() || node.asString().length() > 500 || node.asString().codePoints().anyMatch(Character::isISOControl))
            add(errors, id, path, "INVALID_TEXT", "Provide 1–500 characters without control characters.");
    }
    private static void fields(JsonNode node, Set<String> allowed, String path, String id, List<Error> errors) {
        if (!allowed.containsAll(node.propertyNames())) add(errors, id, path, "UNKNOWN_FIELD", "Remove fields outside this schema; authority, secrets and presentation are not configuration.");
    }
    private static void add(List<Error> errors, String id, String path, String code, String message) { if (errors.size() < 100) errors.add(new Error(id, path, code, message)); }
    private static Validation result(List<Error> errors) { errors.sort(Comparator.comparing(Error::path).thenComparing(Error::code).thenComparing(Error::nodeId, Comparator.nullsFirst(Comparator.naturalOrder()))); return new Validation(errors.isEmpty(), List.copyOf(errors)); }
}
