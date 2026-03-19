package com.lumiere.transport.remoteitsupportserver.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class SchemaMigrationRunner implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(SchemaMigrationRunner.class);
    private final JdbcTemplate jdbcTemplate;

    public SchemaMigrationRunner(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void run(String... args) {
        try {
            String dataType = jdbcTemplate.queryForObject(
                    """
                    SELECT DATA_TYPE
                    FROM INFORMATION_SCHEMA.COLUMNS
                    WHERE TABLE_SCHEMA = DATABASE()
                      AND TABLE_NAME = 'control_sessions'
                      AND COLUMN_NAME = 'status'
                    """,
                    String.class
            );

            if (dataType != null && "enum".equalsIgnoreCase(dataType)) {
                log.info("Migrating control_sessions.status from ENUM to VARCHAR(32)");
                jdbcTemplate.execute("ALTER TABLE control_sessions MODIFY COLUMN status VARCHAR(32)");
            }
        } catch (Exception e) {
            log.warn("Schema migration check for control_sessions.status skipped: {}", e.getMessage());
        }
    }
}