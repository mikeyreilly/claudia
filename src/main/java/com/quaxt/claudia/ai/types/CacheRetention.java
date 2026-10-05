package com.quaxt.claudia.ai.types;

/** Prompt cache retention preference. Mirrors CacheRetention in packages/ai/src/types.ts. */
public enum CacheRetention {
	NONE("none"),
	SHORT("short"),
	LONG("long");

	public String wire;

	CacheRetention(String wire) {
		this.wire = wire;
	}
}
