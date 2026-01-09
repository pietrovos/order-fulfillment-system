package com.fulfillops.shared.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fulfillops.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.test.context.support.WithMockUser;

class AuthIntegrationTest extends IntegrationTest {

    @Autowired
    UserAccountRepository users;

    @Test
    void seedsOneDemoUserPerRole() {
        assertThat(users.findByUsername("sales")).get().extracting(UserAccount::getRole).isEqualTo(Role.SALES);
        assertThat(users.findByUsername("warehouse")).get().extracting(UserAccount::getRole).isEqualTo(Role.WAREHOUSE);
        assertThat(users.findByUsername("supervisor")).get().extracting(UserAccount::getRole).isEqualTo(Role.SUPERVISOR);
        assertThat(users.findByUsername("sales").orElseThrow().getPasswordHash()).startsWith("$2");
    }

    @Test
    void loginIssuesTokenThatAuthenticatesApiCalls() throws Exception {
        mvc.perform(get("/api/auth/me").header("Authorization", bearer("warehouse")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("warehouse"))
                .andExpect(jsonPath("$.roles[0]").value("WAREHOUSE"));
    }

    @Test
    void wrongPasswordIs401WithoutLeakingWhichFieldWasWrong() throws Exception {
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"sales\",\"password\":\"nope\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"ghost\",\"password\":\"nope\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void apiRequiresAValidToken() throws Exception {
        mvc.perform(get("/api/auth/me")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/auth/me").header("Authorization", "Bearer not.a.jwt"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void blankCredentialsAreAValidationError() throws Exception {
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"\",\"password\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.username").exists());
    }

    @Autowired
    MethodSecurityProbe probe;

    @Test
    @WithMockUser(roles = "SALES")
    void methodSecurityDeniesWrongRole() {
        assertThatThrownBy(probe::supervisorOnly).isInstanceOf(AccessDeniedException.class);
    }

    @Test
    @WithMockUser(roles = "SUPERVISOR")
    void methodSecurityAllowsRightRole() {
        assertThat(probe.supervisorOnly()).isEqualTo("ok");
    }
}
