package com.bridgeops;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class BridgeOpsApplication {
    public static void main(String[] args) {
        if (args.length > 0 && "bootstrap".equals(args[0])) {
            // Offline operator mode emits only its fixed messages; suppress framework/driver logging.
            if (org.slf4j.LoggerFactory.getILoggerFactory() instanceof ch.qos.logback.classic.LoggerContext logging) {
                logging.reset();
                logging.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME).setLevel(ch.qos.logback.classic.Level.OFF);
            }
            java.util.logging.Logger.getLogger("org.postgresql").setLevel(java.util.logging.Level.OFF);
            System.exit(com.bridgeops.identity.bootstrap.OfflineBootstrap.run(args,System.getenv(),System.in,System.out,System.err));
            return;
        }
        SpringApplication.run(BridgeOpsApplication.class, args);
    }
}
