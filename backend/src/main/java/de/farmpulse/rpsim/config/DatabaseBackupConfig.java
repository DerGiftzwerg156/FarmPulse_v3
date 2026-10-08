package de.farmpulse.rpsim.config;

import java.io.IOException;
import java.sql.SQLException;
import java.time.LocalDateTime;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.flyway.autoconfigure.FlywayMigrationStrategy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Technical review 10/2026, Phase 1.8 (R-8): the backup runs in Spring Boot's {@link FlywayMigrationStrategy}, i.e.
 * at every start right before the migration (Spring Boot reference "Execute Flyway Database Migrations on Startup").
 * A failed backup (e.g. disk full) is logged and does not stop the start.
 */
@Configuration(proxyBeanMethods = false)
public class DatabaseBackupConfig {

    private static final Logger log = LoggerFactory.getLogger(DatabaseBackupConfig.class);

    @Bean
    public FlywayMigrationStrategy backupBeforeMigration(RpsimProperties props) {
        return flyway -> {
            try {
                DatabaseBackup.run(flyway.getConfiguration().getDataSource(), props.getDb().getBackupDir(),
                        props.getDb().getBackupGenerations(), LocalDateTime.now());
            } catch (SQLException | IOException | RuntimeException e) {
                log.warn("Database backup failed, starting without a new backup: {}", e.getMessage(), e);
            }
            flyway.migrate();
        };
    }
}
