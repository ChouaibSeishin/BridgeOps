package com.bridgeops.workflows.application;

import com.bridgeops.workflows.domain.GraphDefinition.Validation;

public final class InvalidDefinition extends RuntimeException {
    private final Validation validation;
    public InvalidDefinition(Validation validation) { super("Workflow definition is invalid."); this.validation = validation; }
    public Validation validation() { return validation; }
}
