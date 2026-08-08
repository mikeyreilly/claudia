package works.earendil.pi.tui;

import java.io.IOException;

@FunctionalInterface
interface SuspendAction {
	void suspend() throws IOException;
}
