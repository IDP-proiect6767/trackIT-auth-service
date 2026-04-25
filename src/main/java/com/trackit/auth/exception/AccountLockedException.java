package com.trackit.auth.exception;

import java.time.Instant;

public class AccountLockedException extends RuntimeException {
    private final Instant unlockAt;

    public AccountLockedException(Instant unlockAt) {
        super("Account is locked until " + unlockAt);
        this.unlockAt = unlockAt;
    }

    public Instant getUnlockAt() {
        return unlockAt;
    }
}
