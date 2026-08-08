package works.earendil.pi.tui;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** Unix foreground-process-group suspension shared by line and full-screen modes. */
final class ProcessSuspender {
	private ProcessSuspender() {}

	static void suspend() throws IOException {
		Process process = new ProcessBuilder("/bin/kill", "-TSTP", "0").redirectErrorStream(true).start();
		try {
			int exitCode = process.waitFor();
			if (exitCode != 0) {
				String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
				throw new IOException(output.isEmpty() ? "kill exited with code " + exitCode : output);
			}
		} catch (InterruptedException error) {
			Thread.currentThread().interrupt();
			throw new IOException("Interrupted while suspending process", error);
		}
	}
}
