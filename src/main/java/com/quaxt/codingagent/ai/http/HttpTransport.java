package com.quaxt.codingagent.ai.http;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import com.quaxt.codingagent.ai.util.AbortSignal;

/**
 * Provider HTTP transport on java.net.http.HttpClient. Honors HTTPS_PROXY /
 * HTTP_PROXY / NO_PROXY environment variables (basic port of
 * packages/ai/src/utils/node-http-proxy.ts) and integrates AbortSignal
 * cancellation.
 */
public final class HttpTransport {
	public static final int DEFAULT_TIMEOUT_MS = 600_000;

	private static final HttpClient CLIENT = HttpClient.newBuilder()
			.followRedirects(HttpClient.Redirect.NORMAL)
			.connectTimeout(Duration.ofSeconds(30))
			.proxy(proxySelectorFromEnv())
			.build();

	private HttpTransport() {}

	/** Streaming response: status, headers, and an open body stream. */
	public record Response(int status, Map<String, String> headers, InputStream body) implements AutoCloseable {
		@Override
		public void close() {
			try {
				body.close();
			} catch (IOException ignored) {
			}
		}
	}

	/**
	 * POST a JSON body and return the streaming response. Throws HttpException
	 * for non-2xx status (body fully read), AbortedException when the signal
	 * fires, and IOException for transport failures.
	 */
	public static Response postJson(
			String url, Map<String, String> headers, byte[] body, Integer timeoutMs, AbortSignal signal)
			throws IOException {
		return post(url, "application/json", headers, body, timeoutMs, signal);
	}

	/** POST an URL-encoded form body and return a streaming response. */
	public static Response postForm(
			String url, Map<String, String> headers, byte[] body, Integer timeoutMs, AbortSignal signal)
			throws IOException {
		return post(url, "application/x-www-form-urlencoded", headers, body, timeoutMs, signal);
	}

	private static Response post(
			String url,
			String contentType,
			Map<String, String> headers,
			byte[] body,
			Integer timeoutMs,
			AbortSignal signal)
			throws IOException {
		HttpRequest.Builder builder = HttpRequest.newBuilder()
				.uri(URI.create(url))
				.timeout(Duration.ofMillis(timeoutMs != null ? timeoutMs : DEFAULT_TIMEOUT_MS))
				.POST(HttpRequest.BodyPublishers.ofByteArray(body));
		builder.header("content-type", contentType);
		for (Map.Entry<String, String> header : headers.entrySet()) {
			if (header.getValue() != null && !header.getKey().equalsIgnoreCase("content-type")) {
				builder.header(header.getKey(), header.getValue());
			}
		}
		return send(builder.build(), signal);
	}

	public static Response get(String url, Map<String, String> headers, Integer timeoutMs, AbortSignal signal)
			throws IOException {
		HttpRequest.Builder builder = HttpRequest.newBuilder()
				.uri(URI.create(url))
				.timeout(Duration.ofMillis(timeoutMs != null ? timeoutMs : DEFAULT_TIMEOUT_MS))
				.GET();
		for (Map.Entry<String, String> header : headers.entrySet()) {
			if (header.getValue() != null) {
				builder.header(header.getKey(), header.getValue());
			}
		}
		return send(builder.build(), signal);
	}

	private static Response send(HttpRequest request, AbortSignal signal) throws IOException {
		if (signal != null && signal.isAborted()) {
			throw new AbortedException();
		}
		CompletableFuture<HttpResponse<InputStream>> future =
				CLIENT.sendAsync(request, HttpResponse.BodyHandlers.ofInputStream());
		if (signal != null) {
			signal.onAbort(() -> future.cancel(true));
		}
		HttpResponse<InputStream> response;
		try {
			response = future.get();
		} catch (CancellationException e) {
			throw new AbortedException();
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new AbortedException();
		} catch (ExecutionException e) {
			Throwable cause = e.getCause();
			if (signal != null && signal.isAborted()) {
				throw new AbortedException();
			}
			if (cause instanceof IOException io) {
				throw io;
			}
			throw new IOException(cause);
		}

		Map<String, String> responseHeaders = new LinkedHashMap<>();
		response.headers().map().forEach((key, values) -> {
			if (!values.isEmpty()) {
				responseHeaders.put(key.toLowerCase(Locale.ROOT), values.getFirst());
			}
		});

		if (response.statusCode() < 200 || response.statusCode() >= 300) {
			String errorBody;
			try (InputStream stream = response.body()) {
				errorBody = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
			}
			throw new HttpException(response.statusCode(), errorBody);
		}
		InputStream bodyStream = response.body();
		if (signal != null) {
			signal.onAbort(() -> {
				try {
					bodyStream.close();
				} catch (IOException ignored) {
				}
			});
		}
		return new Response(response.statusCode(), responseHeaders, bodyStream);
	}

	/** Thrown when a request is cancelled via AbortSignal. */
	public static final class AbortedException extends IOException {
		public AbortedException() {
			super("Aborted");
		}
	}

	private static ProxySelector proxySelectorFromEnv() {
		String httpsProxy = envAnyCase("https_proxy");
		String httpProxy = envAnyCase("http_proxy");
		if (httpsProxy.isEmpty() && httpProxy.isEmpty()) {
			return ProxySelector.getDefault();
		}
		String chosen = !httpsProxy.isEmpty() ? httpsProxy : httpProxy;
		try {
			URI proxyUri = URI.create(chosen);
			int port = proxyUri.getPort() != -1 ? proxyUri.getPort() : 80;
			return ProxySelector.of(new InetSocketAddress(proxyUri.getHost(), port));
		} catch (IllegalArgumentException e) {
			return ProxySelector.getDefault();
		}
	}

	private static String envAnyCase(String key) {
		String lower = System.getenv(key.toLowerCase(Locale.ROOT));
		if (lower != null && !lower.isEmpty()) {
			return lower;
		}
		String upper = System.getenv(key.toUpperCase(Locale.ROOT));
		return upper != null ? upper : "";
	}
}
