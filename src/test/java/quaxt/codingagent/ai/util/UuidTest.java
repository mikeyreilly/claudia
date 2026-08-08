package works.earendil.pi.ai.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

class UuidTest {
	@Test
	void generatesValidV7Format() {
		String id = Uuid.uuidv7();
		assertTrue(id.matches("[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}"), id);
	}

	@Test
	void generatesUniqueMonotonicIds() {
		Set<String> seen = new HashSet<>();
		String previous = "";
		for (int i = 0; i < 10_000; i++) {
			String id = Uuid.uuidv7();
			assertTrue(seen.add(id), "duplicate: " + id);
			assertTrue(id.compareTo(previous) > 0, "not monotonic: " + previous + " -> " + id);
			previous = id;
		}
		assertEquals(10_000, seen.size());
	}
}
