package com.quaxt.codingagent.ai;

/**
 * Marker for an LLM provider carrier. Descriptive metadata, the model list, and
 * the streaming entry point are exposed by the static operations in
 * CodingAgentOperations, which dispatch over dedicated provider carriers.
 *
 * <p>Contract for the stream operation: once invoked, failures must be encoded
 * in the returned stream (Error event with stopReason ERROR/ABORTED), not
 * thrown.
 */
public interface Provider {}
