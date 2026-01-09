package com.fulfillops.shared.security;

public enum Role {
    SALES,
    WAREHOUSE,
    SUPERVISOR;

    public String authority() {
        return "ROLE_" + name();
    }
}
