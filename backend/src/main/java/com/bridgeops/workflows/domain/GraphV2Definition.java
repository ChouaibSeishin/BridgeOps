package com.bridgeops.workflows.domain;

import java.math.BigInteger;
import java.util.*;
import tools.jackson.databind.JsonNode;

/** Bounded DAG schema only. Connection authority and registered provider fields are checked at dispatch. */
public final class GraphV2Definition {
    public static final Set<String> TYPES = Set.of("manualTrigger", "webhookTrigger", "syntheticAction", "capabilityAction",
        "configuredHttp", "condition", "approval", "delay", "transform", "notification", "end");
    public static final Set<String> OPERATIONS = Set.of("support.list", "support.get", "teams.list", "teams.get", "teams.resolve",
        "support.assignBatch", "support.addNote");
    public static final Set<String> FIELD_TYPES = Set.of("STRING", "INTEGER", "BOOLEAN");
    private static final Set<String> COMPARISONS = Set.of("EQ", "NE", "GT", "GTE", "LT", "LTE", "EMPTY", "NOT_EMPTY");
    private static final Set<String> PROTECTED = Set.of("capabilityAction", "configuredHttp", "notification", "syntheticAction");
    public record Structure(Map<String, JsonNode> nodes, List<String> order, Map<String, List<String>> next,
                            Map<String, Set<String>> dominators) {}
    private GraphV2Definition() {}

