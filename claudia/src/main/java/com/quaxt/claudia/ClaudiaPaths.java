package com.quaxt.claudia;

import java.nio.file.Path;
import java.util.Objects;

/**
 * The user-home-derived locations owned by Claudia.
 *
 * <p>Production creates this from {@code user.home}; embedders and tests may
 * supply another home directory to keep application state isolated.
 */
public record ClaudiaPaths(Path homeDirectory) {
    private static final String APPLICATION_DIRECTORY = ".claudia";
    private static final String LEGACY_APPLICATION_DIRECTORY = ".pi-java";

    public ClaudiaPaths {
        homeDirectory = Objects.requireNonNull(homeDirectory, "homeDirectory")
                .toAbsolutePath()
                .normalize();
    }

    /** Returns paths rooted at the current JVM user's home directory. */
    public static ClaudiaPaths forCurrentUser() {
        return new ClaudiaPaths(Path.of(System.getProperty("user.home")));
    }

    /** Directory containing Claudia's settings, credentials, sessions, and global instructions. */
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
