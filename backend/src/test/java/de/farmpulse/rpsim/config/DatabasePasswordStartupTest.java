package de.farmpulse.rpsim.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Properties;

import javax.sql.DataSource;

import org.flywaydb.core.Flyway;
import org.h2.api.ErrorCode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Review 10/2026 Phase 0.1, acceptance: the backend starts on an existing H2 file database without password (as every
 * installation up to 1.7.0 has it), migrates it with Flyway and protects it with the generated password.
 */
@SpringBootTest
@DirtiesContext
class DatabasePasswordStartupTest {

    static Path dir;
    static String url;

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) throws IOException, SQLException {
        dir = Files.createTempDirectory("rpsim-db-password");
        url = "jdbc:h2:file:" + dir.resolve("rpsim");
        // an installation of 1.7.0: all migrations applied, user sa without password, one savegame-independent row
        Flyway.configure().dataSource(url, "sa", "").locations("classpath:db/migration").load().migrate();
        try (Connection c = DriverManager.getConnection(url, "sa", ""); Statement s = c.createStatement()) {
            s.execute("CREATE TABLE legacy_marker(id INT)");
            s.execute("INSERT INTO legacy_marker VALUES (1)");
        }
        r.add("spring.datasource.url", () -> url);
        r.add("spring.datasource.username", () -> "sa");
        r.add("spring.datasource.password", () -> "");
        r.add("rpsim.db.password-file", () -> dir.resolve("db.properties").toString());
    }

    @Autowired
    DataSource dataSource;

    @Test
    void startsOnTheOldDatabaseAndProtectsIt() throws Exception {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM \"flyway_schema_history\" WHERE \"success\"",
                Integer.class))
                .isPositive();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM legacy_marker", Integer.class)).isOne(); // same data

        Properties p = new Properties();
        try (InputStream in = Files.newInputStream(dir.resolve("db.properties"))) {
            p.load(in);
        }
        String password = p.getProperty("password");
        assertThat(password).hasSize(64);
        try (Connection ignored = DriverManager.getConnection(url, "sa", "")) {
            throw new AssertionError("the empty password must be refused now");
        } catch (SQLException e) {
            assertThat(e.getErrorCode()).isEqualTo(ErrorCode.WRONG_USER_OR_PASSWORD);
        }
    }
}
