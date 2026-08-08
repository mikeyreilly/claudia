package works.earendil.pi.agent;

/** Result of replacing historical model context with a durable summary checkpoint. */
public record CompactionResult(String summary, long tokensBefore, long estimatedTokensAfter) {}