    public static GraphDefinition.Validation validate(JsonNode root) {
        var errors = new ArrayList<GraphDefinition.Error>();
        if (root == null || !root.isObject()) return new GraphDefinition.Validation(false, List.of(new GraphDefinition.Error(null, "$", "OBJECT_REQUIRED", "Use a graph object.")));
        exact(root, Set.of("schemaVersion", "nodes", "edges"), "$", null, errors);
        if (!integer(root.get("schemaVersion"), 2, 2)) error(errors, null, "$.schemaVersion", "UNSUPPORTED_SCHEMA");
        var nodes = root.path("nodes"); var edges = root.path("edges");
        if (!nodes.isArray() || nodes.isEmpty() || nodes.size() > 50) error(errors, null, "$.nodes", "NODE_LIMIT");
        if (!edges.isArray() || edges.size() > 100) error(errors, null, "$.edges", "EDGE_LIMIT");
        if (!nodes.isArray() || !edges.isArray() || nodes.size() > 50 || edges.size() > 100) return result(errors);
        var byId = new TreeMap<String, JsonNode>(); int i = 0;
        for (var node : nodes) {
            String path = "$.nodes[" + i++ + "]";
            if (!node.isObject()) { error(errors, null, path, "NODE_OBJECT"); continue; }
            String id = node.path("id").asString(""); boolean safe = id.matches("[A-Za-z][A-Za-z0-9_-]{0,63}");
            String label = safe ? id : null;
            exact(node, Set.of("id", "type", "typeVersion", "config", "inputs"), path, label, errors);
            if (!safe) error(errors, null, path + ".id", "INVALID_NODE_ID");
            else if (byId.putIfAbsent(id, node) != null) error(errors, id, path + ".id", "DUPLICATE_NODE_ID");
            String type = node.path("type").asString("");
            if (!TYPES.contains(type)) error(errors, label, path + ".type", "UNKNOWN_TYPE");
            if (!integer(node.get("typeVersion"), 1, 1)) error(errors, label, path + ".typeVersion", "UNSUPPORTED_TYPE_VERSION");
            if (!node.path("inputs").isObject()) {
                // A v1 document cannot become v2 merely by changing its version number.
                error(errors, label, path + ".inputs", "UNSUPPORTED_SCHEMA");
            }
            config(node, label, path, errors);
            bindings(node.path("inputs"), label, path + ".inputs", errors);
        }
        var next = new TreeMap<String, List<String>>(); var previous = new TreeMap<String, Set<String>>();
        byId.keySet().forEach(id -> { next.put(id, new ArrayList<>()); previous.put(id, new TreeSet<>()); });
        var used = new HashSet<String>(); var branches = new HashMap<String, Set<String>>(); i = 0;
        for (var edge : edges) {
            String path = "$.edges[" + i++ + "]";
            if (!edge.isObject()) { error(errors, null, path, "EDGE_OBJECT"); continue; }
            String source = edge.path("source").asString(""); String target = edge.path("target").asString("");
            if (!byId.containsKey(source) || !byId.containsKey(target)) { error(errors, null, path, "MISSING_NODE"); continue; }
            boolean condition = byId.get(source).path("type").asString().equals("condition");
            exact(edge, condition ? Set.of("source", "target", "branch") : Set.of("source", "target"), path, source, errors);
            String branch = edge.path("branch").asString("");
            if (condition && (!Set.of("true", "false").contains(branch) || !branches.computeIfAbsent(source, x -> new HashSet<>()).add(branch)))
                error(errors, source, path, "INVALID_BRANCH");
            if (!used.add(source + ":" + target)) { error(errors, source, path, "DUPLICATE_EDGE"); continue; }
            next.get(source).add(target); previous.get(target).add(source);
        }
        var triggers = byId.keySet().stream().filter(id -> trigger(byId.get(id))).toList();
        var ends = byId.keySet().stream().filter(id -> type(byId.get(id), "end")).toList();
        if (triggers.size() != 1) error(errors, null, "$.nodes", "TRIGGER_COUNT");
        if (ends.size() != 1) error(errors, null, "$.nodes", "END_COUNT");
        for (var entry : byId.entrySet()) {
            String id = entry.getKey(); var node = entry.getValue();
            int exits = type(node, "end") ? 0 : type(node, "condition") ? 2 : 1;
            if (next.get(id).size() != exits || (trigger(node) ? !previous.get(id).isEmpty() : previous.get(id).isEmpty()))
                error(errors, id, "$.edges", "INVALID_CONNECTION");
            if (type(node, "approval") && (next.get(id).size() != 1 || !PROTECTED.contains(byId.get(next.get(id).getFirst()).path("type").asString())))
                error(errors, id, "$.edges", "INVALID_APPROVAL_TARGET");
        }
        var degrees = new TreeMap<String, Integer>(); previous.forEach((id, p) -> degrees.put(id, p.size()));
        var ready = new PriorityQueue<String>(); degrees.forEach((id, n) -> { if (n == 0) ready.add(id); });
        var order = new ArrayList<String>();
        while (!ready.isEmpty()) { String id = ready.remove(); order.add(id); for (String target : next.get(id)) if (degrees.merge(target, -1, Integer::sum) == 0) ready.add(target); }
        if (order.size() != byId.size()) error(errors, null, "$.edges", "CYCLE");
        if (triggers.size() == 1) {
            Set<String> reached = new HashSet<>(); var work = new ArrayDeque<String>(); work.add(triggers.getFirst());
            while (!work.isEmpty()) { String id = work.remove(); if (reached.add(id)) work.addAll(next.get(id)); }
            byId.keySet().stream().filter(id -> !reached.contains(id)).forEach(id -> error(errors, id, "$.edges", "UNREACHABLE"));
        }
        if (order.size() == byId.size()) {
            var dominators = dominators(order, previous);
            for (var entry : byId.entrySet()) checkSources(entry.getKey(), entry.getValue().path("inputs"), byId, dominators, errors);
        }
        return result(errors);
    }

    /** Stable topological positions are storage positions, never an instruction to run every branch. */
    public static Structure structure(JsonNode root) {
        if (!validate(root).valid()) throw new IllegalArgumentException("A valid v2 graph is required.");
        var nodes = new TreeMap<String, JsonNode>(); var next = new TreeMap<String, List<String>>(); var previous = new TreeMap<String, Set<String>>();
        root.path("nodes").forEach(n -> { String id = n.path("id").asString(); nodes.put(id, n.deepCopy()); next.put(id, new ArrayList<>()); previous.put(id, new TreeSet<>()); });
        root.path("edges").forEach(e -> { String s = e.path("source").asString(), t = e.path("target").asString(); next.get(s).add(t); previous.get(t).add(s); });
        var degree = new HashMap<String, Integer>(); previous.forEach((id, p) -> degree.put(id, p.size()));
        var ready = new PriorityQueue<String>(); degree.forEach((id, d) -> { if (d == 0) ready.add(id); }); var order = new ArrayList<String>();
        while (!ready.isEmpty()) { String id = ready.remove(); order.add(id); for (var t : next.get(id)) if (degree.merge(t, -1, Integer::sum) == 0) ready.add(t); }
        next.replaceAll((id, list) -> List.copyOf(list));
        return new Structure(Collections.unmodifiableMap(nodes), List.copyOf(order), Collections.unmodifiableMap(next), dominators(order, previous));
    }

