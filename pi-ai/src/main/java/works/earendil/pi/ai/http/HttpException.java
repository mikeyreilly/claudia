package works.earendil.pi.ai.http;

/**
 * Non-2xx HTTP response from a provider. Message includes status and truncated
 * body (mirrors the display format of packages/ai/src/utils/error-body.ts).
 */
public class HttpException extends RuntimeException {
	public static final int MAX_ERROR_BODY_CHARS = 4000;

	private final int status;
	private final String body;

	public HttpException(int status, String body) {
		super(compose(status, body));
		this.status = status;
		this.body = body;
	}

	public int status() {
		return status;
	}

	public String body() {
		return body;
	}

	private static String compose(int status, String body) {
		String trimmed = body == null ? "" : body.trim();
		if (trimmed.isEmpty()) {
			return status + " status code (no body)";
		}
		return status + ": " + truncate(trimmed, MAX_ERROR_BODY_CHARS);
	}

	public static String truncate(String text, int maxChars) {
		if (text.length() <= maxChars) {
			return text;
		}
		return text.substring(0, maxChars) + "... [truncated " + (text.length() - maxChars) + " chars]";
	}
}
