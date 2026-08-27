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
import com.quaxt.codingagent.CodingAgentOperations;

class FileCredentialStoreTest {
	@TempDir Path tempDir;

	@Test
	void persistsTypedCredentialsWithoutLeakingThemInList() throws Exception {
		Path authPath = tempDir.resolve("nested").resolve("auth.json");
		FileCredentialStore store = CodingAgentOperations.fileCredentialStore(authPath);

		CodingAgentOperations.modifyCredential(store, "openai", ignored -> CodingAgentOperations.apiKeyCredential("sk-test", Map.of("BASE_URL", "https://example.test")));
		CodingAgentOperations.modifyCredential(store, "anthropic", ignored -> CodingAgentOperations.oauthCredential("access", "refresh", 1234));
		CodingAgentOperations.modifyCredential(store, "github-copilot", ignored -> CodingAgentOperations.oauthCredential("copilot", "github", 5678, List.of("gpt-5.4")));
		CodingAgentOperations.modifyCredential(store, "chatgpt", ignored -> CodingAgentOperations.oauthCredential(
				"chatgpt-access", "chatgpt-refresh", 9012, null, Map.of("accountId", "acct-1")));

		Credential.ApiKeyCredential apiKey =
				assertInstanceOf(Credential.ApiKeyCredential.class, CodingAgentOperations.readCredential(store, "openai").orElseThrow());
		assertEquals("sk-test", apiKey.key);
		assertEquals("https://example.test", apiKey.env.get("BASE_URL"));
		assertEquals(
				List.of(
						new CredentialStore.CredentialInfo("openai", "api_key"),
						new CredentialStore.CredentialInfo("anthropic", "oauth"),
						new CredentialStore.CredentialInfo("github-copilot", "oauth"),
						new CredentialStore.CredentialInfo("chatgpt", "oauth")),
				CodingAgentOperations.listCredentials(store));
		assertTrue(Files.readString(authPath).contains("\"sk-test\""));
		Credential.OAuthCredential copilot =
				assertInstanceOf(Credential.OAuthCredential.class, CodingAgentOperations.readCredential(store, "github-copilot").orElseThrow());
		assertEquals(List.of("gpt-5.4"), copilot.availableModelIds);
		Credential.OAuthCredential chatGpt =
				assertInstanceOf(Credential.OAuthCredential.class, CodingAgentOperations.readCredential(store, "chatgpt").orElseThrow());
		assertEquals("acct-1", chatGpt.metadata.get("accountId"));
	}

	@Test
	void serializesReadModifyWriteAndDeletes() throws Exception {
		FileCredentialStore store = CodingAgentOperations.fileCredentialStore(tempDir.resolve("auth.json"));
		CodingAgentOperations.modifyCredential(store, "openai", ignored -> CodingAgentOperations.apiKeyCredential("initial"));
		CodingAgentOperations.modifyCredential(store, "openai", current -> {
			Credential.ApiKeyCredential credential = assertInstanceOf(Credential.ApiKeyCredential.class, current);
			return CodingAgentOperations.apiKeyCredential(credential.key + "-rotated");
		});

		assertEquals(
				"initial-rotated",
				assertInstanceOf(Credential.ApiKeyCredential.class, CodingAgentOperations.readCredential(store, "openai").orElseThrow()).key);
		CodingAgentOperations.deleteCredential(store, "openai");
		assertTrue(CodingAgentOperations.readCredential(store, "openai").isEmpty());
	}

	@Test
	void appliesPrivateFilePermissionsOnPosixFilesystems() throws Exception {
		Path authPath = tempDir.resolve("auth.json");
		CodingAgentOperations.modifyCredential(
				CodingAgentOperations.fileCredentialStore(authPath), "openai", ignored -> CodingAgentOperations.apiKeyCredential("secret"));

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
		FileCredentialStore legacyStore = CodingAgentOperations.fileCredentialStore(legacy);
		CodingAgentOperations.modifyCredential(legacyStore, "chatgpt", ignored -> CodingAgentOperations.oauthCredential(
				"old-access", "refresh", 1234, null, Map.of("accountId", "account-1")));

		FileCredentialStore migrating = CodingAgentOperations.fileCredentialStore(canonical, legacy);
		Credential.OAuthCredential loaded = assertInstanceOf(
				Credential.OAuthCredential.class, CodingAgentOperations.readCredential(migrating, "chatgpt").orElseThrow());
		assertEquals("old-access", loaded.access);

		CodingAgentOperations.modifyCredential(migrating, "chatgpt", current -> CodingAgentOperations.oauthCredential(
				"new-access", "refresh", 5678, null, loaded.metadata));
		assertTrue(Files.exists(canonical));
		assertEquals(
				"new-access",
				assertInstanceOf(Credential.OAuthCredential.class, CodingAgentOperations.readCredential(migrating, "chatgpt").orElseThrow()).access);
	}
}
