package com.quaxt.codingagent.cli.settings;

import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;
import com.quaxt.codingagent.ai.types.ThinkingLevel;

/**
 * Location of the global settings file and the lock that serializes updates
 * across processes. Loading, updating, and atomic replacement live in
 * CodingAgentOperations.
 */
public final class SettingsStore {
	public static final Set<PosixFilePermission> DIRECTORY_PERMISSIONS =
			EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE);
	public static final Set<PosixFilePermission> FILE_PERMISSIONS =
			EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);

	/** The settings currently understood by the Java CLI. */
	public static final class Settings {
		public String defaultProvider;
		public String defaultModel;
		public ThinkingLevel defaultThinkingLevel;
		public String theme;
		public boolean hideThinkingBlock;

		public Settings(
				String defaultProvider,
				String defaultModel,
				ThinkingLevel defaultThinkingLevel,
				String theme,
				boolean hideThinkingBlock) {
			this.defaultProvider = defaultProvider;
			this.defaultModel = defaultModel;
			this.defaultThinkingLevel = defaultThinkingLevel;
			this.theme = theme;
			this.hideThinkingBlock = hideThinkingBlock;
		}

		@Override
		public boolean equals(Object other) {
			return other instanceof Settings that
					&& Objects.equals(defaultProvider, that.defaultProvider)
					&& Objects.equals(defaultModel, that.defaultModel)
					&& defaultThinkingLevel == that.defaultThinkingLevel
					&& Objects.equals(theme, that.theme)
					&& hideThinkingBlock == that.hideThinkingBlock;
		}

		@Override
		public int hashCode() {
			return Objects.hash(defaultProvider, defaultModel, defaultThinkingLevel, theme, hideThinkingBlock);
		}

		@Override
		public String toString() {
			return "Settings[defaultProvider=" + defaultProvider
					+ ", defaultModel=" + defaultModel
					+ ", defaultThinkingLevel=" + defaultThinkingLevel
					+ ", theme=" + theme
					+ ", hideThinkingBlock=" + hideThinkingBlock + "]";
		}
	}

	public Path settingsPath;
	public Path lockPath;

	public SettingsStore(Path settingsPath, Path lockPath) {
		this.settingsPath = settingsPath;
		this.lockPath = lockPath;
	}
}
