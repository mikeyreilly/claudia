package com.quaxt.codingagent.ai.auth;

import java.net.URI;
import java.util.Objects;

/**
 * OpenAI Codex device authorization state for ChatGPT subscription access.
 * The device flow, token refresh, and credential persistence behavior lives in
 * CodingAgentOperations.
 */
public final class ChatGptAuth {
	public static final String PROVIDER_ID = "chatgpt";
	public static final URI CODEX_API_BASE_URL = URI.create("https://chatgpt.com/backend-api/codex");
	public static final String CLIENT_ID = "app_EMoamEEZ73f0CkXaXp7hrann";
	public static final String ACCOUNT_ID = "accountId";
	public static final long REFRESH_SKEW_MS = 5 * 60 * 1000L;
	public static final long DEVICE_CODE_LIFETIME_MS = 15 * 60 * 1000L;

	public CredentialStore credentials;
	public URI authBaseUrl;
	public String clientId;

	public ChatGptAuth(CredentialStore credentials, URI authBaseUrl, String clientId) {
		this.credentials = credentials;
		this.authBaseUrl = authBaseUrl;
		this.clientId = clientId;
	}

	/** Pending device authorization presented to the user. */
	public static final class DeviceCode {
		public String deviceAuthId;
		public String userCode;
		public URI verificationUri;
		public int intervalSeconds;
		public long expiresAtMs;

		public DeviceCode(
				String deviceAuthId, String userCode, URI verificationUri, int intervalSeconds, long expiresAtMs) {
			this.deviceAuthId = deviceAuthId;
			this.userCode = userCode;
			this.verificationUri = verificationUri;
			this.intervalSeconds = intervalSeconds;
			this.expiresAtMs = expiresAtMs;
		}

		@Override
		public boolean equals(Object other) {
			return other instanceof DeviceCode that
					&& Objects.equals(deviceAuthId, that.deviceAuthId)
					&& Objects.equals(userCode, that.userCode)
					&& Objects.equals(verificationUri, that.verificationUri)
					&& intervalSeconds == that.intervalSeconds
					&& expiresAtMs == that.expiresAtMs;
		}

		@Override
		public int hashCode() {
			return Objects.hash(deviceAuthId, userCode, verificationUri, intervalSeconds, expiresAtMs);
		}

		@Override
		public String toString() {
			return "DeviceCode[deviceAuthId=" + deviceAuthId + ", userCode=" + userCode
					+ ", verificationUri=" + verificationUri + ", intervalSeconds=" + intervalSeconds
					+ ", expiresAtMs=" + expiresAtMs + "]";
		}
	}

	/** A current ChatGPT bearer token plus the subscription account id. */
	public static final class ChatGptToken {
		public String accessToken;
		public String accountId;

		public ChatGptToken(String accessToken, String accountId) {
			this.accessToken = accessToken;
			this.accountId = accountId;
		}

		@Override
		public boolean equals(Object other) {
			return other instanceof ChatGptToken that
					&& Objects.equals(accessToken, that.accessToken)
					&& Objects.equals(accountId, that.accountId);
		}

		@Override
		public int hashCode() {
			return Objects.hash(accessToken, accountId);
		}

		@Override
		public String toString() {
			return "ChatGptToken[accessToken=" + accessToken + ", accountId=" + accountId + "]";
		}
	}
}
