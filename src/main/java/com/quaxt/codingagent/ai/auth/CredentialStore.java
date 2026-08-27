package com.quaxt.codingagent.ai.auth;

import java.util.Objects;

/**
 * Persistent credential store marker. Mutations are serialized and atomically
 * written by the operations in CodingAgentOperations, which dispatch over the
 * concrete store carriers permitted here.
 */
public sealed interface CredentialStore permits FileCredentialStore {

	/** Provider id and credential type pair, without the secret material. */
	final class CredentialInfo {
		public String providerId;
		public String type;

		public CredentialInfo(String providerId, String type) {
			this.providerId = providerId;
			this.type = type;
		}

		@Override
		public boolean equals(Object other) {
			return other instanceof CredentialInfo that
					&& Objects.equals(providerId, that.providerId)
					&& Objects.equals(type, that.type);
		}

		@Override
		public int hashCode() {
			return Objects.hash(providerId, type);
		}

		@Override
		public String toString() {
			return "CredentialInfo[providerId=" + providerId + ", type=" + type + "]";
		}
	}
}
