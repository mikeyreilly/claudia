package works.earendil.pi.tui;

import java.io.IOException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jline.utils.NonBlockingReader;

/** Escape-sequence parser for the input subset used by pi TUI components. */
final class TuiInputReader {
	private static final long ESCAPE_TIMEOUT_MS = 25;
	private static final Pattern SGR_MOUSE = Pattern.compile("<(\\d+);(\\d+);(\\d+)([Mm])");
	private static final String PASTE_END = "\u001b[201~";

	private TuiInputReader() {}

	static TuiInput read(NonBlockingReader reader, long timeoutMs) throws IOException {
		int value = reader.read(timeoutMs);
		if (value == NonBlockingReader.READ_EXPIRED) {
			return null;
		}
		if (value == NonBlockingReader.EOF) {
			return new TuiInput.Key(TuiInput.KeyType.CANCEL);
		}
		return value == 0x1b ? readEscape(reader) : key(value);
	}

	static TuiInput parseSequence(String sequence) {
		if (sequence == null || sequence.isEmpty()) {
			return new TuiInput.Key(TuiInput.KeyType.UNKNOWN);
		}
		if (sequence.length() == 1 && sequence.charAt(0) != 0x1b) {
			return key(sequence.charAt(0));
		}
		if (sequence.equals("\u001b")) {
			return new TuiInput.Key(TuiInput.KeyType.ESCAPE);
		}
		if (sequence.startsWith("\u001b[")) {
			return csi(sequence.substring(2));
		}
		if (sequence.startsWith("\u001bO") && sequence.length() == 3) {
			return switch (sequence.charAt(2)) {
				case 'A' -> new TuiInput.Key(TuiInput.KeyType.UP);
				case 'B' -> new TuiInput.Key(TuiInput.KeyType.DOWN);
				case 'C' -> new TuiInput.Key(TuiInput.KeyType.RIGHT);
				case 'D' -> new TuiInput.Key(TuiInput.KeyType.LEFT);
				default -> new TuiInput.Key(TuiInput.KeyType.UNKNOWN);
			};
		}
		return new TuiInput.Key(TuiInput.KeyType.UNKNOWN);
	}

	private static TuiInput readEscape(NonBlockingReader reader) throws IOException {
		int next = reader.read(ESCAPE_TIMEOUT_MS);
		if (next == NonBlockingReader.READ_EXPIRED || next == NonBlockingReader.EOF) {
			return new TuiInput.Key(TuiInput.KeyType.ESCAPE);
		}
		if (next == '[') {
			StringBuilder sequence = new StringBuilder();
			while (sequence.length() < 64) {
				int value = reader.read(ESCAPE_TIMEOUT_MS);
				if (value == NonBlockingReader.READ_EXPIRED || value == NonBlockingReader.EOF) {
					break;
				}
				sequence.append((char) value);
				if (value >= 0x40 && value <= 0x7e) {
					break;
				}
			}
			if (sequence.toString().equals("200~")) {
				return new TuiInput.Key(TuiInput.KeyType.PASTE, readPaste(reader));
			}
			return csi(sequence.toString());
		}
		if (next == 'O') {
			int value = reader.read(ESCAPE_TIMEOUT_MS);
			return value < 0 ? new TuiInput.Key(TuiInput.KeyType.ESCAPE)
					: parseSequence("\u001bO" + (char) value);
		}
		return key(next);
	}

	private static String readPaste(NonBlockingReader reader) throws IOException {
		StringBuilder content = new StringBuilder();
		StringBuilder suffix = new StringBuilder();
		while (true) {
			int value = reader.read();
			if (value == NonBlockingReader.EOF) {
				content.append(suffix);
				return content.toString();
			}
			suffix.append((char) value);
			while (!PASTE_END.startsWith(suffix.toString())) {
				content.append(suffix.charAt(0));
				suffix.deleteCharAt(0);
			}
			if (suffix.toString().equals(PASTE_END)) {
				return content.toString();
			}
		}
	}

	private static TuiInput csi(String sequence) {
		return switch (sequence) {
			case "A" -> new TuiInput.Key(TuiInput.KeyType.UP);
			case "B" -> new TuiInput.Key(TuiInput.KeyType.DOWN);
			case "C" -> new TuiInput.Key(TuiInput.KeyType.RIGHT);
			case "D" -> new TuiInput.Key(TuiInput.KeyType.LEFT);
			case "H", "1~", "7~" -> new TuiInput.Key(TuiInput.KeyType.HOME);
			case "F", "4~", "8~" -> new TuiInput.Key(TuiInput.KeyType.END);
			case "3~" -> new TuiInput.Key(TuiInput.KeyType.DELETE);
			case "5~" -> new TuiInput.Key(TuiInput.KeyType.PAGE_UP);
			case "6~" -> new TuiInput.Key(TuiInput.KeyType.PAGE_DOWN);
			default -> mouse(sequence);
		};
	}

	private static TuiInput mouse(String sequence) {
		Matcher matcher = SGR_MOUSE.matcher(sequence);
		if (!matcher.matches()) {
			return new TuiInput.Key(TuiInput.KeyType.UNKNOWN);
		}
		int code = Integer.parseInt(matcher.group(1));
		int x = Integer.parseInt(matcher.group(2));
		int y = Integer.parseInt(matcher.group(3));
		if ((code & 64) != 0) {
			return new TuiInput.Mouse(
					(code & 1) == 0 ? TuiInput.MouseAction.SCROLL_UP : TuiInput.MouseAction.SCROLL_DOWN,
					code & 3,
					x,
					y);
		}
		TuiInput.MouseAction action;
		if (matcher.group(4).equals("m") || (code & 3) == 3) {
			action = TuiInput.MouseAction.RELEASE;
		} else if ((code & 32) != 0) {
			action = TuiInput.MouseAction.DRAG;
		} else {
			action = TuiInput.MouseAction.PRESS;
		}
		return new TuiInput.Mouse(action, code & 3, x, y);
	}

	private static TuiInput key(int value) {
		TuiInput.KeyType applicationType = Keybindings.appKeyType(value);
		if (applicationType != null) {
			return new TuiInput.Key(applicationType);
		}
		return switch (value) {
			case 8, 127 -> new TuiInput.Key(TuiInput.KeyType.BACKSPACE);
			case 9 -> new TuiInput.Key(TuiInput.KeyType.TAB);
			case 10, 13 -> new TuiInput.Key(TuiInput.KeyType.ENTER);
			case 14 -> new TuiInput.Key(TuiInput.KeyType.DOWN);
			case 16 -> new TuiInput.Key(TuiInput.KeyType.UP);
			case 21 -> new TuiInput.Key(TuiInput.KeyType.CLEAR);
			default -> value >= 32
					? new TuiInput.Key(TuiInput.KeyType.CHARACTER, Character.toString(value))
					: new TuiInput.Key(TuiInput.KeyType.UNKNOWN);
		};
	}
}
