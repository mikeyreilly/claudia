package com.quaxt.codingagent.cli.tools;

import java.nio.file.Path;

/**
 * Data carrier for the built-in local filesystem and shell tools: output
 * bounds, result-limit defaults, and the small carriers their execution uses.
 * Tool definitions and filesystem execution live in LocalTools.
 */
public final class BuiltInTools {
	public static final int MAX_LINES = 2_000;
	public static final int MAX_BYTES = 50 * 1024;
	public static final int DEFAULT_FIND_LIMIT = 1_000;
	public static final int DEFAULT_GREP_LIMIT = 100;
	public static final int DEFAULT_LS_LIMIT = 500;

	public BuiltInTools() {}

	/** Command interpreter used by the shell tool. */
	public enum Shell {
		BASH("bash"),
		POWERSHELL("PowerShell");

		public String displayName;

		Shell(String displayName) {
			this.displayName = displayName;
		}
	}

	/** An archive file paired with the entry path requested inside it. */
	public static final class ArchiveLocation {
		public Path archive;
		public String entry;

		public ArchiveLocation(Path archive, String entry) {
			this.archive = archive;
			this.entry = entry;
		}
	}

	/** One resolved replacement span within a file's text. */
	public static final class Replacement {
		public int start;
		public int end;
		public String newText;

		public Replacement(int start, int end, String newText) {
			this.start = start;
			this.end = end;
			this.newText = newText;
		}
	}
}
