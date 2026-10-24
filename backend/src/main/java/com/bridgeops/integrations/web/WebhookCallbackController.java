package com.bridgeops.integrations.web;

import com.bridgeops.integrations.application.WebhookIntake;
import com.bridgeops.integrations.domain.WebhookSignature;
import com.bridgeops.shared.web.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController
@ConditionalOnProperty(name="bridgeops.auth.enabled",havingValue="true")
public class WebhookCallbackController {
    public record Acknowledgement(UUID executionId,java.time.Instant acceptedAt,boolean replay){}
    private final WebhookIntake intake;
    public WebhookCallbackController(WebhookIntake intake){this.intake=intake;}
    @PostMapping("/api/v1/webhooks/{endpoint}")
    public ResponseEntity<Acknowledgement> receive(@PathVariable UUID endpoint,HttpServletRequest request)throws IOException{
        if(!WebhookCallbackPath.matches(request))throw new DomainFailure(404,"NOT_FOUND");
        String timestamp=one(request,"X-BridgeOps-Timestamp"),delivery=one(request,"X-BridgeOps-Delivery"),signature=one(request,"X-BridgeOps-Signature");
        try{var type=MediaType.parseMediaType(Objects.requireNonNullElse(request.getContentType(),""));
            if(!type.getType().equals("application")||!type.getSubtype().equals("json")||type.getParameters().keySet().stream().anyMatch(k->!k.equals("charset"))||type.getCharset()!=null&&!type.getCharset().equals(StandardCharsets.UTF_8))throw new IllegalArgumentException();
        }catch(IllegalArgumentException invalid){throw new DomainFailure(415,"WEBHOOK_JSON_REQUIRED");}
        if(request.getContentLengthLong()>WebhookSignature.MAX_BODY)throw new DomainFailure(413,"WEBHOOK_BODY_TOO_LARGE");
        byte[] body=request.getInputStream().readNBytes(WebhookSignature.MAX_BODY+1);
        try{var accepted=intake.receive(endpoint,timestamp,delivery,signature,body);return ResponseEntity.status(accepted.replay()?200:202).cacheControl(CacheControl.noStore())
            .location(URI.create("/api/v1/organizations/"+accepted.organization()+"/executions/"+accepted.execution()))
            .body(new Acknowledgement(accepted.execution(),accepted.acceptedAt(),accepted.replay()));
        }finally{Arrays.fill(body,(byte)0);}
    }
    private String one(HttpServletRequest request,String name){var values=Collections.list(request.getHeaders(name));if(values.size()!=1)throw new DomainFailure(401,"INVALID_WEBHOOK_SIGNATURE");return values.getFirst();}
}
