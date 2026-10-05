package com.quaxt.claudia.terminal;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Terminal cell widths following wcwidth conventions: combining and format
 * characters occupy no cells, East Asian wide/fullwidth characters and emoji
 * presentation characters occupy two, and control characters report -1.
 */
public final class Cells {
    /** Inclusive start/end pairs of Unicode 15 East_Asian_Width W and F ranges. */
    private static final int[] WIDE = {
        0x1100, 0x115F, 0x231A, 0x231B, 0x2329, 0x232A, 0x23E9, 0x23EC, 0x23F0, 0x23F0,
        0x23F3, 0x23F3, 0x25FD, 0x25FE, 0x2614, 0x2615, 0x2648, 0x2653, 0x267F, 0x267F,
        0x2693, 0x2693, 0x26A1, 0x26A1, 0x26AA, 0x26AB, 0x26BD, 0x26BE, 0x26C4, 0x26C5,
        0x26CE, 0x26CE, 0x26D4, 0x26D4, 0x26EA, 0x26EA, 0x26F2, 0x26F3, 0x26F5, 0x26F5,
        0x26FA, 0x26FA, 0x26FD, 0x26FD, 0x2705, 0x2705, 0x270A, 0x270B, 0x2728, 0x2728,
        0x274C, 0x274C, 0x274E, 0x274E, 0x2753, 0x2755, 0x2757, 0x2757, 0x2795, 0x2797,
        0x27B0, 0x27B0, 0x27BF, 0x27BF, 0x2B1B, 0x2B1C, 0x2B50, 0x2B50, 0x2B55, 0x2B55,
        0x2E80, 0x303E, 0x3041, 0x33FF, 0x3400, 0x4DBF, 0x4E00, 0x9FFF, 0xA000, 0xA4CF,
        0xA960, 0xA97F, 0xAC00, 0xD7A3, 0xF900, 0xFAFF, 0xFE10, 0xFE19, 0xFE30, 0xFE6F,
        0xFF00, 0xFF60, 0xFFE0, 0xFFE6, 0x16FE0, 0x16FE4, 0x16FF0, 0x16FF1, 0x17000, 0x18D08,
        0x1AFF0, 0x1B2FF, 0x1F004, 0x1F004, 0x1F0CF, 0x1F0CF, 0x1F18E, 0x1F18E, 0x1F191, 0x1F19A,
        0x1F200, 0x1F202, 0x1F210, 0x1F23B, 0x1F240, 0x1F248, 0x1F250, 0x1F251, 0x1F260, 0x1F265,
        0x1F300, 0x1F320, 0x1F32D, 0x1F335, 0x1F337, 0x1F37C, 0x1F37E, 0x1F393, 0x1F3A0, 0x1F3CA,
        0x1F3CF, 0x1F3D3, 0x1F3E0, 0x1F3F0, 0x1F3F4, 0x1F3F4, 0x1F3F8, 0x1F43E, 0x1F440, 0x1F440,
        0x1F442, 0x1F4FC, 0x1F4FF, 0x1F53D, 0x1F54B, 0x1F54E, 0x1F550, 0x1F567, 0x1F57A, 0x1F57A,
        0x1F595, 0x1F596, 0x1F5A4, 0x1F5A4, 0x1F5FB, 0x1F64F, 0x1F680, 0x1F6C5, 0x1F6CC, 0x1F6CC,
        0x1F6D0, 0x1F6D2, 0x1F6D5, 0x1F6D7, 0x1F6DC, 0x1F6DF, 0x1F6EB, 0x1F6EC, 0x1F6F4, 0x1F6FC,
        0x1F7E0, 0x1F7EB, 0x1F7F0, 0x1F7F0, 0x1F90C, 0x1F93A, 0x1F93C, 0x1F945, 0x1F947, 0x1F9FF,
        0x1FA70, 0x1FAFF, 0x20000, 0x2FFFD, 0x30000, 0x3FFFD
    };

    private Cells() {}

    /** Cells occupied by one code point: -1 for controls, otherwise 0, 1, or 2. */
    public static int width(int codePoint) {
        if (codePoint == 0) return 0;
        if (codePoint < 32 || (codePoint >= 0x7f && codePoint < 0xa0)) return -1;
        if (codePoint < 0x300) return 1;
        int type = Character.getType(codePoint);
        if (type == Character.NON_SPACING_MARK
                || type == Character.ENCLOSING_MARK
                || type == Character.LINE_SEPARATOR
                || type == Character.PARAGRAPH_SEPARATOR
                || (type == Character.FORMAT && codePoint != 0x600 && codePoint != 0x6dd)
                || (codePoint >= 0x1160 && codePoint <= 0x11FF)
                || (codePoint >= 0xD7B0 && codePoint <= 0xD7FF)) {
            return 0;
        }
        return isWide(codePoint) ? 2 : 1;
    }

    private static boolean isWide(int codePoint) {
        if (codePoint < WIDE[0] || codePoint > WIDE[WIDE.length - 1]) return false;
        int index = Arrays.binarySearch(WIDE, codePoint);
        // An exact hit is a range bound; otherwise an odd insertion point lies inside a range.
        return index >= 0 || ((-index - 1) & 1) == 1;
    }

    /** Cells occupied by plain text; control characters count as zero. */
    public static int width(CharSequence plain) {
        int width = 0;
        for (int index = 0; index < plain.length(); ) {
            int codePoint = Character.codePointAt(plain, index);
            width += Math.max(0, width(codePoint));
            index += Character.charCount(codePoint);
        }
        return width;
    }

    /** The longest prefix of plain text that fits in the given number of cells. */
    public static String truncate(String plain, int columns) {
        if (columns <= 0) return "";
        StringBuilder output = new StringBuilder();
        int width = 0;
        for (int index = 0; index < plain.length(); ) {
            int codePoint = plain.codePointAt(index);
            int cells = Math.max(0, width(codePoint));
            if (width + cells > columns) break;
            output.appendCodePoint(codePoint);
            width += cells;
            index += Character.charCount(codePoint);
        }
        return output.toString();
    }

    /**
     * Splits plain text into consecutive segments of at most {@code columns}
     * cells. Zero-width characters stay with the preceding character.
     */
    public static List<String> wrap(String plain, int columns) {
        int limit = Math.max(1, columns);
        List<String> parts = new ArrayList<>();
        StringBuilder part = new StringBuilder();
        int width = 0;
        for (int index = 0; index < plain.length(); ) {
            int codePoint = plain.codePointAt(index);
            int cells = Math.max(0, width(codePoint));
            if (!part.isEmpty() && width + cells > limit) {
                parts.add(part.toString());
                part.setLength(0);
                width = 0;
            }
            part.appendCodePoint(codePoint);
            width += cells;
            index += Character.charCount(codePoint);
        }
        if (!part.isEmpty() || parts.isEmpty()) parts.add(part.toString());
        return parts;
    }
}
