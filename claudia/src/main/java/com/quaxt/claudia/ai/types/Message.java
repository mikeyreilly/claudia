package com.quaxt.claudia.ai.types;

/**
 * A conversation message: user, assistant, or tool result. Marker interface;
 * the sealed hierarchy is retained for pattern matching and serialization.
 * Role and timestamp are read via ClaudiaOperations.
 */
public sealed interface Message permits UserMessage, AssistantMessage, ToolResultMessage {}
