package com.quaxt.codingagent.mcp;

import java.io.IOException;
import java.net.URI;
import java.util.List;
import java.util.Locale;

/** Opens an OAuth URL using the host platform without an external desktop dependency. */
final class McpBrowser {
	private McpBrowser() {}

	static boolean open(URI uri) {
		String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
		List<String> command;
		if (os.contains("mac")) command = List.of("open", uri.toString());
		else if (os.contains("win")) command = List.of("rundll32", "url.dll,FileProtocolHandler", uri.toString());
		else command = List.of("xdg-open", uri.toString());
		try {
			new ProcessBuilder(command)
					.redirectOutput(ProcessBuilder.Redirect.DISCARD)
					.redirectError(ProcessBuilder.Redirect.DISCARD)
					.start();
			return true;
		} catch (IOException | RuntimeException ignored) {
			return false;
		}
	}
}
