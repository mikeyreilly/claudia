package works.earendil.pi.ai.types;

/** Prompt cache retention preference. Mirrors CacheRetention in packages/ai/src/types.ts. */
public enum CacheRetention {
	NONE("none"),
	SHORT("short"),
	LONG("long");

	private final String wire;

	CacheRetention(String wire) {
		this.wire = wire;
	}

	public String wire() {
		return wire;
	}
}
