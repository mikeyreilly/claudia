package com.quaxt.codingagent.ai.auth;

import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.function.UnaryOperator;

/** Persistent credential store. Mutations are serialized and atomically written. */
public interface CredentialStore {
	Optional<Credential> read(String providerId) throws IOException;

	List<CredentialInfo> list() throws IOException;

	/**
	 * Atomically applies a mutation to a provider credential. Returning null
	 * removes the credential.
	 */
	Optional<Credential> modify(String providerId, UnaryOperator<Credential> operation) throws IOException;

	default void delete(String providerId) throws IOException {
		modify(providerId, ignored -> null);
	}

	record CredentialInfo(String providerId, String type) {}
}
