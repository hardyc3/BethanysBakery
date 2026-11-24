package com.bethanysbakery.api.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;

/** V8 upgrade guard and clean-install migration test against a real Postgres. */
class V8MigrationTest {

    private static final PostgreSQLContainer<?> UPGRADE_DB = new PostgreSQLContainer<>("postgres:16-alpine");
    private static final PostgreSQLContainer<?> CLEAN_UPGRADE_DB = new PostgreSQLContainer<>("postgres:16-alpine");
    private static final PostgreSQLContainer<?> EMPTY_DB = new PostgreSQLContainer<>("postgres:16-alpine");

    static {
        UPGRADE_DB.start();
        CLEAN_UPGRADE_DB.start();
        EMPTY_DB.start();
    }

    @Test
    void upgradeFailsExplicitlyWhenTwoAdminDevicesAreActive() {
        migrate(UPGRADE_DB, "7");
        seedActiveDevices(UPGRADE_DB, 2);

        assertThatThrownBy(() -> migrate(UPGRADE_DB, null))
            .hasStackTraceContaining("Revoke extra active devices before deploy");
        assertThat(columnCount(UPGRADE_DB, "client_submitted_at")).isZero();
    }

    @Test
    void upgradeAppliesCleanlyFromS7WithOneActiveDevice() {
        migrate(CLEAN_UPGRADE_DB, "7");
        seedActiveDevices(CLEAN_UPGRADE_DB, 1);

        migrate(CLEAN_UPGRADE_DB, null);

        assertThat(columnCount(CLEAN_UPGRADE_DB, "client_submitted_at")).isEqualTo(1);
        assertThat(columnCount(CLEAN_UPGRADE_DB, "request_fingerprint")).isEqualTo(1);
        assertThat(deviceColumnCount(CLEAN_UPGRADE_DB, "fcm_token")).isEqualTo(1);
        assertThat(deviceColumnCount(CLEAN_UPGRADE_DB, "fcm_updated_at")).isEqualTo(1);
        JdbcTemplate jdbc = jdbc(CLEAN_UPGRADE_DB);
        assertThat(jdbc.queryForObject("""
            SELECT count(*) FROM pg_indexes
            WHERE tablename = 'admin_device' AND indexname = 'uq_admin_device_single_active'
            """, Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from admin_device where status = 'ACTIVE'",
            Integer.class)).isEqualTo(1);
    }

    @Test
    void v8AppliesCleanlyToEmptyDatabase() {
        migrate(EMPTY_DB, null);

        assertThat(columnCount(EMPTY_DB, "client_submitted_at")).isEqualTo(1);
        assertThat(columnCount(EMPTY_DB, "request_fingerprint")).isEqualTo(1);
        assertThat(deviceColumnCount(EMPTY_DB, "fcm_token")).isEqualTo(1);
        assertThat(deviceColumnCount(EMPTY_DB, "fcm_updated_at")).isEqualTo(1);
        JdbcTemplate jdbc = jdbc(EMPTY_DB);
        assertThat(jdbc.queryForObject("""
            SELECT count(*) FROM pg_indexes
            WHERE tablename = 'admin_device' AND indexname = 'uq_admin_device_single_active'
            """, Integer.class)).isEqualTo(1);
    }

    private static void seedActiveDevices(PostgreSQLContainer<?> container, int count) {
        JdbcTemplate jdbc = jdbc(container);
        UUID adminId = UUID.randomUUID();
        java.sql.Timestamp now = java.sql.Timestamp.from(Instant.now());
        jdbc.update("""
            INSERT INTO admin_user (id, email, name, password_hash, totp_secret_encrypted, status,
                created_at, updated_at)
            VALUES (?::uuid, ?, ?, ?, ?, 'ACTIVE', ?, ?)
            """, adminId.toString(), "migration@example.com", "Migration Admin", "hash", "secret", now, now);
        for (int i = 0; i < count; i++) {
            jdbc.update("""
                INSERT INTO admin_device (id, admin_user_id, name, platform, public_key_pem,
                    cert_fingerprint_sha256, api_key_hash, status, paired_at, created_at, updated_at)
                VALUES (?::uuid, ?::uuid, ?, 'ANDROID', 'pem', ?, ?, 'ACTIVE', ?, ?, ?)
                """, UUID.randomUUID().toString(), adminId.toString(), "Device " + i,
                "fingerprint-" + i, "api-key-" + i, now, now, now);
        }
    }

    private static void migrate(PostgreSQLContainer<?> container, String target) {
        var config = Flyway.configure()
            .dataSource(container.getJdbcUrl(), container.getUsername(), container.getPassword())
            .locations("classpath:db/migration");
        if (target != null) {
            config.target(target);
        }
        config.load().migrate();
    }

    private static JdbcTemplate jdbc(PostgreSQLContainer<?> container) {
        var dataSource = new org.springframework.jdbc.datasource.DriverManagerDataSource(
            container.getJdbcUrl(), container.getUsername(), container.getPassword());
        return new JdbcTemplate(dataSource);
    }

    private static int columnCount(PostgreSQLContainer<?> container, String column) {
        return jdbc(container).queryForObject("""
            SELECT count(*) FROM information_schema.columns
            WHERE table_name = 'orders' AND column_name = ?
            """, Integer.class, column);
    }

    private static int deviceColumnCount(PostgreSQLContainer<?> container, String column) {
        return jdbc(container).queryForObject("""
            SELECT count(*) FROM information_schema.columns
            WHERE table_name = 'admin_device' AND column_name = ?
            """, Integer.class, column);
    }
}
