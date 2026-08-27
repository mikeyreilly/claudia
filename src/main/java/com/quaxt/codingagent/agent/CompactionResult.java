package com.quaxt.codingagent.agent;

/** Result of replacing historical model context with a durable summary checkpoint. */
public final class CompactionResult {
	public String summary;
	public long tokensBefore;
	public long estimatedTokensAfter;

	public CompactionResult(String summary, long tokensBefore, long estimatedTokensAfter) {
		this.summary = summary;
		this.tokensBefore = tokensBefore;
		this.estimatedTokensAfter = estimatedTokensAfter;
	}
}
