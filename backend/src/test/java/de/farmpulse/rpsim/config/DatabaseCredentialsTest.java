package de.farmpulse.rpsim.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Properties;

import org.h2.api.ErrorCode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Review 10/2026 Phase 0.1 (S-1): random password for the H2 file database, migration of existing databases. */
class DatabaseCredentialsTest {

    @TempDir
    Path tmp;

    private String url() {
        return "jdbc:h2:file:" + tmp.resolve("rpsim");
    }

    private String passwordFile() {
        return tmp.resolve("db.properties").toString();
    }

    private static void createDatabase(String url, String password) throws SQLException {
        try (Connection c = DriverManager.getConnection(url, "sa", password); Statement s = c.createStatement()) {
            s.execute("CREATE TABLE marker(id INT)");
        }
    }

    private static int errorCodeOf(String url, String password) {
        try (Connection ignored = DriverManager.getConnection(url, "sa", password)) {
            return 0;
        } catch (SQLException e) {
            return e.getErrorCode();
        }
    }

    private String storedPassword() throws IOException {
        Properties p = new Properties();
        try (InputStream in = Files.newInputStream(Path.of(passwordFile()))) {
            p.load(in);
        }
        return p.getProperty("password");
    }

    @Test
    void theErrorCodeConstantIsH2s() {
        assertThat(DatabaseCredentials.WRONG_USER_OR_PASSWORD).isEqualTo(ErrorCode.WRONG_USER_OR_PASSWORD);
    }

    @Test
    void aNewDatabaseGetsARandomPasswordInAnOwnerOnlyFile() throws Exception {
        String password = DatabaseCredentials.resolve(url(), "sa", null, passwordFile());

        assertThat(password).hasSize(64).matches("[0-9a-f]+");
        assertThat(storedPassword()).isEqualTo(password);
        assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(Path.of(passwordFile()))))
                .isEqualTo("rw-------");
        // H2 creates the database with the credentials of the first connection
        createDatabase(url(), password);
        assertThat(errorCodeOf(url(), "")).isEqualTo(ErrorCode.WRONG_USER_OR_PASSWORD);
        assertThat(errorCodeOf(url(), password)).isZero();
    }

    @Test
    void anExistingDatabaseWithoutPasswordIsProtectedOnStart() throws Exception {
        createDatabase(url(), "");

        String password = DatabaseCredentials.resolve(url(), "sa", "", passwordFile());

        assertThat(errorCodeOf(url(), "")).isEqualTo(ErrorCode.WRONG_USER_OR_PASSWORD);
        assertThat(errorCodeOf(url(), password)).isZero();
        assertThat(storedPassword()).isEqualTo(password);
    }

    @Test
    void theNextStartReusesTheStoredPassword() throws Exception {
        createDatabase(url(), "");
        String first = DatabaseCredentials.resolve(url(), "sa", null, passwordFile());
        String second = DatabaseCredentials.resolve(url(), "sa", null, passwordFile());
        assertThat(second).isEqualTo(first);
        assertThat(errorCodeOf(url(), first)).isZero();
    }

    @Test
    void aCrashBetweenFileAndDatabaseIsRepairedByTheNextStart() throws Exception {
        createDatabase(url(), "");
        Files.writeString(Path.of(passwordFile()), "password=0123abcd\n"); // file written, database not changed yet

        String password = DatabaseCredentials.resolve(url(), "sa", null, passwordFile());

        assertThat(password).isEqualTo("0123abcd");
        assertThat(errorCodeOf(url(), "")).isEqualTo(ErrorCode.WRONG_USER_OR_PASSWORD);
        assertThat(errorCodeOf(url(), "0123abcd")).isZero();
    }

    @Test
    void aLostPasswordFileStopsTheStartWithAnExplanation() throws Exception {
        createDatabase(url(), "other-password");

        assertThatThrownBy(() -> DatabaseCredentials.resolve(url(), "sa", null, passwordFile()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("refuses the password").hasMessageContaining("db.properties")
                .hasMessageContaining("fehlerbehebung.md");
        assertThat(errorCodeOf(url(), "other-password")).isZero(); // nothing changed
    }

    @Test
    void aConfiguredPasswordWinsAndNothingIsWritten() {
        assertThat(DatabaseCredentials.resolve(url(), "sa", "mine", passwordFile())).isEqualTo("mine");
        assertThat(Path.of(passwordFile())).doesNotExist();
    }

    @Test
    void withoutPasswordFileOrForInMemoryDatabasesNothingChanges() {
        assertThat(DatabaseCredentials.resolve(url(), "sa", "", "")).isEmpty();
        assertThat(DatabaseCredentials.resolve(url(), "sa", null, null)).isNull();
        assertThat(DatabaseCredentials.resolve("jdbc:h2:mem:rpsim-test;DB_CLOSE_DELAY=-1", "sa", "", passwordFile()))
                .isEmpty();
        assertThat(Path.of(passwordFile())).doesNotExist();
    }

    @Test
    void anEmptyPasswordFileIsAnError() throws IOException {
        Files.writeString(Path.of(passwordFile()), "# nothing\n");
        assertThatThrownBy(() -> DatabaseCredentials.resolve(url(), "sa", null, passwordFile()))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("no 'password' entry");
    }

    @Test
    void databaseFileOfTheUrl() {
        assertThat(DatabaseCredentials.databaseFile("jdbc:h2:file:./data/rpsim-dev"))
                .isEqualTo(Path.of("./data/rpsim-dev.mv.db"));
        assertThat(DatabaseCredentials.databaseFile("jdbc:h2:file:/home/p/.rpsim/rpsim;AUTO_SERVER=TRUE"))
                .isEqualTo(Path.of("/home/p/.rpsim/rpsim.mv.db"));
        assertThat(DatabaseCredentials.databaseFile("jdbc:h2:file:~/.rpsim/rpsim"))
                .isEqualTo(Path.of(System.getProperty("user.home") + "/.rpsim/rpsim.mv.db"));
    }
}
