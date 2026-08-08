package com.quaxt.codingagent.ai.types;

/**
 * Any message content block. Mirrors the content unions in packages/ai/src/types.ts.
 * UserContent and AssistantContent narrow the allowed blocks per message role.
 */
public sealed interface ContentBlock permits UserContent, AssistantContent {}
