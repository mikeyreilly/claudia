package works.earendil.pi.tui;

/** Normalized keyboard, mouse, and resize input for Java TUI components. */
public sealed interface TuiInput {
	enum KeyType {
		CHARACTER,
		PASTE,
		UP,
		DOWN,
		LEFT,
		RIGHT,
		PAGE_UP,
		PAGE_DOWN,
		HOME,
		END,
		ENTER,
		ESCAPE,
		BACKSPACE,
		DELETE,
		TAB,
		CLEAR,
		CANCEL,
		EXIT,
		SUSPEND,
		EXPAND_TOOLS,
		TOGGLE_THINKING,
		UNKNOWN
	}

	record Key(KeyType type, String text) implements TuiInput {
		public Key(KeyType type) {
			this(type, "");
		}
	}

	enum MouseAction {
		PRESS,
		RELEASE,
		DRAG,
		SCROLL_UP,
		SCROLL_DOWN
	}

	record Mouse(MouseAction action, int button, int x, int y) implements TuiInput {}

	record Resize(int width, int height) implements TuiInput {}
}
