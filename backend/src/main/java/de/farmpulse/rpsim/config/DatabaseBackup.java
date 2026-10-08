package de.farmpulse.rpsim.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.stream.Stream;

import javax.sql.DataSource;

import de.farmpulse.rpsim.common.OwnerOnlyFiles;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Technical review 10/2026, Phase 1.8 (R-8): backs up the H2 file database with H2's {@code BACKUP TO} (a ZIP file
 * with the database file, taken while the database is open). Runs at every start before Flyway migrates, so a
 * failed migration can be undone as well. Keeps the newest {@code generations} files {@code rpsim-<time>.zip}.
 * <p>
 * Restore: stop the backend, unzip the newest file into the database folder (replaces {@code rpsim.mv.db}), start
 * again - see docs/user-guide/fehlerbehebung.md. The password file {@code db.properties} stays as it is.
 */
public final class DatabaseBackup {

    private static final Logger log = LoggerFactory.getLogger(DatabaseBackup.class);
    static final String PREFIX = "rpsim-";
    private static final DateTimeFormatter NAME = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS");

    private DatabaseBackup() {
    }

    /**
     * Writes a backup to {@code dir} and deletes all but the newest {@code generations}. Returns the backup file,
     * or {@code null} if backups are switched off ({@code dir} empty) or the database is not an H2 file database.
     */
    public static Path run(DataSource dataSource, String dir, int generations, LocalDateTime now)
            throws SQLException, IOException {
        if (dir == null || dir.isBlank()) {
            return null;
        }
        Path folder = Path.of(dir).toAbsolutePath().normalize();
        try (Connection c = dataSource.getConnection(); Statement st = c.createStatement()) {
            String url = c.getMetaData().getURL();
            if (!url.startsWith("jdbc:h2:") || url.startsWith("jdbc:h2:mem:")) {
                log.info("No database backup: {} is no H2 file database", url);
                return null;
            }
            Files.createDirectories(folder);
            Path file = folder.resolve(PREFIX + NAME.format(now) + ".zip");
            st.execute("BACKUP TO '" + file.toString().replace("'", "''") + "'");
            OwnerOnlyFiles.restrict(file); // the database content, like db.properties, belongs to the player only
            prune(folder, Math.max(1, generations));
            log.info("Database backup: {}", file);
            return file;
        }
    }

    /** Backups of this folder, newest first (the name sorts by time). */
    static List<Path> backups(Path folder) throws IOException {
        try (Stream<Path> files = Files.list(folder)) {
            return files.filter(f -> {
                String n = f.getFileName().toString();
                return n.startsWith(PREFIX) && n.endsWith(".zip");
            }).sorted((a, b) -> b.getFileName().toString().compareTo(a.getFileName().toString())).toList();
        }
    }

    private static void prune(Path folder, int generations) throws IOException {
        List<Path> all = backups(folder);
        for (Path old : all.subList(Math.min(generations, all.size()), all.size())) {
            Files.deleteIfExists(old);
        }
    }
}
