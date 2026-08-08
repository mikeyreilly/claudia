package com.quaxt.codingagent.ai.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FileCredentialStoreTest {
	@TempDir Path tempDir;

	@Test
	void persistsTypedCredentialsWithoutLeakingThemInList() throws Exception {
		Path authPath = tempDir.resolve("nested").resolve("auth.json");
		FileCredentialStore store = new FileCredentialStore(authPath);

		store.modify("openai", ignored -> new Credential.ApiKeyCredential("sk-test", Map.of("BASE_URL", "https://example.test")));
		store.modify("anthropic", ignored -> new Credential.OAuthCredential("access", "refresh", 1234));
		store.modify("github-copilot", ignored -> new Credential.OAuthCredential("copilot", "github", 5678, List.of("gpt-5.4")));
		store.modify("chatgpt", ignored -> new Credential.OAuthCredential(
				"chatgpt-access", "chatgpt-refresh", 9012, null, Map.of("accountId", "acct-1")));

		Credential.ApiKeyCredential apiKey =
				assertInstanceOf(Credential.ApiKeyCredential.class, store.read("openai").orElseThrow());
		assertEquals("sk-test", apiKey.key());
		assertEquals("https://example.test", apiKey.env().get("BASE_URL"));
		assertEquals(
				List.of(
						new CredentialStore.CredentialInfo("openai", "api_key"),
						new CredentialStore.CredentialInfo("anthropic", "oauth"),
						new CredentialStore.CredentialInfo("github-copilot", "oauth"),
						new CredentialStore.CredentialInfo("chatgpt", "oauth")),
				store.list());
		assertTrue(Files.readString(authPath).contains("\"sk-test\""));
		Credential.OAuthCredential copilot =
				assertInstanceOf(Credential.OAuthCredential.class, store.read("github-copilot").orElseThrow());
		assertEquals(List.of("gpt-5.4"), copilot.availableModelIds());
		Credential.OAuthCredential chatGpt =
				assertInstanceOf(Credential.OAuthCredential.class, store.read("chatgpt").orElseThrow());
		assertEquals("acct-1", chatGpt.metadata().get("accountId"));
	}

	@Test
	void serializesReadModifyWriteAndDeletes() throws Exception {
		FileCredentialStore store = new FileCredentialStore(tempDir.resolve("auth.json"));
		store.modify("openai", ignored -> new Credential.ApiKeyCredential("initial"));
		store.modify("openai", current -> {
			Credential.ApiKeyCredential credential = assertInstanceOf(Credential.ApiKeyCredential.class, current);
			return new Credential.ApiKeyCredential(credential.key() + "-rotated");
		});

		assertEquals(
				"initial-rotated",
				assertInstanceOf(Credential.ApiKeyCredential.class, store.read("openai").orElseThrow()).key());
		store.delete("openai");
		assertTrue(store.read("openai").isEmpty());
	}

	@Test
	void appliesPrivateFilePermissionsOnPosixFilesystems() throws Exception {
		Path authPath = tempDir.resolve("auth.json");
		new FileCredentialStore(authPath).modify("openai", ignored -> new Credential.ApiKeyCredential("secret"));

		try {
			assertEquals(
					java.util.Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
					Files.getPosixFilePermissions(authPath));
		} catch (UnsupportedOperationException ignored) {
			// Windows does not expose POSIX permission bits.
		}
	}

	@Test
	void readsLegacyCredentialUntilCanonicalStoreIsWritten() throws Exception {
		Path canonical = tempDir.resolve(".codingagent/auth.json");
		Path legacy = tempDir.resolve(".pi-java/auth.json");
		FileCredentialStore legacyStore = new FileCredentialStore(legacy);
		legacyStore.modify("chatgpt", ignored -> new Credential.OAuthCredential(
				"old-access", "refresh", 1234, null, Map.of("accountId", "account-1")));

		FileCredentialStore migrating = new FileCredentialStore(canonical, legacy);
		Credential.OAuthCredential loaded = assertInstanceOf(
				Credential.OAuthCredential.class, migrating.read("chatgpt").orElseThrow());
		assertEquals("old-access", loaded.access());

		migrating.modify("chatgpt", current -> new Credential.OAuthCredential(
				"new-access", "refresh", 5678, null, loaded.metadata()));
		assertTrue(Files.exists(canonical));
		assertEquals(
				"new-access",
				assertInstanceOf(Credential.OAuthCredential.class, migrating.read("chatgpt").orElseThrow()).access());
	}
}
