package com.quaxt.codingagent.ai.auth;

import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.EnumSet;
import java.util.Set;

/**
 * File credential storage state for the Java CLI. The read/modify/write
 * behavior (lock file, atomic replace, 0600 permissions) lives in
 * CodingAgentOperations.
 */
public final class FileCredentialStore implements CredentialStore {
	public static final Set<PosixFilePermission> DIRECTORY_PERMISSIONS =
			EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE);
	public static final Set<PosixFilePermission> FILE_PERMISSIONS =
			EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);

	public Path authPath;
	public Path lockPath;
	public Path fallbackAuthPath;

	public FileCredentialStore(Path authPath, Path lockPath, Path fallbackAuthPath) {
		this.authPath = authPath;
		this.lockPath = lockPath;
		this.fallbackAuthPath = fallbackAuthPath;
	}
}
