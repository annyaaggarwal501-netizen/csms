package com.vertex.csms.security;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * In a real shop (DEMO_MODE=false) nobody may keep a demo password that is printed in the README:
 * at start-up every login that still uses one must choose a new password at its next login.
 */
@Component
public class DefaultPasswordGuard implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DefaultPasswordGuard.class);
    private static final List<String> DEMO_PASSWORDS = List.of("Owner@123", "Staff@123");

    private final AppUserRepository users;
    private final PasswordEncoder encoder;
    private final boolean demoMode;

    public DefaultPasswordGuard(AppUserRepository users, PasswordEncoder encoder, @Value("${csms.demo-mode:true}") boolean demoMode) {
        this.users = users;
        this.encoder = encoder;
        this.demoMode = demoMode;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (demoMode) return;
        for (AppUser u : users.findAllByOrderByIdAsc()) {
            if (!u.isMustChangePassword() && DEMO_PASSWORDS.stream().anyMatch(p -> encoder.matches(p, u.getPasswordHash()))) {
                u.setMustChangePassword(true);
                users.save(u);
                log.warn("User '{}' still has a demo password: they must choose a new one at the next login.", u.getUsername());
            }
        }
    }
}
