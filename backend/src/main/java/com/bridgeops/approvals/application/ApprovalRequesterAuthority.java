package com.bridgeops.approvals.application;

import com.bridgeops.shared.domain.*;
import java.util.UUID;

/** Approval-owned port for the live authority behind an exact automated operation. */
public interface ApprovalRequesterAuthority {
    void check(UUID organization,UUID execution,ActorReference actor,AutomationOrigin origin,String action,OperationApprovalIntent.Connection connection,String operation,String classification);
}
