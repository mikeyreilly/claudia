package org.jline.terminal.impl.jni.win;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

@EnabledOnOs(OS.WINDOWS)
class ShiftAwareNativeWinSysTerminalTest {
	@Test
	void convertsOnlyShiftEnterToTheInsertNewlineCharacter() {
		assertEquals("\n", ShiftAwareNativeWinSysTerminal.shiftedEnterSequence((short) 0x0d, 0x01));
		assertNull(ShiftAwareNativeWinSysTerminal.shiftedEnterSequence((short) 0x0d, 0));
		assertNull(ShiftAwareNativeWinSysTerminal.shiftedEnterSequence((short) 0x0d, 0x04));
		assertNull(ShiftAwareNativeWinSysTerminal.shiftedEnterSequence((short) 0x41, 0x01));
	}
}