    private static void config(JsonNode node, String id, String path, List<GraphDefinition.Error> errors) {
        var config = node.path("config"); String type = node.path("type").asString(""); path += ".config";
        if (!config.isObject()) { error(errors, id, path, "CONFIG_OBJECT"); return; }
        switch (type) {
            case "manualTrigger", "webhookTrigger" -> {
                exact(config, Set.of("inputFields"), path, id, errors); var fields = config.path("inputFields");
                if (!fields.isObject() || fields.size() > 32) error(errors, id, path + ".inputFields", "INPUT_SCHEMA");
                else for (String field : fields.propertyNames()) if (!name(field) || !fields.get(field).isString() || !FIELD_TYPES.contains(fields.get(field).asString())) error(errors, id, path + ".inputFields", "INPUT_SCHEMA");
            }
            case "syntheticAction" -> {
                exact(config, Set.of("operation", "message"), path, id, errors);
                if (!config.path("operation").asString("").equals("record")) error(errors, id, path, "INVALID_OPERATION");
                text(config.get("message"), 500, id, path + ".message", errors);
            }
            case "capabilityAction", "configuredHttp", "notification" -> {
                exact(config, type.equals("notification") ? Set.of("integrationId", "revision") : Set.of("integrationId", "revision", "operation"), path, id, errors);
                try { if (!config.path("integrationId").isString() || !UUID.fromString(config.path("integrationId").asString()).toString().equals(config.path("integrationId").asString())) throw new IllegalArgumentException(); }
                catch (IllegalArgumentException invalid) { error(errors, id, path + ".integrationId", "CONNECTION_REFERENCE"); }
                if (!integer(config.get("revision"), 0, Long.MAX_VALUE)) error(errors, id, path + ".revision", "CONNECTION_REFERENCE");
                if (!type.equals("notification")) {
                    String operation = config.path("operation").asString("");
                    if (!config.path("operation").isString() || (type.equals("capabilityAction") ? !OPERATIONS.contains(operation) : !operation.matches("[A-Za-z][A-Za-z0-9_-]{0,63}"))) error(errors, id, path + ".operation", "INVALID_OPERATION");
                }
            }
            case "condition" -> {
                String comparison = config.path("operator").asString(""); boolean unary = Set.of("EMPTY", "NOT_EMPTY").contains(comparison);
                exact(config, unary ? Set.of("operator") : Set.of("operator", "compare"), path, id, errors);
                if (!COMPARISONS.contains(comparison)) error(errors, id, path + ".operator", "INVALID_COMPARISON");
                if (!unary && (!primitive(config.get("compare")) || (Set.of("GT", "GTE", "LT", "LTE").contains(comparison) && !integer(config.get("compare"), Long.MIN_VALUE, Long.MAX_VALUE)))) error(errors, id, path + ".compare", "INVALID_COMPARISON");
            }
            case "approval" -> { exact(config, Set.of("prompt"), path, id, errors); text(config.get("prompt"), 500, id, path + ".prompt", errors); }
            case "delay" -> { exact(config, Set.of("seconds"), path, id, errors); if (!integer(config.get("seconds"), 0, 3600)) error(errors, id, path + ".seconds", "DELAY_LIMIT"); }
            case "transform", "end" -> exact(config, Set.of(), path, id, errors);
            default -> { /* Unknown type rejected separately. */ }
        }
        var inputs = node.path("inputs");
        Set<String> required = switch (type) {
            case "condition" -> Set.of("value"); case "notification" -> Set.of("message");
            case "configuredHttp" -> Set.of("pathParameters", "fields");
            case "capabilityAction" -> switch (config.path("operation").asString("")) {
                case "support.list" -> Set.of("limit", "priorities", "statuses");
                case "support.get" -> Set.of("ticketId"); case "teams.get" -> Set.of("teamId");
                case "teams.resolve" -> Set.of("tickets"); case "support.assignBatch" -> Set.of("assignments");
                case "support.addNote" -> Set.of("ticketId", "expectedRevision", "note"); default -> Set.of();
            };
            default -> Set.of();
        };
        if (inputs.isObject() && !type.equals("transform") && !inputs.propertyNames().equals(required)) error(errors, id, path.substring(0, path.length() - 7) + ".inputs", "INPUT_FIELDS");
    }

