package com.bridgeops.executions;
import java.nio.file.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
class ExecutionBoundariesTest {
    @Test void runtimeImportsOnlyPublicApplicationContractsOfOtherModules()throws Exception{
        Path root=Path.of("src/main/java/com/bridgeops");if(!Files.isDirectory(root))root=Path.of("backend").resolve(root);
        try(var files=Files.walk(root.resolve("executions"))){for(Path file:files.filter(p->p.toString().endsWith(".java")).toList())for(String line:Files.readAllLines(file))if(line.startsWith("import com.bridgeops."))assertThat(line).matches("import com\\.bridgeops\\.(executions\\..*|shared\\..*|workflows\\.application\\..*|identity\\.application\\..*|organizations\\.application\\..*|audit\\.application\\..*);");}
    }
}
