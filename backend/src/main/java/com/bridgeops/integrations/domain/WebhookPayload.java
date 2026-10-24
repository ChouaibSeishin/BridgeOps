package com.bridgeops.integrations.domain;

import com.bridgeops.shared.web.DomainFailure;
import java.nio.*;
import java.nio.charset.*;
import tools.jackson.core.*;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.*;
import tools.jackson.databind.json.JsonMapper;

/** A single bounded flat input object; no delivery-supplied configuration or authority. */
public final class WebhookPayload {
    private static final JsonMapper JSON=JsonMapper.builder(JsonFactory.builder()
        .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(8).maxStringLength(2000).maxNumberLength(20).build())
        .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build()).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    private WebhookPayload(){}
    public static JsonNode parse(byte[] body){
        if(body==null||body.length>WebhookSignature.MAX_BODY)throw new DomainFailure(413,"WEBHOOK_BODY_TOO_LARGE");
        JsonNode payload;
        try{String text=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(body)).toString();payload=JSON.readTree(text);}
        catch(Exception invalid){throw invalid();}
        if(payload==null||!payload.isObject()||payload.size()!=1||!payload.path("input").isObject()||payload.path("input").size()>100)throw invalid();
        var input=payload.path("input");for(String name:input.propertyNames()){
            text(name);var value=input.get(name);if(value.isString())text(value.asString());else if(!value.isBoolean()&&!value.isIntegralNumber())throw invalid();
        }
        return input;
    }
    private static void text(String value){
        if(value.length()>2000)throw invalid();
        for(int i=0;i<value.length();i++){char c=value.charAt(i);if(c==0)throw invalid();
            if(Character.isHighSurrogate(c)){if(++i>=value.length()||!Character.isLowSurrogate(value.charAt(i)))throw invalid();}
            else if(Character.isLowSurrogate(c))throw invalid();
        }
    }
    private static DomainFailure invalid(){return new DomainFailure(400,"INVALID_WEBHOOK_PAYLOAD");}
}
