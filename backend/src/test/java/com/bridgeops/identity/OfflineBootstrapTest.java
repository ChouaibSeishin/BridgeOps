package com.bridgeops.identity;

import com.bridgeops.identity.bootstrap.OfflineBootstrap;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class OfflineBootstrapTest {
    @Test void disabledAndMissingConfigurationDoNotReadSecretsOrConnect() {
        InputStream forbidden=new InputStream(){public int read(){throw new AssertionError("Secret input should not be read.");}};
        var output=new ByteArrayOutputStream(); var stream=new PrintStream(output);
        assertThat(OfflineBootstrap.run(new String[]{"bootstrap"},Map.of(),forbidden,stream,stream)).isEqualTo(2);
        assertThat(OfflineBootstrap.run(new String[]{"bootstrap"},Map.of("BOOTSTRAP_ENABLED","true"),forbidden,stream,stream)).isEqualTo(2);
    }
    @Test void executableDispatchIsNonWebAndNeverEchoesArguments() throws Exception {
        String canary="synthetic-argument-canary";
        var process=new ProcessBuilder(java.nio.file.Path.of(System.getProperty("java.home"),"bin","java").toString(),"-cp",
            System.getProperty("surefire.test.class.path",System.getProperty("java.class.path")),"com.bridgeops.BridgeOpsApplication","bootstrap",canary)
            .redirectErrorStream(true);
        process.environment().put("BOOTSTRAP_ENABLED","true");var running=process.start();
        assertThat(running.waitFor(15,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        assertThat(running.exitValue()).isEqualTo(2);
        assertThat(new String(running.getInputStream().readAllBytes(),StandardCharsets.UTF_8)).contains("input/configuration is invalid").doesNotContain(canary,"Tomcat","Started BridgeOps");
    }
    @Test void argumentsAndMalformedInputAreRejectedWithoutEchoingSecretValues() {
        String canary="synthetic-cli-canary";var env=new HashMap<String,String>();
        env.put("BOOTSTRAP_ENABLED","true");env.put("BOOTSTRAP_EMAIL","operator@example.invalid");env.put("BOOTSTRAP_DISPLAY_NAME","Operator");
        env.put("DATABASE_URL","jdbc:postgresql://127.0.0.1:1/test");env.put("DATABASE_USER","operator");env.put("DATABASE_PASSWORD",canary);
        var output=new ByteArrayOutputStream(); var stream=new PrintStream(output);
        assertThat(OfflineBootstrap.run(new String[]{"bootstrap",canary},env,new ByteArrayInputStream(new byte[0]),stream,stream)).isEqualTo(2);
        for(byte[] input:List.of(new byte[0],new byte[]{(byte)0xff},"x".repeat(129).getBytes(StandardCharsets.UTF_8)))
            assertThat(OfflineBootstrap.run(new String[]{"bootstrap"},env,new ByteArrayInputStream(input),stream,stream)).isEqualTo(2);
        assertThat(output.toString(StandardCharsets.UTF_8)).doesNotContain(canary,"operator@example.invalid");
    }
}
