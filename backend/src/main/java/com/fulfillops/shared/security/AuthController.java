package com.fulfillops.shared.security;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.time.Instant;
import java.util.List;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
class AuthController {

    private final AuthenticationManager authenticationManager;
    private final UserAccountRepository users;
    private final TokenService tokens;

    AuthController(AuthenticationManager authenticationManager, UserAccountRepository users, TokenService tokens) {
        this.authenticationManager = authenticationManager;
        this.users = users;
        this.tokens = tokens;
    }

    @PostMapping("/login")
    LoginResponse login(@Valid @RequestBody LoginRequest request) {
        // Throws BadCredentialsException / DisabledException -> 401 via ApiExceptionHandler.
        authenticationManager.authenticate(
                UsernamePasswordAuthenticationToken.unauthenticated(request.username(), request.password()));
        UserAccount user = users.findByUsername(request.username()).orElseThrow();
        TokenService.IssuedToken token = tokens.issue(user);
        return new LoginResponse(token.value(), token.expiresAt(), CurrentUser.of(user));
    }

    @GetMapping("/me")
    CurrentUser me(@AuthenticationPrincipal Jwt jwt) {
        return new CurrentUser(jwt.getSubject(), jwt.getClaimAsString("name"), jwt.getClaimAsStringList("roles"));
    }

    record LoginRequest(@NotBlank String username, @NotBlank String password) {
    }

    record LoginResponse(String token, Instant expiresAt, CurrentUser user) {
    }

    record CurrentUser(String username, String displayName, List<String> roles) {
        static CurrentUser of(UserAccount u) {
            return new CurrentUser(u.getUsername(), u.getDisplayName(), List.of(u.getRole().name()));
        }
    }
}
