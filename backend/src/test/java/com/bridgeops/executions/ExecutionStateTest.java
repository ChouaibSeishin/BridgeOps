package com.bridgeops.executions;

import com.bridgeops.executions.domain.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class ExecutionStateTest {
    @Test void terminalStatesCannotResumeThroughAnyTransition() {
        for (var state : ExecutionState.values()) if (state.terminal())
            for (var next : ExecutionState.values()) assertThat(state.canTransitionTo(next)).isFalse();
    }
    @Test void activeStatesEnforceDispatchWaitAndCancellationGuards() {
        assertThat(ExecutionState.QUEUED.canTransitionTo(ExecutionState.SUCCEEDED)).isFalse();
        assertThat(ExecutionState.QUEUED.canTransitionTo(ExecutionState.RUNNING)).isTrue();
        assertThat(ExecutionState.RUNNING.canTransitionTo(ExecutionState.WAITING_FOR_RETRY)).isTrue();
        assertThat(ExecutionState.RUNNING.canTransitionTo(ExecutionState.WAITING_FOR_DELAY)).isTrue();
        for (var state : ExecutionState.values()) if (!state.terminal()) {
            assertThat(state.canTransitionTo(ExecutionState.CANCELLED)).isTrue();
            assertThat(state.canTransitionTo(ExecutionState.FAILED)).isTrue();
        }
        assertThat(ExecutionState.WAITING_FOR_RETRY.canTransitionTo(ExecutionState.RUNNING)).isFalse();
        assertThatThrownBy(() -> ExecutionState.SUCCEEDED.requireTransition(ExecutionState.QUEUED)).isInstanceOf(IllegalStateException.class);
    }
    @Test void backoffHasDeterministicBoundedJitterAndThreeTotalAttempts() {
        assertThat(new RetryPolicy(() -> 0).delay(1).toMillis()).isEqualTo(1000);
        assertThat(new RetryPolicy(() -> 0.999).delay(2).toMillis()).isBetween(2000L,2500L);
        assertThatThrownBy(() -> new RetryPolicy(() -> 0).delay(3)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RetryPolicy(() -> Double.NaN).delay(1)).isInstanceOf(IllegalStateException.class);
    }
}
