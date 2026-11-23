package com.vertex.csms.security;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * The demo logins shown on the login page in demo mode – only the ones that really still work. Once someone
 * changes the demo password (e.g. on the shared cloud database), that login is no longer shown.
 * Checked once at start-up in the background (password checks are deliberately slow) and again after any
 * password change; the login page only reads the remembered answer.
 */
@Component
public class DemoLogins {

    static final Map<String, String> DEMO = Map.of("owner", "Owner@123", "staff", "Staff@123");

    private final AppUserRepository users;
    private final PasswordEncoder encoder;
    private volatile List<Map<String, String>> working = List.of();

    public DemoLogins(AppUserRepository users, PasswordEncoder encoder) {
        this.users = users;
        this.encoder = encoder;
    }

    /** e.g. [{username: owner, password: Owner@123}] – empty when no demo password works any more. */
    public List<Map<String, String>> working() {
        return working;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void atStart() {
        Thread t = new Thread(this::refresh, "demo-logins");
        t.setDaemon(true);
        t.start();
    }

    /** Re-check in the background once the current change is saved (password checks are slow on purpose). */
    public void refreshLater() {
        Runnable run = () -> { Thread t = new Thread(this::refresh, "demo-logins"); t.setDaemon(true); t.start(); };
        if (org.springframework.transaction.support.TransactionSynchronizationManager.isSynchronizationActive()) {
            org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                    new org.springframework.transaction.support.TransactionSynchronization() {
                        @Override public void afterCommit() { run.run(); }
                    });
        } else {
            run.run();
        }
    }

    /** Re-check now. */
    public void refresh() {
        List<Map<String, String>> found = new ArrayList<>();
        for (String name : List.of("owner", "staff")) {
            users.findByUsername(name).filter(AppUser::isActive)
                    .filter(u -> encoder.matches(DEMO.get(name), u.getPasswordHash()))
                    .ifPresent(u -> found.add(Map.of("username", name, "password", DEMO.get(name))));
        }
        working = List.copyOf(found);
    }
}
