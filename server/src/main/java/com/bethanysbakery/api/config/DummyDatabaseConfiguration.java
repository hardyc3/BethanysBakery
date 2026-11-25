package com.bethanysbakery.api.config;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import javax.sql.DataSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/** Dummy-image only: fresh embedded PostgreSQL on every start, no volume, no published DB port. */
@Configuration
@Profile("dummy")
public class DummyDatabaseConfiguration {

    @Bean(destroyMethod = "close")
    EmbeddedPostgres dummyPostgres() throws Exception {
        Path data = Files.createTempDirectory("bethanys-bakery-dummy-pg-");
        return EmbeddedPostgres.builder()
            .setCleanDataDirectory(true)
            .setDataDirectory(data)
            .setPGStartupWait(Duration.ofSeconds(90))
            .setServerConfig("listen_addresses", "127.0.0.1")
            .setLocaleConfig("locale", "C")
            .setLocaleConfig("lc-collate", "C")
            .setLocaleConfig("lc-ctype", "C")
            .start();
    }

    @Bean
    DataSource dataSource(EmbeddedPostgres dummyPostgres) {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(dummyPostgres.getJdbcUrl("postgres", "postgres"));
        config.setUsername("postgres");
        config.setPassword("postgres");
        config.setMaximumPoolSize(10);
        config.setMinimumIdle(1);
        config.setPoolName("dummy-postgres");
        return new HikariDataSource(config);
    }
}
