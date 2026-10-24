package com.bridgeops.integrations.web;

import com.bridgeops.identity.application.AccessTokens.Identity;
import com.bridgeops.integrations.application.WebhookHistory;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@ConditionalOnProperty(name="bridgeops.auth.enabled",havingValue="true")
public class WebhookHistoryController {
    private final WebhookHistory history;
    public WebhookHistoryController(WebhookHistory history){this.history=history;}
    @GetMapping("/api/v1/organizations/{organization}/webhook-endpoints/{endpoint}/deliveries")
    public WebhookHistory.Page list(@AuthenticationPrincipal Identity actor,@PathVariable UUID organization,@PathVariable UUID endpoint,@RequestParam(defaultValue="0")int page,@RequestParam(defaultValue="25")int size){return history.list(actor.userId(),actor.familyId(),organization,endpoint,page,size);}
}
