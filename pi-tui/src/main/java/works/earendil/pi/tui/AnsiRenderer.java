package works.earendil.pi.tui;

import java.util.ArrayList;
import java.util.List;

/**
 * Differential line renderer for terminal regions. Callers write the returned
 * ANSI sequence themselves, allowing it to be used with JLine or another
 * terminal abstraction.
 */
public final class AnsiRenderer {
	private List<String> previousLines = List.of();

	/** Returns the minimal line updates needed to replace the prior frame. */
	public String render(List<String> lines) {
		List<String> next = List.copyOf(lines);
		StringBuilder output = new StringBuilder();
		int common = Math.min(previousLines.size(), next.size());
		for (int index = 0; index < common; index++) {
			if (!previousLines.get(index).equals(next.get(index))) {
				moveTo(output, index);
				output.append("\u001b[2K").append(next.get(index));
			}
		}
		for (int index = common; index < next.size(); index++) {
			moveTo(output, index);
			output.append("\u001b[2K").append(next.get(index));
		}
		for (int index = next.size(); index < previousLines.size(); index++) {
			moveTo(output, index);
			output.append("\u001b[2K");
		}
		previousLines = next;
		return output.toString();
	}

	/** Resets the baseline, for example after the terminal scrolls externally. */
	public void reset() {
		previousLines = List.of();
	}

	public List<String> previousLines() {
		return new ArrayList<>(previousLines);
	}

	private static void moveTo(StringBuilder output, int zeroBasedLine) {
		output.append("\u001b[").append(zeroBasedLine + 1).append(";1H");
	}
}
