package dev.buildcli.spike.application;

/** A retryable failure of one task attempt. */
class TaskFailure extends RuntimeException {
    TaskFailure(String message) {
        super(message);
    }
}

/** Token budget exhausted: escalates immediately, no retries. */
final class TokenBudgetExceeded extends TaskFailure {
    TokenBudgetExceeded(String message) {
        super(message);
    }
}
