package com.quaxt.codingagent.cli.tools;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import com.quaxt.codingagent.ai.util.AbortSignal;

/** Batch git-ignore filtering for local file-search tools. */
final class GitIgnore {
	private static final Duration TIMEOUT = Duration.ofSeconds(30);
	private final String executable;

	GitIgnore(String executable) {
		this.executable = executable;
	}

	List<Path> filter(Path walkRoot, List<Path> candidates, AbortSignal signal) {
		if (candidates.isEmpty()) return candidates;
		Path workingDirectory = Files.isDirectory(walkRoot) ? walkRoot : walkRoot.getParent();
		if (workingDirectory == null || enclosingWorktree(workingDirectory) == null) return candidates;

		List<String> relativeNames = new ArrayList<>(candidates.size());
		for (Path candidate : candidates) {
			relativeNames.add(workingDirectory.relativize(candidate).toString());
		}

		Process process;
		try {
			process = new ProcessBuilder(
					executable, "-C", workingDirectory.toString(), "check-ignore", "--stdin", "-z")
					.redirectErrorStream(true)
					.start();
		} catch (IOException ignored) {
			return candidates;
		}

		ByteArrayOutputStream output = new ByteArrayOutputStream();
		AtomicReference<IOException> transferFailure = new AtomicReference<>();
		Thread writer = Thread.ofVirtual().start(() -> {
			try (var input = process.getOutputStream()) {
				for (String relative : relativeNames) {
					input.write(relative.getBytes(StandardCharsets.UTF_8));
					input.write(0);
				}
			} catch (IOException error) {
				transferFailure.compareAndSet(null, error);
			}
		});
		Thread reader = Thread.ofVirtual().start(() -> {
			try (var bytes = process.getInputStream()) {
				bytes.transferTo(output);
			} catch (IOException error) {
				transferFailure.compareAndSet(null, error);
			}
		});

		long deadline = System.nanoTime() + TIMEOUT.toNanos();
		try {
			while (process.isAlive()) {
				if (signal.isAborted()) {
					process.destroyForcibly();
					join(writer, reader);
					throw new IllegalStateException("Operation aborted");
				}
				if (System.nanoTime() >= deadline) {
					process.destroyForcibly();
					join(writer, reader);
					return candidates;
				}
				process.waitFor(50, TimeUnit.MILLISECONDS);
			}
			join(writer, reader);
		} catch (InterruptedException error) {
			process.destroyForcibly();
			Thread.currentThread().interrupt();
			return candidates;
		}
		if (transferFailure.get() != null || (process.exitValue() != 0 && process.exitValue() != 1)) {
			return candidates;
		}

		Set<String> ignored = nulSeparated(output.toByteArray());
		if (ignored.isEmpty()) return candidates;
		List<Path> filtered = new ArrayList<>(candidates.size());
		for (int index = 0; index < candidates.size(); index++) {
			if (!ignored.contains(relativeNames.get(index))) filtered.add(candidates.get(index));
		}
		return List.copyOf(filtered);
	}

	private static Path enclosingWorktree(Path start) {
		for (Path directory = start.toAbsolutePath().normalize(); directory != null; directory = directory.getParent()) {
			if (Files.exists(directory.resolve(".git"))) return directory;
		}
		return null;
	}

	private static Set<String> nulSeparated(byte[] bytes) {
		Set<String> values = new HashSet<>();
		int start = 0;
		for (int index = 0; index < bytes.length; index++) {
			if (bytes[index] != 0) continue;
			values.add(new String(bytes, start, index - start, StandardCharsets.UTF_8));
			start = index + 1;
		}
		if (start < bytes.length) {
			values.add(new String(bytes, start, bytes.length - start, StandardCharsets.UTF_8));
		}
		return values;
	}

	private static void join(Thread... threads) throws InterruptedException {
		for (Thread thread : threads) thread.join();
	}
}
