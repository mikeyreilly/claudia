package com.quaxt.codingagent.ai.providers;

import java.util.List;
import com.quaxt.codingagent.ai.Provider;
import com.quaxt.codingagent.ai.types.Model;

/**
 * Native REST/SSE carrier for the Google Generative Language (Gemini) API
 * adapter. All request/stream behavior lives in CodingAgentOperations.
 */
public final class GoogleProvider implements Provider {
	public static final String API = "google-generative-ai";

	public List<Model> models;

	public GoogleProvider(List<Model> models) {
		this.models = models;
	}
}
