package de.farmpulse.rpsim.common;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.attribute.UserPrincipal;
import java.util.EnumSet;
import java.util.List;

import com.google.common.jimfs.Configuration;
import com.google.common.jimfs.Jimfs;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Review 10/2026 Phase 0.1/0.5: files with secrets are readable by their owner only. */
class OwnerOnlyFilesTest {

    @TempDir
    Path tmp;

    @Test
    void writesTheContentWithMode600OnThisFileSystem() throws IOException {
        Path file = tmp.resolve("sub/secret.properties");
        OwnerOnlyFiles.write(file, "password=abc".getBytes(StandardCharsets.UTF_8));
        assertThat(Files.readString(file)).isEqualTo("password=abc");
        assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(file))).isEqualTo("rw-------");
        assertThat(Files.list(file.getParent())).containsExactly(file); // no temporary file left
    }

    @Test
    void replacesAnExistingFileAndRestrictsIt() throws IOException {
        Path file = tmp.resolve("ai-provider.properties");
        Files.writeString(file, "old");
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-r--r--"));
        OwnerOnlyFiles.write(file, "new".getBytes(StandardCharsets.UTF_8));
        assertThat(Files.readString(file)).isEqualTo("new");
        assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(file))).isEqualTo("rw-------");
    }

    @Test
    void restrictsAnExistingFileOnAUnixFileSystem() throws IOException {
        Configuration unix = Configuration.unix().toBuilder().setAttributeViews("basic", "owner", "posix").build();
        try (FileSystem fs = Jimfs.newFileSystem(unix)) {
            Path file = fs.getPath("/home/player/.rpsim/db.properties");
            Files.createDirectories(file.getParent());
            Files.writeString(file, "password=abc");
            assertThat(OwnerOnlyFiles.restrict(file)).isTrue();
            assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(file))).isEqualTo("rw-------");
        }
    }

    @Test
    void windowsStyleAclKeepsOnlyTheOwner() throws IOException {
        Configuration windows = Configuration.windows().toBuilder().setAttributeViews("basic", "owner", "acl").build();
        try (FileSystem fs = Jimfs.newFileSystem(windows)) {
            Path file = fs.getPath("C:\\Users\\player\\.rpsim\\db.properties");
            Files.createDirectories(file.getParent());
            OwnerOnlyFiles.write(file, "password=abc".getBytes(StandardCharsets.UTF_8));

            AclFileAttributeView view = Files.getFileAttributeView(file, AclFileAttributeView.class);
            UserPrincipal owner = view.getOwner();
            List<AclEntry> acl = view.getAcl();
            assertThat(acl).hasSize(1);
            assertThat(acl.get(0).type()).isEqualTo(AclEntryType.ALLOW);
            assertThat(acl.get(0).principal()).isEqualTo(owner);
            assertThat(acl.get(0).permissions()).isEqualTo(EnumSet.allOf(AclEntryPermission.class));
            assertThat(Files.readString(file)).isEqualTo("password=abc");
        }
    }

    @Test
    void aFileSystemWithoutPermissionViewsIsLeftAsItIs() throws IOException {
        try (FileSystem fs = Jimfs.newFileSystem(Configuration.windows())) { // "basic" view only
            Path file = fs.getPath("C:\\secret.properties");
            Files.writeString(file, "x");
            assertThat(OwnerOnlyFiles.restrict(file)).isFalse();
        }
    }
}
