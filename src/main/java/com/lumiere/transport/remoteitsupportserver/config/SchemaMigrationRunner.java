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
        migrateControlSessionsStatus();
        migrateChatMessagesRoomId();
    }

    private void migrateControlSessionsStatus() {
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

    private void migrateChatMessagesRoomId() {
        try {
            Integer roomIdColumnExists = jdbcTemplate.queryForObject(
                    """
                    SELECT COUNT(*)
                    FROM INFORMATION_SCHEMA.COLUMNS
                    WHERE TABLE_SCHEMA = DATABASE()
                      AND TABLE_NAME = 'chat_messages'
                      AND COLUMN_NAME = 'room_id'
                    """,
                    Integer.class
            );

            if (roomIdColumnExists != null && roomIdColumnExists == 0) {
                log.info("Adding chat_messages.room_id column");
                jdbcTemplate.execute("ALTER TABLE chat_messages ADD COLUMN room_id VARCHAR(128) NULL");
            }

            Integer sessionIdColumnExists = jdbcTemplate.queryForObject(
                    """
                    SELECT COUNT(*)
                    FROM INFORMATION_SCHEMA.COLUMNS
                    WHERE TABLE_SCHEMA = DATABASE()
                      AND TABLE_NAME = 'chat_messages'
                      AND COLUMN_NAME = 'session_id'
                    """,
                    Integer.class
            );

            if (sessionIdColumnExists != null && sessionIdColumnExists > 0) {
                jdbcTemplate.execute("UPDATE chat_messages SET room_id = CONCAT('session-', session_id) WHERE room_id IS NULL");
                jdbcTemplate.execute("ALTER TABLE chat_messages MODIFY COLUMN session_id BIGINT NULL");
            }

            jdbcTemplate.execute("UPDATE chat_messages SET room_id = 'legacy-room' WHERE room_id IS NULL OR room_id = ''");
            jdbcTemplate.execute("ALTER TABLE chat_messages MODIFY COLUMN room_id VARCHAR(128) NOT NULL");
        } catch (Exception e) {
            log.warn("Schema migration check for chat_messages.room_id skipped: {}", e.getMessage());
        }
    }
}