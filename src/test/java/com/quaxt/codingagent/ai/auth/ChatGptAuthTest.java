package com.quaxt.codingagent.ai.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.quaxt.codingagent.CodingAgentOperations;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ChatGptAuthTest {
	@TempDir Path tempDir;

	@Test
	void deviceLoginPersistsAccountAndRefreshesExpiredToken() throws Exception {
		AtomicInteger tokenExchanges = new AtomicInteger();
		HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		try {
			server.createContext("/api/accounts/deviceauth/usercode", exchange -> respond(exchange, 200,
					"{\"device_auth_id\":\"device-1\",\"user_code\":\"ABCD-EFGH\",\"interval\":\"0\"}"));
			server.createContext("/api/accounts/deviceauth/token", exchange -> respond(exchange, 200,
					"{\"authorization_code\":\"auth-code\",\"code_verifier\":\"verifier\"}"));
			server.createContext("/oauth/token", exchange -> {
				exchange.getRequestBody().readAllBytes();
				int request = tokenExchanges.incrementAndGet();
				String access = request == 1 ? "access-1" : "access-2";
				respond(exchange, 200, "{\"access_token\":\"" + access
						+ "\",\"refresh_token\":\"refresh-1\",\"expires_in\":1,\"id_token\":\""
						+ jwt("account-123") + "\"}");
			});
			server.start();

			CodingAgentOperations store = CodingAgentOperations.fileCredentialStore(tempDir.resolve("auth.json"), null);
			URI base = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
			CodingAgentOperations auth = CodingAgentOperations.chatGptAuth(store, base, "test-client");
			CodingAgentOperations.ChatGptDeviceCode device = auth.chatGptBeginLogin();
			assertEquals("ABCD-EFGH", device.userCode);
			auth.chatGptCompleteLogin(device);
			assertTrue(auth.chatGptHasCredential());

			CodingAgentOperations.ChatGptToken refreshed = auth.chatGptResolveToken();
			assertEquals("access-2", refreshed.accessToken);
			assertEquals("account-123", refreshed.accountId);
			assertEquals(2, tokenExchanges.get());

			auth.chatGptLogout();
			assertFalse(auth.chatGptHasCredential());
		} finally {
			server.stop(0);
		}
	}

	private static String jwt(String accountId) throws IOException {
		String header = Base64.getUrlEncoder().withoutPadding().encodeToString("{}".getBytes(StandardCharsets.UTF_8));
		String claims = CodingAgentOperations.jsonObject()
				.set("https://api.openai.com/auth", CodingAgentOperations.jsonObject().put("chatgpt_account_id", accountId))
				.toString();
		return header + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(claims.getBytes(StandardCharsets.UTF_8)) + ".signature";
	}

	private static void respond(HttpExchange exchange, int status, String body) throws IOException {
		exchange.getRequestBody().readAllBytes();
		byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
		exchange.getResponseHeaders().add("Content-Type", "application/json");
		exchange.sendResponseHeaders(status, bytes.length);
		exchange.getResponseBody().write(bytes);
		exchange.close();
	}
}
