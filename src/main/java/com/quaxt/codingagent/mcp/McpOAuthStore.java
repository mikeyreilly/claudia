package com.quaxt.codingagent.mcp;

import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Carrier for the secure persistent storage of OAuth tokens and dynamically
 * registered MCP clients: the codingagent credential file, its lock file, and
 * the read-only files imported from OpenCode. Reading and writing live in
 * CodingAgentOperations.
 */
public final class McpOAuthStore {
	public static final Set<PosixFilePermission> DIRECTORY_PERMISSIONS = EnumSet.of(
			PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE);
	public static final Set<PosixFilePermission> FILE_PERMISSIONS =
			EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);

	public Path path;
	public Path lockPath;
	public List<Path> importPaths;

	public McpOAuthStore(Path path, Path lockPath, List<Path> importPaths) {
		this.path = path;
		this.lockPath = lockPath;
		this.importPaths = importPaths;
	}

	/** One stored server credential: its tokens and its registered client. */
	public static final class Entry {
		public Tokens tokens;
		public ClientInfo clientInfo;

		public Entry(Tokens tokens, ClientInfo clientInfo) {
			this.tokens = tokens;
			this.clientInfo = clientInfo;
		}
	}

	/** One stored OAuth token set. */
	public static final class Tokens {
		public String accessToken;
		public String refreshToken;
		public Long expiresAt;
		public String scope;

		public Tokens(String accessToken, String refreshToken, Long expiresAt, String scope) {
			this.accessToken = accessToken;
			this.refreshToken = refreshToken;
			this.expiresAt = expiresAt;
			this.scope = scope;
		}
	}

	/** One configured or dynamically registered OAuth client. */
	public static final class ClientInfo {
		public String clientId;
		public String clientSecret;
		public Long clientIdIssuedAt;
		public Long clientSecretExpiresAt;
		public String tokenEndpointAuthMethod;
		public String redirectUri;

		public ClientInfo(
				String clientId,
				String clientSecret,
				Long clientIdIssuedAt,
				Long clientSecretExpiresAt,
				String tokenEndpointAuthMethod,
				String redirectUri) {
			this.clientId = clientId;
			this.clientSecret = clientSecret;
			this.clientIdIssuedAt = clientIdIssuedAt;
			this.clientSecretExpiresAt = clientSecretExpiresAt;
			this.tokenEndpointAuthMethod = tokenEndpointAuthMethod;
			this.redirectUri = redirectUri;
		}
	}
}
