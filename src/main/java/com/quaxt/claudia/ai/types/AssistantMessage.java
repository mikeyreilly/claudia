package com.quaxt.claudia.ai.types;

import java.util.concurrent.CopyOnWriteArrayList;
import java.util.List;

/**
 * Assistant message. Mutable: providers build it up while streaming and event
 * consumers observe the in-progress partial (mirrors the TS design where the
 * same `partial` object is emitted with every event).
 */
public final class AssistantMessage implements Message {
	public List<AssistantContent> content = new CopyOnWriteArrayList<>();
	public String api;
	public String provider;
	public String model;
	/** Concrete response model when different from the requested model. */
	public String responseModel;
	/** Provider-specific response/message identifier, when exposed. */
	public String responseId;
	public Usage usage = new Usage();
	public StopReason stopReason = StopReason.PENDING;
	public String errorMessage;
	public String rawStopReason;
	public long timestamp = System.currentTimeMillis();

	public AssistantMessage() {}

	public AssistantMessage(String api, String provider, String model) {
		this.api = api;
		this.provider = provider;
		this.model = model;
	}
}
