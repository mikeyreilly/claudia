package com.quaxt.codingagent.ai.util;

import java.security.SecureRandom;

/** Time-ordered UUIDv7 generator. Port of packages/ai/src/utils/uuid.ts. */
public final class Uuid {
	private static final SecureRandom RANDOM = new SecureRandom();
	private static final Object LOCK = new Object();
	private static long lastTimestamp = Long.MIN_VALUE;
	private static long sequence;

	private Uuid() {}

	public static String uuidv7() {
		byte[] random = new byte[16];
		RANDOM.nextBytes(random);
		long timestampMs;
		long seq;
		synchronized (LOCK) {
			long now = System.currentTimeMillis();
			if (now > lastTimestamp) {
				sequence = ((random[6] & 0xFFL) << 24)
						| ((random[7] & 0xFFL) << 16)
						| ((random[8] & 0xFFL) << 8)
						| (random[9] & 0xFFL);
				lastTimestamp = now;
			} else {
				sequence = (sequence + 1) & 0xFFFFFFFFL;
				if (sequence == 0) {
					lastTimestamp++;
				}
			}
			timestampMs = lastTimestamp;
			seq = sequence;
		}

		byte[] bytes = new byte[16];
		bytes[0] = (byte) (timestampMs >>> 40);
		bytes[1] = (byte) (timestampMs >>> 32);
		bytes[2] = (byte) (timestampMs >>> 24);
		bytes[3] = (byte) (timestampMs >>> 16);
		bytes[4] = (byte) (timestampMs >>> 8);
		bytes[5] = (byte) timestampMs;
		bytes[6] = (byte) (0x70 | ((seq >>> 28) & 0x0F));
		bytes[7] = (byte) ((seq >>> 20) & 0xFF);
		bytes[8] = (byte) (0x80 | ((seq >>> 14) & 0x3F));
		bytes[9] = (byte) ((seq >>> 6) & 0xFF);
		bytes[10] = (byte) (((seq & 0x3F) << 2) | (random[10] & 0x03));
		bytes[11] = random[11];
		bytes[12] = random[12];
		bytes[13] = random[13];
		bytes[14] = random[14];
		bytes[15] = random[15];

		StringBuilder sb = new StringBuilder(36);
		for (int i = 0; i < 16; i++) {
			if (i == 4 || i == 6 || i == 8 || i == 10) {
				sb.append('-');
			}
			sb.append(Character.forDigit((bytes[i] >> 4) & 0xF, 16));
			sb.append(Character.forDigit(bytes[i] & 0xF, 16));
		}
		return sb.toString();
	}
}
