package com.fulfillops.shared.security;

import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Creates one demo account per role. Disabled in the prod profile. */
@Component
@Order(0)
@ConditionalOnProperty(name = "fulfillops.seed.demo-users", havingValue = "true", matchIfMissing = true)
class DemoUserSeeder implements ApplicationRunner {

    static final String DEMO_PASSWORD = "fulfill123";
    private static final Logger log = LoggerFactory.getLogger(DemoUserSeeder.class);

    private final UserAccountRepository users;
    private final PasswordEncoder encoder;

    DemoUserSeeder(UserAccountRepository users, PasswordEncoder encoder) {
        this.users = users;
        this.encoder = encoder;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        record Demo(String username, String name, Role role) {
        }
        String hash = encoder.encode(DEMO_PASSWORD);
        for (Demo d : List.of(
                new Demo("sales", "Sam Ortega (Sales)", Role.SALES),
                new Demo("warehouse", "Wren Patel (Warehouse)", Role.WAREHOUSE),
                new Demo("supervisor", "Sasha Lindqvist (Supervisor)", Role.SUPERVISOR))) {
            if (!users.existsByUsername(d.username())) {
                users.save(new UserAccount(d.username(), d.name(), hash, d.role()));
                log.info("Seeded demo user '{}'", d.username());
            }
        }
    }
}
