package com.quaxt.codingagent.tui;

import java.io.IOException;

@FunctionalInterface
interface SuspendAction {
	void suspend() throws IOException;
}
