package works.earendil.pi.ai.types;

/** Content allowed in user messages and tool results: text and images. */
public sealed interface UserContent extends ContentBlock permits TextContent, ImageContent {}
