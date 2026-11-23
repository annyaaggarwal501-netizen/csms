package com.vertex.csms;

import org.springframework.boot.autoconfigure.flyway.FlywayMigrationStrategy;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/** The test database is wiped and rebuilt from the migrations when the test context starts. */
@TestConfiguration
class CleanDatabase {
    @Bean
    FlywayMigrationStrategy cleanThenMigrate() {
        return flyway -> { flyway.clean(); flyway.migrate(); };
    }
}
