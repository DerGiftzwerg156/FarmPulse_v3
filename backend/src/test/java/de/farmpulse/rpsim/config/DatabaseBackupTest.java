package de.farmpulse.rpsim.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Properties;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import javax.sql.DataSource;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Technical review 10/2026, Phase 1.8 (R-8): a backup exists after the start, it holds the database as it was before
 * the migration, and unzipping it into the database folder restores it (the steps of the troubleshooting guide).
 */
@SpringBootTest
@DirtiesContext
class DatabaseBackupTest {

    static Path dir;
    static String url;

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) throws IOException, SQLException {
        dir = Files.createTempDirectory("rpsim-db-backup");
        url = "jdbc:h2:file:" + dir.resolve("rpsim");
        // an installation one release back: migrated up to V40, one row of the player
        Flyway.configure().dataSource(url, "sa", "").locations("classpath:db/migration").target("40").load().migrate();
        try (Connection c = DriverManager.getConnection(url, "sa", ""); Statement s = c.createStatement()) {
            s.execute("CREATE TABLE player_marker(id INT)");
            s.execute("INSERT INTO player_marker VALUES (7)");
        }
        r.add("spring.datasource.url", () -> url);
        r.add("spring.datasource.username", () -> "sa");
        r.add("spring.datasource.password", () -> "");
        r.add("rpsim.db.password-file", () -> dir.resolve("db.properties").toString());
        r.add("rpsim.db.backup-dir", () -> dir.resolve("backups").toString());
    }

    @Autowired
    DataSource dataSource;

    @Test
    void theStartBacksUpTheDatabaseBeforeTheMigrationAndTheBackupRestores(@TempDir Path restored) throws Exception {
        assertThat(version(new JdbcTemplate(dataSource))).isGreaterThan(40); // the start migrated

        List<Path> backups = DatabaseBackup.backups(dir.resolve("backups"));
        assertThat(backups).hasSize(1);

        // restore as documented: unzip the backup into the (here: an empty) database folder
        try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(backups.getFirst()))) {
            ZipEntry entry = zip.getNextEntry();
            assertThat(entry.getName()).isEqualTo("rpsim.mv.db");
            Files.copy(zip, restored.resolve(entry.getName()));
            assertThat(zip.getNextEntry()).isNull();
        }
        try (Connection c = DriverManager.getConnection("jdbc:h2:file:" + restored.resolve("rpsim"), "sa", password())) {
            JdbcTemplate jdbc = new JdbcTemplate(new SingleConnection(c));
            assertThat(version(jdbc)).isEqualTo(40); // the state before the migration
            assertThat(jdbc.queryForObject("SELECT id FROM player_marker", Integer.class)).isEqualTo(7);
        }
    }

    @Test
    void keepsTheNewestGenerations(@TempDir Path folder) throws Exception {
        DataSource db = new DriverManagerDataSource("jdbc:h2:file:" + folder.resolve("db"), "sa", "");
        LocalDateTime t = LocalDateTime.of(2026, 10, 7, 12, 0);
        for (int i = 0; i < 7; i++) {
            DatabaseBackup.run(db, folder.resolve("backups").toString(), 5, t.plusMinutes(i));
        }
        assertThat(DatabaseBackup.backups(folder.resolve("backups"))).extracting(p -> p.getFileName().toString())
                .containsExactly("rpsim-20261007-120600-000.zip", "rpsim-20261007-120500-000.zip",
                        "rpsim-20261007-120400-000.zip", "rpsim-20261007-120300-000.zip", "rpsim-20261007-120200-000.zip");
    }

    @Test
    void noBackupWithoutFolderOrForInMemoryDatabases(@TempDir Path folder) throws Exception {
        DataSource mem = new DriverManagerDataSource("jdbc:h2:mem:backup-test", "sa", "");
        assertThat(DatabaseBackup.run(mem, folder.toString(), 5, LocalDateTime.now())).isNull();
        assertThat(DatabaseBackup.run(mem, "", 5, LocalDateTime.now())).isNull();
        try (var files = Files.list(folder)) {
            assertThat(files).isEmpty();
        }
    }

    private static int version(JdbcTemplate jdbc) {
        return jdbc.queryForObject("SELECT MAX(CAST(\"version\" AS INT)) FROM \"flyway_schema_history\" WHERE \"success\"",
                Integer.class);
    }

    private static String password() throws IOException {
        Properties p = new Properties();
        try (InputStream in = Files.newInputStream(dir.resolve("db.properties"))) {
            p.load(in);
        }
        return p.getProperty("password");
    }

    /** A DataSource on one open connection (closing it is the caller's job). */
    private static final class SingleConnection extends org.springframework.jdbc.datasource.SingleConnectionDataSource {
        SingleConnection(Connection c) {
            super(c, true);
        }
    }
}
