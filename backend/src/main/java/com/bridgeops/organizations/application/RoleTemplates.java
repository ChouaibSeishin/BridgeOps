package com.bridgeops.organizations.application;

import java.util.*;

public final class RoleTemplates {
    private RoleTemplates(){}
    private static final Set<String> READ=Set.of("organization.read","membership.read","integration.read","workflow.read","execution.read","approval.read","capability.read");
    private static Set<String> plus(String...codes){var result=new HashSet<>(READ);result.addAll(List.of(codes));return Set.copyOf(result);}
    public static final Map<String,Set<String>> ALL=Map.of(
        "OWNER",plus("organization.manage","ownership.transfer","membership.invite","membership.manage","membership.grant_privileged","policy.manage","integration.manage","workflow.edit","workflow.publish","execution.run","execution.retry","execution.cancel","capability.write","approval.decide","audit.read","ai.use"),
        "ADMIN",plus("membership.invite","membership.manage","integration.manage","workflow.edit","workflow.publish","execution.run","execution.retry","execution.cancel","capability.write","audit.read","ai.use"),
        "BUILDER",plus("integration.manage","workflow.edit","workflow.publish","capability.write","ai.use"),
        "OPERATOR",plus("execution.run","execution.retry","execution.cancel","capability.write","ai.use"),
        "APPROVER",plus("approval.decide","audit.read"),"VIEWER",READ);
}
