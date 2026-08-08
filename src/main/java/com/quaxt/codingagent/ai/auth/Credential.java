package com.quaxt.codingagent.ai.auth;

import java.util.Map;
import java.util.List;

/** One persisted credential for a provider, stored in {@code ~/.pi-java/auth.json}. */
public sealed interface Credential permits Credential.ApiKeyCredential, Credential.OAuthCredential {
	String type();

	/** API key credential with optional provider-scoped configuration values. */
	record ApiKeyCredential(String key, Map<String, String> env) implements Credential {
		public ApiKeyCredential {
			env = env == null ? Map.of() : Map.copyOf(env);
		}

		public ApiKeyCredential(String key) {
			this(key, Map.of());
		}

		@Override
		public String type() {
			return "api_key";
		}
	}

	/** OAuth refresh/access tokens, expiry in Unix milliseconds, and optional enabled model ids. */
	record OAuthCredential(String access, String refresh, long expires, List<String> availableModelIds) implements Credential {
		public OAuthCredential {
			availableModelIds = availableModelIds == null ? null : List.copyOf(availableModelIds);
		}

		public OAuthCredential(String access, String refresh, long expires) {
			this(access, refresh, expires, null);
		}

		@Override
		public String type() {
			return "oauth";
		}

		public boolean isExpired(long nowMs) {
			return expires <= nowMs;
		}
	}
}
