package de.farmpulse.rpsim.config;

import java.io.IOException;
import java.io.InputStream;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HexFormat;
import java.util.Properties;

import de.farmpulse.rpsim.common.OwnerOnlyFiles;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Technical review 10/2026, Phase 0.1 (S-1): the H2 file database gets a random password instead of {@code sa}
 * without password. The password lives in a properties file that only the owner can read
 * ({@code rpsim.db.password-file}); an existing database without password is switched over once on start.
 * <ul>
 *   <li>a password configured in {@code spring.datasource.password} wins - the player manages it then;</li>
 *   <li>no password file configured, or no H2 file URL ({@code jdbc:h2:file:}) - nothing changes;</li>
 *   <li>the password file is written <em>before</em> the database is changed, so a crash in between is repaired by
 *   the next start (stored password refused, empty password accepted - the stored one is set).</li>
 * </ul>
 */
public final class DatabaseCredentials {

    private static final Logger log = LoggerFactory.getLogger(DatabaseCredentials.class);
    static final String FILE_URL_PREFIX = "jdbc:h2:file:";
    static final String KEY = "password";
    /** {@code org.h2.api.WRONG_USER_OR_PASSWORD} (H2 stays a runtime dependency; asserted by the test). */
    static final int WRONG_USER_OR_PASSWORD = 28000;
    private static final SecureRandom RANDOM = new SecureRandom();

    private DatabaseCredentials() {
    }

    /** The password the data source has to use. */
    public static String resolve(String url, String user, String configuredPassword, String passwordFile) {
        if (configuredPassword != null && !configuredPassword.isEmpty()) {
            return configuredPassword;
        }
        if (passwordFile == null || passwordFile.isBlank() || url == null || !url.startsWith(FILE_URL_PREFIX)) {
            return configuredPassword;
        }
        try {
            String password = loadOrCreate(Path.of(passwordFile));
            if (Files.exists(databaseFile(url))) {
                migrate(url, user, password, Path.of(passwordFile));
            }
            return password;
        } catch (IOException e) {
            throw new IllegalStateException("Database password file " + passwordFile + " cannot be read or written: "
                    + e.getMessage(), e);
        }
    }

    /** {@code jdbc:h2:file:<path>[;options]} -> {@code <path>.mv.db}; {@code ~} is the user home as in H2. */
    static Path databaseFile(String url) {
        String path = url.substring(FILE_URL_PREFIX.length());
        int options = path.indexOf(';');
        if (options >= 0) {
            path = path.substring(0, options);
        }
        if (path.startsWith("~")) {
            path = System.getProperty("user.home") + path.substring(1);
        }
        return Path.of(path + ".mv.db");
    }

    static String loadOrCreate(Path file) throws IOException {
        if (Files.isRegularFile(file)) {
            Properties p = new Properties();
            try (InputStream in = Files.newInputStream(file)) {
                p.load(in);
            }
            String password = p.getProperty(KEY);
            if (password == null || password.isBlank()) {
                throw new IOException("no '" + KEY + "' entry");
            }
            OwnerOnlyFiles.restrict(file);
            return password;
        }
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        String password = HexFormat.of().formatHex(bytes); // hex: safe inside an SQL string literal
        Properties p = new Properties();
        p.setProperty(KEY, password);
        StringWriter out = new StringWriter();
        p.store(out, "FarmPulse database password - created automatically, keep this file next to the database");
        OwnerOnlyFiles.write(file, out.toString().getBytes(StandardCharsets.ISO_8859_1));
        log.info("Created the database password file {}", file.toAbsolutePath());
        return password;
    }

    /** Existing database: sets the stored password if the database still accepts the empty one. */
    static void migrate(String url, String user, String password, Path passwordFile) {
        try (Connection ignored = DriverManager.getConnection(url, user, password)) {
            return; // already protected with the stored password
        } catch (SQLException e) {
            if (e.getErrorCode() != WRONG_USER_OR_PASSWORD) {
                throw new IllegalStateException("Database " + url + " cannot be opened: " + e.getMessage(), e);
            }
        }
        try (Connection c = DriverManager.getConnection(url, user, "");
             Statement s = c.createStatement()) {
            s.execute("SET PASSWORD '" + password + "'");
            log.info("Database {} is protected with the password from {} now", url, passwordFile.toAbsolutePath());
        } catch (SQLException e) {
            if (e.getErrorCode() == WRONG_USER_OR_PASSWORD) {
                throw new IllegalStateException("The database " + url + " refuses the password from "
                        + passwordFile.toAbsolutePath() + ". Restore the db.properties that belongs to this database "
                        + "or delete the database to start over (see docs/user-guide/fehlerbehebung.md).", e);
            }
            throw new IllegalStateException("Database " + url + " cannot be opened: " + e.getMessage(), e);
        }
    }
}
