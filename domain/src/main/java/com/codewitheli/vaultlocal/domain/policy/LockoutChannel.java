package com.codewitheli.vaultlocal.domain.policy;

/** Independent lockout counters. Reveal and copy prompts share {@link #MASTER} with unlock. */
public enum LockoutChannel {
    MASTER,
    RECOVERY
}
