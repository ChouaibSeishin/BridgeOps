package com.bridgeops.workflows;

import java.nio.file.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class WorkflowBoundariesTest {
    @Test void workflowsUseOnlyPublicApplicationAndSharedModuleContracts() throws Exception {
        Path root = Path.of("src/main/java/com/bridgeops"); if (!Files.isDirectory(root)) root = Path.of("backend").resolve(root);
        try (var files = Files.walk(root.resolve("workflows"))) {
            for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList())
                for (String line : Files.readAllLines(file)) if (line.startsWith("import com.bridgeops."))
                    assertThat(line).matches("import com\\.bridgeops\\.(workflows\\..*|shared\\..*|identity\\.application\\..*|organizations\\.application\\..*|audit\\.application\\..*);");
        }
    }
}
