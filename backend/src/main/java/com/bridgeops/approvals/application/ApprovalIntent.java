package com.bridgeops.approvals.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import tools.jackson.databind.json.JsonMapper;

/** Public immutable intent contract. This application-specific canonical JSON is not RFC 8785. */
public record ApprovalIntent(UUID organization, UUID execution, String gateNode, String actionNode,
    UUID version, String definitionHash, UUID requester, UUID logicalOperation,
    long policyVersion, String policySnapshot, String prompt, Instant expiresAt, Instant deadlineAt,
    String arguments, String argumentHash, String canonical, String hash) {
    private static final JsonMapper JSON = JsonMapper.builder().build();

    public static ApprovalIntent record(UUID organization, UUID execution, String gateNode, String actionNode,
        UUID version, String definitionHash, UUID requester, UUID logicalOperation, long policyVersion,
        String policySnapshot, String prompt, String message, Instant createdAt, Instant expiresAt, Instant deadlineAt) {
        Objects.requireNonNull(organization); Objects.requireNonNull(execution); Objects.requireNonNull(version);
        Objects.requireNonNull(requester); Objects.requireNonNull(logicalOperation);
        if (gateNode == null || !gateNode.matches("[A-Za-z0-9_-]{1,64}") || actionNode == null || !actionNode.matches("[A-Za-z0-9_-]{1,64}")
            || definitionHash == null || !definitionHash.matches("[a-f0-9]{64}") || policyVersion < 0
            || policySnapshot == null || policySnapshot.length() > 1024 || prompt == null || prompt.isBlank() || prompt.length() > 500
            || message == null || message.isBlank() || message.length() > 500 || createdAt == null || expiresAt == null || deadlineAt == null
            || !expiresAt.isAfter(createdAt) || expiresAt.isAfter(deadlineAt))
            throw new IllegalArgumentException("Invalid approval intent.");
        createdAt=createdAt.truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        expiresAt=expiresAt.truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        deadlineAt=deadlineAt.truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        if(!expiresAt.isAfter(createdAt))throw new IllegalArgumentException("Invalid approval expiry.");
        var policy=JSON.readTree(policySnapshot);
        if(!policy.isObject()||policy.size()!=2||!policy.path("required").isBoolean()||!policy.path("lifetimeSeconds").isIntegralNumber()
            ||policy.path("lifetimeSeconds").asLong()<60||policy.path("lifetimeSeconds").asLong()>86400)
            throw new IllegalArgumentException("Invalid approval policy snapshot.");
        policySnapshot=JSON.writeValueAsString(new TreeMap<>(Map.of("required",policy.path("required").asBoolean(),"lifetimeSeconds",policy.path("lifetimeSeconds").asInt())));
        String arguments = JSON.writeValueAsString(new TreeMap<>(Map.of("message", message)));
        var fields = new TreeMap<String,Object>();
        fields.put("schemaVersion",1); fields.put("requestRevision",0); fields.put("organization",organization.toString()); fields.put("execution",execution.toString());
        fields.put("gateNode",gateNode); fields.put("actionNode",actionNode); fields.put("version",version.toString());
        fields.put("definitionHash",definitionHash); fields.put("requester",requester.toString()); fields.put("logicalOperation",logicalOperation.toString());
        fields.put("capability","synthetic"); fields.put("operation","record"); fields.put("arguments",JSON.readTree(arguments));
        fields.put("policyVersion",policyVersion); fields.put("policySnapshot",JSON.readTree(policySnapshot)); fields.put("prompt",prompt);
        fields.put("createdAt",createdAt.toString()); fields.put("expiresAt",expiresAt.toString()); fields.put("deadlineAt",deadlineAt.toString());
        String canonical=JSON.writeValueAsString(fields);
        return new ApprovalIntent(organization,execution,gateNode,actionNode,version,definitionHash,requester,logicalOperation,
            policyVersion,policySnapshot,prompt,expiresAt,deadlineAt,arguments,digest(arguments),canonical,digest(canonical));
    }
    public static String digest(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch(java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException("SHA-256 unavailable."); }
    }
}
