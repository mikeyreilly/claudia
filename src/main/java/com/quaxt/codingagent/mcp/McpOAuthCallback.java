package com.quaxt.codingagent.mcp;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** One loopback HTTP receiver for an OAuth authorization-code redirect. */
final class McpOAuthCallback implements AutoCloseable {
	private final URI redirectUri;
	private final String expectedState;
	private final HttpServer server;
	private final ExecutorService executor;
	private final CompletableFuture<String> code = new CompletableFuture<>();

	McpOAuthCallback(URI redirectUri, String expectedState) throws IOException {
		this.redirectUri = redirectUri;
		this.expectedState = expectedState;
		validateRedirectUri(redirectUri);
		int port = redirectUri.getPort() >= 0 ? redirectUri.getPort() : 80;
		InetAddress loopback = InetAddress.getByName(
				redirectUri.getHost().equalsIgnoreCase("localhost") ? "127.0.0.1" : redirectUri.getHost());
		server = HttpServer.create(new InetSocketAddress(loopback, port), 0);
		executor = Executors.newVirtualThreadPerTaskExecutor();
		server.setExecutor(executor);
		server.createContext("/", this::handle);
		server.start();
	}

	String await(Duration timeout) throws IOException, InterruptedException {
		try {
			return code.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
		} catch (TimeoutException error) {
			throw new IOException("OAuth authorization timed out after " + timeout.toMinutes() + " minutes", error);
		} catch (ExecutionException error) {
			Throwable cause = error.getCause();
			if (cause instanceof IOException io) throw io;
			throw new IOException(cause == null ? "OAuth authorization failed" : cause.getMessage(), cause);
		}
	}

	private void handle(HttpExchange exchange) throws IOException {
		try (exchange) {
			if (!exchange.getRequestMethod().equals("GET")
					|| !exchange.getRequestURI().getPath().equals(callbackPath())) {
				respond(exchange, 404, page("Not found", false));
				return;
			}
			Map<String, String> parameters = query(exchange.getRequestURI().getRawQuery());
			String state = parameters.get("state");
			if (state == null || !constantTimeEquals(expectedState, state)) {
				respond(exchange, 400, page("The OAuth state was missing or invalid. Return to codingagent and try again.", false));
				return;
			}
			String oauthError = parameters.get("error");
			if (oauthError != null) {
				String description = parameters.getOrDefault("error_description", oauthError);
				respond(exchange, 200, page(description, false));
				code.completeExceptionally(new IOException("OAuth authorization was rejected: " + description));
				return;
			}
			String authorizationCode = parameters.get("code");
			if (authorizationCode == null || authorizationCode.isBlank()) {
				respond(exchange, 400, page("No authorization code was returned. Return to codingagent and try again.", false));
				return;
			}
			if (code.isDone()) {
				respond(exchange, 400, page("This OAuth authorization has already been completed.", false));
				return;
			}
			respond(exchange, 200, page("Authorization complete. You can close this window and return to codingagent.", true));
			code.complete(authorizationCode);
		}
	}

	private String callbackPath() {
		return redirectUri.getPath() == null || redirectUri.getPath().isEmpty() ? "/" : redirectUri.getPath();
	}

	private static Map<String, String> query(String rawQuery) {
		LinkedHashMap<String, String> values = new LinkedHashMap<>();
		if (rawQuery == null || rawQuery.isEmpty()) return values;
		for (String part : rawQuery.split("&")) {
			int separator = part.indexOf('=');
			String rawName = separator < 0 ? part : part.substring(0, separator);
			String rawValue = separator < 0 ? "" : part.substring(separator + 1);
			values.put(
					URLDecoder.decode(rawName, StandardCharsets.UTF_8),
					URLDecoder.decode(rawValue, StandardCharsets.UTF_8));
		}
		return values;
	}

	private static void respond(HttpExchange exchange, int status, String body) throws IOException {
		byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
		exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
		exchange.getResponseHeaders().set("Cache-Control", "no-store");
		exchange.getResponseHeaders().set(
				"Content-Security-Policy", "default-src 'none'; style-src 'unsafe-inline'; base-uri 'none'; frame-ancestors 'none'");
		exchange.sendResponseHeaders(status, bytes.length);
		exchange.getResponseBody().write(bytes);
	}

	private static String page(String message, boolean success) {
		String title = success ? "Authorization complete" : "Authorization failed";
		String color = success ? "#16803c" : "#b42318";
		return "<!doctype html><html><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width\">"
				+ "<title>" + title + "</title><style>body{font:16px system-ui;margin:4rem auto;max-width:42rem;padding:0 1.5rem}"
				+ "h1{color:" + color + "}</style></head><body><h1>" + title + "</h1><p>" + escape(message)
				+ "</p></body></html>";
	}

	private static String escape(String value) {
		return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
	}

	private static boolean constantTimeEquals(String expected, String actual) {
		return MessageDigest.isEqual(
				expected.getBytes(StandardCharsets.UTF_8), actual.getBytes(StandardCharsets.UTF_8));
	}

	private static void validateRedirectUri(URI uri) {
		if (uri == null || !uri.isAbsolute() || !uri.getScheme().equalsIgnoreCase("http") || uri.getHost() == null) {
			throw new IllegalArgumentException("MCP OAuth redirectUri must be an absolute loopback HTTP URL");
		}
		String host = uri.getHost();
		if (!(host.equalsIgnoreCase("localhost") || isLoopback(host))) {
			throw new IllegalArgumentException("MCP OAuth redirectUri must use localhost or a loopback IP address");
		}
		if (uri.getFragment() != null) throw new IllegalArgumentException("MCP OAuth redirectUri must not contain a fragment");
	}

	private static boolean isLoopback(String host) {
		try {
			return InetAddress.getByName(host).isLoopbackAddress();
		} catch (IOException error) {
			return false;
		}
	}

	@Override
	public void close() {
		server.stop(0);
		executor.shutdownNow();
	}
}
