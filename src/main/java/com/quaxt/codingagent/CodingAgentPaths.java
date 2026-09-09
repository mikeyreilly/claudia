package com.quaxt.codingagent;

import java.nio.file.Path;
import java.util.Objects;

/**
 * The user-home-derived locations owned by codingagent.
 *
 * <p>Production creates this from {@code user.home}; embedders and tests may
 * supply another home directory to keep application state isolated.
 */
public record CodingAgentPaths(Path homeDirectory) {
    private static final String APPLICATION_DIRECTORY = ".codingagent";
    private static final String LEGACY_APPLICATION_DIRECTORY = ".pi-java";

    public CodingAgentPaths {
        homeDirectory = Objects.requireNonNull(homeDirectory, "homeDirectory")
                .toAbsolutePath()
                .normalize();
    }

    /** Returns paths rooted at the current JVM user's home directory. */
    public static CodingAgentPaths forCurrentUser() {
        return new CodingAgentPaths(Path.of(System.getProperty("user.home")));
    }

    /** Directory containing codingagent's settings, credentials, sessions, and global instructions. */
    public Path settingsDirectory() {
        return homeDirectory.resolve(APPLICATION_DIRECTORY);
    }

    public Path settingsFile() {
        return settingsDirectory().resolve("settings.json");
    }

    public Path authFile() {
        return settingsDirectory().resolve("auth.json");
    }

    public Path mcpAuthFile() {
        return settingsDirectory().resolve("mcp-auth.json");
    }

    public Path sessionsDirectory() {
        return settingsDirectory().resolve("sessions");
    }

    public Path globalInstructionsFile() {
        return settingsDirectory().resolve("AGENTS.md");
    }

    public Path legacyAuthFile() {
        return legacyDirectory().resolve("auth.json");
    }

    public Path legacySessionsDirectory() {
        return legacyDirectory().resolve("sessions");
    }

    private Path legacyDirectory() {
        return homeDirectory.resolve(LEGACY_APPLICATION_DIRECTORY);
    }
}
