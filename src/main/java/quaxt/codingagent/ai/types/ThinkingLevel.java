package works.earendil.pi.ai.types;

/**
 * Thinking/reasoning effort levels. OFF corresponds to ModelThinkingLevel "off";
 * the rest mirror ThinkingLevel in packages/ai/src/types.ts.
 */
public enum ThinkingLevel {
	OFF("off"),
	MINIMAL("minimal"),
	LOW("low"),
	MEDIUM("medium"),
	HIGH("high"),
	XHIGH("xhigh"),
	MAX("max");

	private final String wire;

	ThinkingLevel(String wire) {
		this.wire = wire;
	}

	public String wire() {
		return wire;
	}

	public static ThinkingLevel fromWire(String value) {
		for (ThinkingLevel level : values()) {
			if (level.wire.equals(value)) {
				return level;
			}
		}
		throw new IllegalArgumentException("Unknown thinking level: " + value);
	}
}
