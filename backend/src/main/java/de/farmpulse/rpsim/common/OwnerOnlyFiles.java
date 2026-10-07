package de.farmpulse.rpsim.common;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.UserPrincipal;
import java.util.EnumSet;
import java.util.List;

/**
 * Technical review 10/2026, Phase 0 (S-1, S-3): files with secrets (database password, AI API keys) are readable and
 * writable by the owner only - POSIX {@code 600}, or on Windows/NTFS an ACL with a single entry for the owner.
 * File systems with neither view keep their permissions ({@link #restrict} returns {@code false}).
 */
public final class OwnerOnlyFiles {

    private OwnerOnlyFiles() {
    }

    /**
     * Writes {@code content} to {@code file}: a temporary file next to it is restricted <em>before</em> the content is
     * written and then moved over the target, so the secret is never readable by others, not even for a moment.
     */
    public static void write(Path file, byte[] content) throws IOException {
        Path dir = file.toAbsolutePath().getParent();
        Files.createDirectories(dir);
        Path tmp = Files.createTempFile(dir, file.getFileName().toString(), ".tmp");
        try {
            restrict(tmp);
            Files.write(tmp, content);
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    /** Restricts an existing file to its owner. Returns {@code false} if the file system supports neither view. */
    public static boolean restrict(Path file) throws IOException {
        PosixFileAttributeView posix = Files.getFileAttributeView(file, PosixFileAttributeView.class);
        if (posix != null) {
            posix.setPermissions(EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
            return true;
        }
        AclFileAttributeView acl = Files.getFileAttributeView(file, AclFileAttributeView.class);
        if (acl != null) {
            UserPrincipal owner = acl.getOwner();
            acl.setAcl(List.of(AclEntry.newBuilder().setType(AclEntryType.ALLOW).setPrincipal(owner)
                    .setPermissions(EnumSet.allOf(AclEntryPermission.class)).build()));
            return true;
        }
        return false;
    }
}
