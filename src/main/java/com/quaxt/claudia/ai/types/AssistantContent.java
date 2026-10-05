package com.quaxt.claudia.ai.types;

/** Content allowed in assistant messages: text, thinking, and tool calls. */
public sealed interface AssistantContent extends ContentBlock permits TextContent, ThinkingContent, ToolCall {}
