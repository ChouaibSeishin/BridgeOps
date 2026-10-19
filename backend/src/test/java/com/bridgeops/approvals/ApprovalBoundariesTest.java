package com.bridgeops.approvals;

import java.nio.file.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class ApprovalBoundariesTest {
    @Test void approvalImportsOnlyPublicOrganizationAuditContractsAndOwnsItsSql()throws Exception {
        Path root=Path.of("src/main/java/com/bridgeops/approvals");if(!Files.isDirectory(root))root=Path.of("backend").resolve(root);
        try(var files=Files.walk(root)){for(Path file:files.filter(p->p.toString().endsWith(".java")).toList()){
            String source=Files.readString(file);
            for(String line:source.lines().toList())if(line.startsWith("import com.bridgeops."))assertThat(line).matches("import com\\.bridgeops\\.(approvals\\..*|shared\\..*|organizations\\.application\\..*|identity\\.application\\..*|audit\\.application\\..*);");
            assertThat(source).doesNotContain("FROM workflow_executions","UPDATE workflow_executions","FROM execution_steps","UPDATE execution_work_items");
        }}
        for(var type:com.bridgeops.approvals.application.ApprovalService.class.getDeclaredClasses())if(type.isRecord())for(var component:type.getRecordComponents())assertThat(component.getGenericType().getTypeName()).doesNotContain(".persistence.");
    }
}
