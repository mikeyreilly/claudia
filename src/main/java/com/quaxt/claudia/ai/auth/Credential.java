package com.quaxt.claudia.ai.auth;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * One persisted credential for a provider, stored in
 * {@code ~/.claudia/auth.json}.
 *
 * <p>Marker interface; the sealed hierarchy is retained so operations can
 * pattern match on the credential flavour. All behavior lives in
 * ClaudiaOperations.
 */
public sealed interface Credential permits Credential.ApiKeyCredential, Credential.OAuthCredential {

	/** API key credential with optional provider-scoped configuration values. */
	final class ApiKeyCredential implements Credential {
		public String key;
		public Map<String, String> env;

		public ApiKeyCredential(String key, Map<String, String> env) {
			this.key = key;
			this.env = env;
		}

		@Override
		public boolean equals(Object other) {
			return other instanceof ApiKeyCredential that
					&& Objects.equals(key, that.key)
					&& Objects.equals(env, that.env);
		}

		@Override
		public int hashCode() {
			return Objects.hash(key, env);
		}

		@Override
		public String toString() {
			return "ApiKeyCredential[key=" + key + ", env=" + env + "]";
		}
	}

	/** OAuth refresh/access tokens, expiry in Unix milliseconds, and optional enabled model ids. */
	final class OAuthCredential implements Credential {
		public String access;
		public String refresh;
		public long expires;
		public List<String> availableModelIds;
		public Map<String, String> metadata;

		public OAuthCredential(
				String access,
				String refresh,
				long expires,
				List<String> availableModelIds,
				Map<String, String> metadata) {
			this.access = access;
			this.refresh = refresh;
			this.expires = expires;
			this.availableModelIds = availableModelIds;
			this.metadata = metadata;
		}

		@Override
		public boolean equals(Object other) {
			return other instanceof OAuthCredential that
					&& Objects.equals(access, that.access)
					&& Objects.equals(refresh, that.refresh)
					&& expires == that.expires
					&& Objects.equals(availableModelIds, that.availableModelIds)
					&& Objects.equals(metadata, that.metadata);
		}

		@Override
		public int hashCode() {
			return Objects.hash(access, refresh, expires, availableModelIds, metadata);
		}

		@Override
		public String toString() {
			return "OAuthCredential[access=" + access + ", refresh=" + refresh + ", expires=" + expires
					+ ", availableModelIds=" + availableModelIds + ", metadata=" + metadata + "]";
		}
	}
}
