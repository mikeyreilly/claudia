package works.earendil.pi.ai.types;

/**
 * Why an assistant message stopped. Mirrors StopReason in packages/ai/src/types.ts.
 */
public enum StopReason {
	PENDING("pending"),
	STOP("stop"),
	LENGTH("length"),
	TOOL_USE("toolUse"),
	ERROR("error"),
	ABORTED("aborted"),
	DEFERRED("deferred");

	private final String wire;

	StopReason(String wire) {
		this.wire = wire;
	}

	public String wire() {
		return wire;
	}

	public static StopReason fromWire(String value) {
		for (StopReason reason : values()) {
			if (reason.wire.equals(value)) {
				return reason;
			}
		}
		throw new IllegalArgumentException("Unknown stop reason: " + value);
	}
}
