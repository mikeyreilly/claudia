package com.quaxt.claudia.mcp;

import com.quaxt.claudia.RunningBuild;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * MCP servers that Claudia supplies without any settings-file configuration.
 *
 * <p>The only built-in server is code-lens. It is located inside the checkout that
 * built Claudia: an in-place build (see {@code install.sh}/{@code install.ps1})
 * leaves Claudia in {@code <repo>/target/} and code-lens in
 * {@code <repo>/code-lens/build/}. When that binary is absent the server is simply
 * not offered. Enable/disable choices are stored under {@link #SETTINGS_KEY} in
 * the settings file, never in its OpenCode-compatible {@code mcp} object.
 */
public final class BuiltInMcpServers {
	public static final String CODE_LENS = "code-lens";
	/** Top-level settings object holding a {@link Preference} per built-in server name. */
	public static final String SETTINGS_KEY = "builtInMcp";

	/** Stored enable/disable choices for one built-in server. */
	public record Preference(boolean enabled, List<String> disabledTools) {
		public static final Preference DEFAULT = new Preference(true, List.of());

		public Preference {
			disabledTools = List.copyOf(disabledTools);
		}
	}

	private BuiltInMcpServers() {}

	/** Finds the code-lens binary that belongs to the running Claudia build, if one was built. */
	public static Optional<Path> locateCodeLens() {
		return RunningBuild.artifact().flatMap(artifact -> codeLensFor(artifact, isWindows()));
	}

	/**
	 * Returns {@code <repo>/code-lens/build/code-lens[.exe]} for a Claudia jar or
	 * native executable at {@code <repo>/target/<artifact>}, when it is an executable file.
	 */
	public static Optional<Path> codeLensFor(Path artifact, boolean windows) {
		return RunningBuild.executable(artifact, "code-lens", "build", windows ? "code-lens.exe" : "code-lens");
	}

	/** The local server configuration that runs {@code binary} as code-lens's MCP stdio server. */
	public static McpServerConfig.Local codeLens(Path binary, Preference preference) {
		return new McpServerConfig.Local(
				List.of(binary.toString(), "mcp"),
				null,
				Map.of(),
				preference.enabled(),
				null,
				List.of(),
				preference.disabledTools(),
				true);
	}

	private static boolean isWindows() {
		return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows");
	}
}
