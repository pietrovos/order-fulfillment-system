package com.fulfillops.shared.security;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;

@Service
class MethodSecurityProbe {

    @PreAuthorize("hasRole('SUPERVISOR')")
    String supervisorOnly() {
        return "ok";
    }
}