    private static void bindings(JsonNode inputs, String id, String path, List<GraphDefinition.Error> errors) {
        if (!inputs.isObject()) return;
        if (inputs.size() > 32) { error(errors, id, path, "INPUT_LIMIT"); return; }
        for (String key : inputs.propertyNames()) {
            if (!name(key)) error(errors, id, path, "INPUT_FIELD");
            binding(inputs.get(key), id, path + "." + (name(key) ? key : "field"), errors, true);
        }
    }
    private static void binding(JsonNode value, String id, String path, List<GraphDefinition.Error> errors, boolean partsAllowed) {
        if (!value.isObject()) { error(errors, id, path, "INVALID_MAPPING"); return; }
        var fields = value.propertyNames();
        if (fields.equals(Set.of("literal"))) {
            if (!literal(value.get("literal"))) error(errors, id, path, "LITERAL_LIMIT");
        } else if (fields.equals(Set.of("from", "path"))) {
            if (!value.path("from").isString() || !value.path("from").asString().matches("[A-Za-z][A-Za-z0-9_-]{0,63}") || !value.path("path").isString() || !value.path("path").asString().matches("(?:\\$|[A-Za-z][A-Za-z0-9_]{0,63}(?:\\.[A-Za-z][A-Za-z0-9_]{0,63})?)")) error(errors, id, path, "INVALID_MAPPING");
        } else if (partsAllowed && fields.equals(Set.of("parts")) && value.path("parts").isArray() && !value.path("parts").isEmpty() && value.path("parts").size() <= 16) {
            for (var part : value.path("parts")) {
                binding(part, id, path + ".parts", errors, false);
                if (part.has("literal") && !primitive(part.get("literal"))) error(errors, id, path, "TEXT_PART_TYPE");
                if (part.has("from") && part.path("path").asString().equals("$")) error(errors, id, path, "TEXT_PART_TYPE");
            }
        } else error(errors, id, path, "INVALID_MAPPING");
    }
    private static void checkSources(String id, JsonNode inputs, Map<String, JsonNode> nodes, Map<String, Set<String>> dominators, List<GraphDefinition.Error> errors) {
        if (!inputs.isObject()) return;
        for (String field : inputs.propertyNames()) {
            var binding = inputs.get(field); var refs = new ArrayList<JsonNode>(); refs.add(binding);
            if (binding.has("parts")) binding.path("parts").forEach(refs::add);
            for (var ref : refs) if (ref.has("from")) {
                String source = ref.path("from").asString(""); String path = ref.path("path").asString("");
                if (source.equals(id) || !dominators.getOrDefault(id, Set.of()).contains(source)) error(errors, id, "$.inputs", "SOURCE_NOT_DOMINATING");
                var sourceNode = nodes.get(source);
                if (sourceNode != null && (!output(sourceNode, path) || type(sourceNode, "approval"))) error(errors, id, "$.inputs", "UNREGISTERED_OUTPUT");
            }
        }
    }
    private static boolean output(JsonNode source, String path) {
        String type = source.path("type").asString("");
        if(path.equals("$"))return Set.of("manualTrigger","webhookTrigger","transform").contains(type);
        return switch (type) {
            case "manualTrigger", "webhookTrigger" -> source.path("config").path("inputFields").has(path);
            case "syntheticAction" -> Set.of("message", "recorded").contains(path);
            case "transform" -> source.path("inputs").has(path);
            case "condition" -> path.equals("matched");
            case "configuredHttp" -> path.matches("response\\.[A-Za-z][A-Za-z0-9_]{0,63}");
            case "notification" -> Set.of("notificationId", "status").contains(path);
            case "delay" -> path.equals("resumedAt");
            case "capabilityAction" -> switch (source.path("config").path("operation").asString("")) {
                case "support.list" -> Set.of("tickets", "count").contains(path);
                case "support.get" -> path.equals("ticket"); case "teams.list" -> Set.of("teams", "count").contains(path);
                case "teams.get" -> path.equals("team"); case "teams.resolve" -> Set.of("assignments", "unmatched", "count").contains(path);
                case "support.assignBatch" -> Set.of("items", "applied", "count").contains(path);
                case "support.addNote" -> Set.of("noteId", "ticketId", "revision").contains(path); default -> false;
            };
            default -> false;
        };
    }
    private static Map<String, Set<String>> dominators(List<String> order, Map<String, Set<String>> previous) {
        var result = new TreeMap<String, Set<String>>();
        for (String id : order) {
            Set<String> common = null;
            for (String parent : previous.get(id)) { if (common == null) common = new HashSet<>(result.get(parent)); else common.retainAll(result.get(parent)); }
            if (common == null) common = new HashSet<>(); common.add(id); result.put(id, Set.copyOf(common));
        }
        return Collections.unmodifiableMap(result);
    }
    static boolean primitive(JsonNode value) { return value != null && (value.isBoolean() || (value.isString() && value.asString().length() <= 2000 && value.asString().codePoints().noneMatch(Character::isISOControl)) || integer(value, Long.MIN_VALUE, Long.MAX_VALUE)); }
    static boolean literal(JsonNode value) {
        if (value == null) return false;
        record Item(JsonNode value, int depth) {} var todo = new ArrayDeque<Item>(); todo.add(new Item(value, 0)); int count = 0;
        while (!todo.isEmpty()) {
            var item = todo.remove(); var n = item.value(); if (++count > 512 || item.depth() > 8) return false;
            if (n.isContainer()) {
                if (n.size() > 50 || (n.isObject() && n.propertyNames().stream().anyMatch(k -> !name(k)))) return false;
                for (var child : n) todo.add(new Item(child, item.depth() + 1));
            } else if (!primitive(n)) return false;
        }
        return true;
    }
    private static boolean integer(JsonNode n, long min, long max) { return n != null && n.isIntegralNumber() && n.bigIntegerValue().compareTo(BigInteger.valueOf(min)) >= 0 && n.bigIntegerValue().compareTo(BigInteger.valueOf(max)) <= 0; }
    private static boolean name(String key) { return key.matches("[A-Za-z][A-Za-z0-9_]{0,63}"); }
    private static boolean trigger(JsonNode node) { return type(node, "manualTrigger") || type(node, "webhookTrigger"); }
    private static boolean type(JsonNode node, String value) { return node.path("type").asString("").equals(value); }
    private static void text(JsonNode n, int max, String id, String path, List<GraphDefinition.Error> errors) { if (n == null || !n.isString() || n.asString().isBlank() || n.asString().length() > max || n.asString().codePoints().anyMatch(Character::isISOControl)) error(errors, id, path, "INVALID_TEXT"); }
    private static void exact(JsonNode n, Set<String> fields, String path, String id, List<GraphDefinition.Error> errors) { if (!n.isObject() || !n.propertyNames().equals(fields)) error(errors, id, path, "SCHEMA_FIELDS"); }
    private static void error(List<GraphDefinition.Error> errors, String id, String path, String code) { if (errors.size() < 100) errors.add(new GraphDefinition.Error(id, path, code, "Use the bounded registered schema-v2 contract; supplied values are not included in validation errors.")); }
    private static GraphDefinition.Validation result(List<GraphDefinition.Error> errors) { errors.sort(Comparator.comparing(GraphDefinition.Error::path).thenComparing(GraphDefinition.Error::code).thenComparing(GraphDefinition.Error::nodeId, Comparator.nullsFirst(Comparator.naturalOrder()))); return new GraphDefinition.Validation(errors.isEmpty(), List.copyOf(errors)); }
}
