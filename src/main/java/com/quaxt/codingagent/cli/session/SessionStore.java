package com.quaxt.codingagent.cli.session;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Location of the JSONL session files, including directories written before
 * the application-data directory was renamed. Reading, appending, listing,
 * and snapshotting live in CodingAgentOperations.
 */
public final class SessionStore {
	public static final Set<PosixFilePermission> DIRECTORY_PERMISSIONS =
			EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE);
	public static final Set<PosixFilePermission> FILE_PERMISSIONS =
			EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);

	/** One complete append-only JSONL record. */
	public static final class Entry {
		public long timestamp;
		public String type;
		public JsonNode payload;

		public Entry(long timestamp, String type, JsonNode payload) {
			this.timestamp = timestamp;
			this.type = type;
			this.payload = payload;
		}
	}

	public Path directory;
	public List<Path> legacyDirectories;

	public SessionStore(Path directory, List<Path> legacyDirectories) {
		this.directory = directory;
		this.legacyDirectories = legacyDirectories;
	}
}
