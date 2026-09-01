package com.quaxt.codingagent.ai.http;

import java.io.IOException;
import java.io.InputStream;
import java.net.Authenticator;
import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Map;
import com.quaxt.codingagent.CodingAgentOperations;

/**
 * Provider HTTP transport state on java.net.http.HttpClient. Honors
 * HTTPS_PROXY / HTTP_PROXY environment variables (basic port of
 * packages/ai/src/utils/node-http-proxy.ts).
 *
 * <p>Pure data carrier: the request/response behavior lives in
 * CodingAgentOperations; this class only owns the shared client and the
 * response carrier.
 */
public final class HttpTransport {
	public static final int DEFAULT_TIMEOUT_MS = 600_000;

	public static final HttpClient CLIENT;

	static {
		String httpsProxy = CodingAgentOperations.envAnyCase("https_proxy");
		String httpProxy = CodingAgentOperations.envAnyCase("http_proxy");
		ProxySelector proxy;
		if (httpsProxy.isEmpty() && httpProxy.isEmpty()) {
			proxy = ProxySelector.getDefault();
		} else {
			String chosen = !httpsProxy.isEmpty() ? httpsProxy : httpProxy;
			try {
				URI proxyUri = URI.create(chosen);
				int port = proxyUri.getPort() != -1 ? proxyUri.getPort() : 80;
				proxy = ProxySelector.of(new InetSocketAddress(proxyUri.getHost(), port));
			} catch (IllegalArgumentException error) {
				proxy = ProxySelector.getDefault();
			}
		}
		Authenticator auth = Authenticator.getDefault();
		HttpClient.Builder builder = HttpClient.newBuilder()
				.followRedirects(HttpClient.Redirect.NORMAL)
				.connectTimeout(Duration.ofSeconds(30))
				.proxy(proxy);
		if (auth != null) {
			builder.authenticator(auth);
		}
		CLIENT = builder.build();
	}

	public HttpTransport() {}

	/** Streaming response: status, headers, and an open body stream. */
	public static final class Response {
		public int status;
		public Map<String, String> headers;
		public InputStream body;

		public Response(int status, Map<String, String> headers, InputStream body) {
			this.status = status;
			this.headers = headers;
			this.body = body;
		}
	}

	/** Thrown when a request is cancelled via AbortSignal. */
	public static final class AbortedException extends IOException {
		public AbortedException() {
			super("Aborted");
		}
	}
}
