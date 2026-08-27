package com.quaxt.codingagent.cli.tools;

import java.time.Duration;

/**
 * Configuration carrier for batch git-ignore filtering of local file searches.
 * The filtering itself lives in CodingAgentOperations.
 */
public final class GitIgnore {
	public static final Duration TIMEOUT = Duration.ofSeconds(30);

	public String executable;

	public GitIgnore(String executable) {
		this.executable = executable;
	}
}
