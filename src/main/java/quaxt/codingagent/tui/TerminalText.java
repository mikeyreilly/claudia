package works.earendil.pi.tui;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.jline.utils.WCWidth;

/** Width-safe terminal text helpers, including OSC 8 hyperlinks. */
public final class TerminalText {
	private static final Pattern ANSI = Pattern.compile(
			"\u001b(?:\\[[0-?]*[ -/]*[@-~]|\\][^\u0007\u001b]*(?:\u0007|\u001b\\\\))");

	private TerminalText() {}

	public static int visibleWidth(String value) {
		String plain = stripAnsi(value);
		int width = 0;
		for (int index = 0; index < plain.length(); ) {
			int codePoint = plain.codePointAt(index);
			width += Math.max(0, WCWidth.wcwidth(codePoint));
			index += Character.charCount(codePoint);
		}
		return width;
	}

	public static String truncatePlain(String value, int maximumWidth) {
		if (maximumWidth <= 0) {
			return "";
		}
		if (visibleWidth(value) <= maximumWidth) {
			return value;
		}
		String ellipsis = maximumWidth > 3 ? "..." : "";
		int targetWidth = maximumWidth - ellipsis.length();
		StringBuilder output = new StringBuilder();
		int width = 0;
		for (int index = 0; index < value.length(); ) {
			int codePoint = value.codePointAt(index);
			int codePointWidth = Math.max(0, WCWidth.wcwidth(codePoint));
			if (width + codePointWidth > targetWidth) {
				break;
			}
			output.appendCodePoint(codePoint);
			width += codePointWidth;
			index += Character.charCount(codePoint);
		}
		return output + ellipsis;
	}

	/** Hard-wraps plain text to terminal cell width while preserving explicit blank lines. */
	public static List<String> wrapPlain(String value, int maximumWidth) {
		if (maximumWidth <= 0) {
			return List.of("");
		}
		String normalized = value.replace("\r\n", "\n").replace('\r', '\n').replace("\t", "    ");
		List<String> lines = new ArrayList<>();
		for (String sourceLine : normalized.split("\n", -1)) {
			if (sourceLine.isEmpty()) {
				lines.add("");
				continue;
			}
			StringBuilder line = new StringBuilder();
			int width = 0;
			for (int index = 0; index < sourceLine.length(); ) {
				int codePoint = sourceLine.codePointAt(index);
				int codePointWidth = Math.max(0, WCWidth.wcwidth(codePoint));
				if (!line.isEmpty() && width + codePointWidth > maximumWidth) {
					lines.add(line.toString());
					line.setLength(0);
					width = 0;
				}
				line.appendCodePoint(codePoint);
				width += codePointWidth;
				index += Character.charCount(codePoint);
			}
			if (!line.isEmpty()) {
				lines.add(line.toString());
			}
		}
		return List.copyOf(lines);
	}

	public static String hyperlink(String text, String url) {
		String safeUrl = url.replace("\u001b", "").replace("\u0007", "");
		return "\u001b]8;;" + safeUrl + "\u001b\\" + text + "\u001b]8;;\u001b\\";
	}

	public static String stripAnsi(String value) {
		return ANSI.matcher(value).replaceAll("");
	}
}
