package com.quaxt.codingagent.ai.auth;

import java.net.URI;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * github.com device authorization and Copilot API-token state. The device
 * flow, token minting, and model-policy behavior lives in
 * CodingAgentOperations.
 */
public final class GitHubCopilotAuth {
	public static final String PROVIDER_ID = "github-copilot";
	public static final String CLIENT_ID = "Iv1.b507a08c87ecfe98";
	public static final String USER_AGENT = "GitHubCopilotChat/0.35.0";
	public static final String DEFAULT_COPILOT_BASE_URL = "https://api.individual.githubcopilot.com";
	public static final Pattern PROXY_ENDPOINT = Pattern.compile("(?:^|;)proxy-ep=([^;]+)");
	public static final long REFRESH_SKEW_MS = 5 * 60 * 1000L;

	public CredentialStore credentials;
	public URI githubBaseUrl;
	public URI copilotTokenUrl;
	public URI defaultCopilotBaseUrl;

	public GitHubCopilotAuth(
			CredentialStore credentials, URI githubBaseUrl, URI copilotTokenUrl, URI defaultCopilotBaseUrl) {
		this.credentials = credentials;
		this.githubBaseUrl = githubBaseUrl;
		this.copilotTokenUrl = copilotTokenUrl;
		this.defaultCopilotBaseUrl = defaultCopilotBaseUrl;
	}

	/** Pending GitHub device authorization presented to the user. */
	public static final class DeviceCode {
		public String deviceCode;
		public String userCode;
		public URI verificationUri;
		public int intervalSeconds;
		public long expiresAtMs;

		public DeviceCode(
				String deviceCode, String userCode, URI verificationUri, int intervalSeconds, long expiresAtMs) {
			this.deviceCode = deviceCode;
			this.userCode = userCode;
			this.verificationUri = verificationUri;
			this.intervalSeconds = intervalSeconds;
			this.expiresAtMs = expiresAtMs;
		}

		@Override
		public boolean equals(Object other) {
			return other instanceof DeviceCode that
					&& Objects.equals(deviceCode, that.deviceCode)
					&& Objects.equals(userCode, that.userCode)
					&& Objects.equals(verificationUri, that.verificationUri)
					&& intervalSeconds == that.intervalSeconds
					&& expiresAtMs == that.expiresAtMs;
		}

		@Override
		public int hashCode() {
			return Objects.hash(deviceCode, userCode, verificationUri, intervalSeconds, expiresAtMs);
		}

		@Override
		public String toString() {
			return "DeviceCode[deviceCode=" + deviceCode + ", userCode=" + userCode
					+ ", verificationUri=" + verificationUri + ", intervalSeconds=" + intervalSeconds
					+ ", expiresAtMs=" + expiresAtMs + "]";
		}
	}

	/** A current derived Copilot bearer token plus the credential-specific API endpoint. */
	public static final class CopilotToken {
		public String accessToken;
		public URI baseUrl;
		public List<String> availableModelIds;

		public CopilotToken(String accessToken, URI baseUrl, List<String> availableModelIds) {
			this.accessToken = accessToken;
			this.baseUrl = baseUrl;
			this.availableModelIds = availableModelIds;
		}

		@Override
		public boolean equals(Object other) {
			return other instanceof CopilotToken that
					&& Objects.equals(accessToken, that.accessToken)
					&& Objects.equals(baseUrl, that.baseUrl)
					&& Objects.equals(availableModelIds, that.availableModelIds);
		}

		@Override
		public int hashCode() {
			return Objects.hash(accessToken, baseUrl, availableModelIds);
		}

		@Override
		public String toString() {
			return "CopilotToken[accessToken=" + accessToken + ", baseUrl=" + baseUrl
					+ ", availableModelIds=" + availableModelIds + "]";
		}
	}
}
