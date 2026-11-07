package com.quaxt.agentic;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** An explicitly named credential file. Never falls back to another app's home directory. */
public final class CredentialFile {
    private static final Map<Path, Object> LOCKS = new ConcurrentHashMap<>();
    private final Path file;
    private final ChatGptAuth.Codec codec;
    public CredentialFile(Path file, ChatGptAuth.Codec codec) { this.file = file.toAbsolutePath().normalize(); this.codec = codec; }
    public Object lock() { return LOCKS.computeIfAbsent(file, ignored -> new Object()); }
    public Map<String, Object> read() throws IOException {
        synchronized (lock()) {
            if (!Files.exists(file)) return Map.of();
            if (Files.size(file) > 1048576) throw new IOException("Credential file is too large");
            return codec.decode(Files.readString(file));
        }
    }
    public void put(String key, Object value) throws IOException {
        synchronized (lock()) {
            Map<String, Object> data = new LinkedHashMap<>(read());
            if (value == null) data.remove(key); else data.put(key, value);
            Files.createDirectories(file.getParent());
            Path temp = Files.createTempFile(file.getParent(), ".credentials-", ".tmp");
            try {
                restrict(temp);
                Files.writeString(temp, codec.encode(data));
                try { Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
                catch (AtomicMoveNotSupportedException ignored) { Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING); }
            } finally { Files.deleteIfExists(temp); }
        }
    }
    private static void restrict(Path file) throws IOException {
        PosixFileAttributeView posix = Files.getFileAttributeView(file, PosixFileAttributeView.class);
        if (posix != null) { posix.setPermissions(Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)); return; }
        AclFileAttributeView acl = Files.getFileAttributeView(file, AclFileAttributeView.class);
        if (acl != null) acl.setAcl(List.of(AclEntry.newBuilder().setType(AclEntryType.ALLOW).setPrincipal(Files.getOwner(file))
                .setPermissions(EnumSet.allOf(AclEntryPermission.class)).build()));
    }
}
