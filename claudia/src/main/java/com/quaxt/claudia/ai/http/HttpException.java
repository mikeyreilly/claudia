package com.quaxt.claudia.ai.http;

import java.io.IOException;

/**
 * Non-2xx HTTP response from a provider. Message includes status and truncated
 * body (mirrors the display format of packages/ai/src/utils/error-body.ts).
 *
 * <p>Data carrier: the message composition lives in ClaudiaOperations.
 */
public class HttpException extends IOException {
	public static final int MAX_ERROR_BODY_CHARS = 4000;

	public int status;
	public String body;

	public HttpException(int status, String body, String message) {
		super(message);
		this.status = status;
		this.body = body;
	}
}
