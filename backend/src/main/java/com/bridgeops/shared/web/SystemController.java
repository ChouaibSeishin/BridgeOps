package com.bridgeops.shared.web;

import io.swagger.v3.oas.annotations.Operation;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/system")
public class SystemController {
    @Operation(summary = "Read non-sensitive application identity")
    @GetMapping("/info")
    public SystemInfo info() {
        return new SystemInfo("BridgeOps", "v1");
    }

    public record SystemInfo(String name, String apiVersion) { }
}
