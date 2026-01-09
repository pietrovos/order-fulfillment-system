package com.fulfillops.shared.security;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/** Username of the authenticated caller, for audit columns. "system" for background jobs. */
public final class CurrentActor {

    public static final String SYSTEM = "system";

    private CurrentActor() {
    }

    public static String username() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth == null || !auth.isAuthenticated() ? SYSTEM : auth.getName();
    }
}
