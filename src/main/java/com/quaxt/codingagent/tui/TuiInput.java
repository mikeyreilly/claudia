package com.quaxt.codingagent.tui;

import java.util.Objects;

/**
 * Normalized keyboard, mouse, and resize input carriers for Java TUI
 * components. Parsing lives in CodingAgentOperations.
 */
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

	final class Key implements TuiInput {
		public KeyType type;
		public String text;

		public Key(KeyType type, String text) {
			this.type = type;
			this.text = text;
		}

		@Override
		public boolean equals(Object other) {
			return other instanceof Key that && type == that.type && Objects.equals(text, that.text);
		}

		@Override
		public int hashCode() {
			return Objects.hash(type, text);
		}

		@Override
		public String toString() {
			return "Key[type=" + type + ", text=" + text + "]";
		}
	}

	enum MouseAction {
		PRESS,
		RELEASE,
		DRAG,
		SCROLL_UP,
		SCROLL_DOWN
	}

	final class Mouse implements TuiInput {
		public MouseAction action;
		public int button;
		public int x;
		public int y;

		public Mouse(MouseAction action, int button, int x, int y) {
			this.action = action;
			this.button = button;
			this.x = x;
			this.y = y;
		}

		@Override
		public boolean equals(Object other) {
			return other instanceof Mouse that
					&& action == that.action
					&& button == that.button
					&& x == that.x
					&& y == that.y;
		}

		@Override
		public int hashCode() {
			return Objects.hash(action, button, x, y);
		}

		@Override
		public String toString() {
			return "Mouse[action=" + action + ", button=" + button + ", x=" + x + ", y=" + y + "]";
		}
	}

	final class Resize implements TuiInput {
		public int width;
		public int height;

		public Resize(int width, int height) {
			this.width = width;
			this.height = height;
		}

		@Override
		public boolean equals(Object other) {
			return other instanceof Resize that && width == that.width && height == that.height;
		}

		@Override
		public int hashCode() {
			return Objects.hash(width, height);
		}

		@Override
		public String toString() {
			return "Resize[width=" + width + ", height=" + height + "]";
		}
	}
}
