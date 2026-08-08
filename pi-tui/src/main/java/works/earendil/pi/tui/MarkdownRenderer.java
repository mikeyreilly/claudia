package works.earendil.pi.tui;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Compact ANSI formatter for the Markdown constructs used in agent replies. */
public final class MarkdownRenderer {
	private static final String RESET = "\u001b[0m";
	private static final String BOLD = "\u001b[1m";
	private static final String DIM = "\u001b[2m";
	private static final String CYAN = "\u001b[36m";
	private static final Pattern BOLD_PATTERN = Pattern.compile("\\*\\*(.+?)\\*\\*");
	private static final Pattern CODE_PATTERN = Pattern.compile("`([^`]+)`");

	private MarkdownRenderer() {}

	public static String render(String markdown) {
		return render(markdown, Theme.DARK);
	}

	public static String render(String markdown, Theme theme) {
		StringBuilder output = new StringBuilder();
		for (String line : markdown.split("\\R", -1)) {
			if (line.startsWith("### ")) output.append(theme.strong()).append(line.substring(4)).append(theme.reset());
			else if (line.startsWith("## ")) output.append(theme.heading()).append(line.substring(3)).append(theme.reset());
			else if (line.startsWith("# ")) output.append(theme.heading()).append(line.substring(2)).append(theme.reset());
			else if (line.startsWith("> ")) output.append(theme.muted()).append(line.substring(2)).append(theme.reset());
			else output.append(inline(line, theme));
			output.append('\n');
		}
		return output.isEmpty() ? "" : output.substring(0, output.length() - 1);
	}

	private static String inline(String text, Theme theme) {
		return replace(CODE_PATTERN, replace(BOLD_PATTERN, text, theme.strong(), theme.reset()), theme.code(), theme.reset());
	}

	private static String replace(Pattern pattern, String input, String style, String reset) {
		Matcher matcher = pattern.matcher(input);
		StringBuilder output = new StringBuilder();
		while (matcher.find()) {
			matcher.appendReplacement(output, Matcher.quoteReplacement(style + matcher.group(1) + reset));
		}
		matcher.appendTail(output);
		return output.toString();
	}
}
