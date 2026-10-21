package com.bridgeops.integrations.domain;

import com.bridgeops.shared.web.DomainFailure;
import java.util.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** A registered operation, never a workflow-provided URL, header map or method. */
public record RestOperation(String id,String method,String path,Set<String> pathParameters,Map<String,String> requestFields,
    Map<String,String> responseFields,String classification,int timeoutSeconds,int maximumResponseBytes,boolean remoteIdempotencyKey) {
    private static final JsonMapper JSON=JsonMapper.builder().build();
    private static final Set<String> TYPES=Set.of("STRING","INTEGER","BOOLEAN");
    public RestOperation {
        if(id==null||!id.matches("[a-z][a-zA-Z0-9_-]{0,47}")||!Set.of("GET","POST","PUT","PATCH","DELETE").contains(method)
            ||!Set.of("READ","WRITE").contains(classification)||classification.equals("READ")!=method.equals("GET")
            ||timeoutSeconds<1||timeoutSeconds>10||maximumResponseBytes<256||maximumResponseBytes>1048576
            ||path==null||path.length()>500||!path.matches("/(?:[A-Za-z0-9_-]+|\\{[a-zA-Z][a-zA-Z0-9_]{0,31}\\})(?:/(?:[A-Za-z0-9_-]+|\\{[a-zA-Z][a-zA-Z0-9_]{0,31}\\}))*"))throw invalid();
        pathParameters=Set.copyOf(pathParameters);requestFields=Map.copyOf(requestFields);responseFields=Map.copyOf(responseFields);
        if(pathParameters.size()>8||requestFields.size()>32||responseFields.size()>32)throw invalid();
        var found=new HashSet<String>();var matcher=java.util.regex.Pattern.compile("\\{([^}]+)}").matcher(path);while(matcher.find())found.add(matcher.group(1));
        if(!found.equals(pathParameters)||method.equals("GET")&&!requestFields.isEmpty())throw invalid();
        for(var fields:List.of(requestFields,responseFields))for(var e:fields.entrySet())if(!field(e.getKey())||!TYPES.contains(e.getValue()))throw invalid();
    }
    public static RestOperation parse(JsonNode node){
        try {
            exact(node,Set.of("id","method","path","pathParameters","requestFields","responseFields","classification","timeoutSeconds","maximumResponseBytes","remoteIdempotencyKey"));
            for(String key:List.of("id","method","path","classification"))if(!node.path(key).isString())throw invalid();
            if(!node.path("pathParameters").isArray()||!node.path("remoteIdempotencyKey").isBoolean())throw invalid();
            var paths=new HashSet<String>();for(var value:node.path("pathParameters")){if(!value.isString()||!field(value.asString())||!paths.add(value.asString()))throw invalid();}
            return new RestOperation(node.path("id").asString(),node.path("method").asString(),node.path("path").asString(),paths,fields(node.path("requestFields")),fields(node.path("responseFields")),node.path("classification").asString(),integer(node.path("timeoutSeconds")),integer(node.path("maximumResponseBytes")),node.path("remoteIdempotencyKey").asBoolean());
        }catch(IllegalArgumentException|NullPointerException malformed){throw invalid();}
    }
    public String resolvedPath(JsonNode arguments){
        validateArguments(arguments);String result=path;
        for(String key:pathParameters)result=result.replace("{"+key+"}",arguments.path("pathParameters").path(key).asString());
        return new DestinationPolicy(List.of()).path(result);
    }
    public void validateArguments(JsonNode arguments){
        exact(arguments,Set.of("pathParameters","fields"));var paths=arguments.path("pathParameters");var fields=arguments.path("fields");
        if(!paths.isObject()||!paths.propertyNames().equals(pathParameters)||!fields.isObject()||!fields.propertyNames().equals(requestFields.keySet()))throw invalid();
        for(String key:pathParameters)if(!paths.path(key).isString()||!paths.path(key).asString().matches("[A-Za-z0-9_-]{1,64}"))throw invalid();
        requestFields.forEach((key,type)->check(fields.path(key),type,2000));
    }
    public JsonNode projectResponse(JsonNode source){
        if(!source.isObject())throw new DomainFailure(502,"INVALID_PROVIDER_RESPONSE");var output=JSON.createObjectNode();
        for(var field:responseFields.entrySet()){
            try{check(source.path(field.getKey()),field.getValue(),2000);}catch(DomainFailure invalid){throw new DomainFailure(502,"INVALID_PROVIDER_RESPONSE");}
            output.set(field.getKey(),source.get(field.getKey()));
        }
        return output;
    }
    private static Map<String,String> fields(JsonNode node){if(!node.isObject()||node.size()>32)throw invalid();var fields=new TreeMap<String,String>();for(String key:node.propertyNames()){if(!node.path(key).isString())throw invalid();fields.put(key,node.path(key).asString());}return fields;}
    private static void check(JsonNode value,String type,int length){
        boolean valid=switch(type){case "STRING"->value.isString()&&value.asString().length()<=length&&value.asString().codePoints().noneMatch(c->c==0);case "INTEGER"->value.isIntegralNumber()&&value.bigIntegerValue().bitLength()<=63;case "BOOLEAN"->value.isBoolean();default->false;};if(!valid)throw invalid();
    }
    private static int integer(JsonNode node){if(!node.isIntegralNumber()||node.bigIntegerValue().bitLength()>31)throw invalid();return node.asInt();}
    private static boolean field(String field){return field!=null&&field.matches("[a-zA-Z][a-zA-Z0-9_]{0,31}")&&!Set.of("constructor","prototype","__proto__","authorization","password","credential","secret","apikey","api_key","access_token","refresh_token","accessToken".toLowerCase(Locale.ROOT),"refreshToken".toLowerCase(Locale.ROOT)).contains(field.toLowerCase(Locale.ROOT));}
    private static void exact(JsonNode node,Set<String> keys){if(node==null||!node.isObject()||!node.propertyNames().equals(keys))throw invalid();}
    private static DomainFailure invalid(){return new DomainFailure(422,"INVALID_REST_OPERATION");}
}
