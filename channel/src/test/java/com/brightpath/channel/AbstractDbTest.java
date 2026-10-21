package com.brightpath.channel;

import io.micronaut.test.support.TestPropertyProvider;
import org.junit.jupiter.api.AfterAll;
import org.testcontainers.containers.PostgreSQLContainer;

import java.util.Map;

/**
 * Base class for channel tests: spins up a real Postgres via Testcontainers
 * and points the datasource at it (Flyway migrates on context start).
 */
public abstract class AbstractDbTest implements TestPropertyProvider {

    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("brightpath_test")
            .withUsername("brightpath")
            .withPassword("brightpath_test_password")
            .withStartupTimeout(java.time.Duration.ofSeconds(180));

    static {
        if (!POSTGRES.isRunning()) {
            POSTGRES.start();
        }
    }

    @AfterAll
    static void stopDb() {
        // container is shared within the JVM; left running for subsequent test classes
    }

    @Override
    public Map<String, String> getProperties() {
        Map<String, String> props = new java.util.HashMap<>();
        props.put("datasources.default.url", POSTGRES.getJdbcUrl());
        props.put("datasources.default.username", POSTGRES.getUsername());
        props.put("datasources.default.password", POSTGRES.getPassword());
        props.put("datasources.default.db-type", "postgres");
        props.put("datasources.default.driver-class-name", "org.postgresql.Driver");
        props.put("brightpath.seed.enabled", "false");
        props.put("flyway.datasources.default.enabled", "true");
        props.put("jpa.default.packages-to-scan", "com.brightpath.channel.domain");
        props.put("jpa.default.entity-scan.packages", "com.brightpath.channel.domain");
        props.put("jpa.default.properties.hibernate.dialect", "org.hibernate.dialect.PostgreSQLDialect");
        props.put("jpa.default.properties.hibernate.hbm2ddl.auto", "none");
        return props;
    }
}
