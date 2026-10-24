package com.bridgeops.executions.application;

import com.bridgeops.shared.domain.AutomationOrigin;
import java.util.UUID;

/** Execution-owned authority port. An accepted origin is identity, never a bearer capability. */
public interface ExecutionAutomation {
    void authorize(UUID organization,AutomationOrigin origin,UUID workflow,UUID version,String hash);
    void operation(UUID organization,AutomationOrigin origin,UUID workflow,UUID version,String hash,String node,UUID connection,Long revision,String operation,String classification);
}
