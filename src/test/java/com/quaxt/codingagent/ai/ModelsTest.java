package com.quaxt.codingagent.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import com.quaxt.codingagent.ai.types.Model;
import com.quaxt.codingagent.ai.types.ModelCost;
import com.quaxt.codingagent.ai.types.ThinkingLevel;
import com.quaxt.codingagent.ai.types.Usage;

class ModelsTest {
	private static Model model(ModelCost cost) {
		return Model.builder()
				.id("m")
				.api("test")
				.provider("p")
				.baseUrl("https://example.com")
				.cost(cost)
				.build();
	}

	@Test
	void calculatesBaseCost() {
		Model m = model(new ModelCost(3, 15, 0.3, 3.75));
		Usage usage = new Usage();
		usage.input = 1_000_000;
		usage.output = 2_000_000;
		usage.cacheRead = 1_000_000;
		usage.cacheWrite = 1_000_000;
		Models.calculateCost(m, usage);
		assertEquals(3.0, usage.cost.input, 1e-9);
		assertEquals(30.0, usage.cost.output, 1e-9);
		assertEquals(0.3, usage.cost.cacheRead, 1e-9);
		assertEquals(3.75, usage.cost.cacheWrite, 1e-9);
		assertEquals(37.05, usage.cost.total, 1e-9);
	}

	@Test
	void appliesTierPricingWhenInputExceedsThreshold() {
		Model m = model(new ModelCost(1, 2, 0.1, 0.2,
				List.of(new ModelCost.Tier(200_000, 2, 4, 0.2, 0.4))));
		Usage usage = new Usage();
		usage.input = 300_000;
		Models.calculateCost(m, usage);
		assertEquals(0.6, usage.cost.input, 1e-9); // 2 $/M * 0.3M
	}

	@Test
	void oneHourCacheWritesCostDoubleInputRate() {
		Model m = model(new ModelCost(3, 15, 0.3, 3.75));
		Usage usage = new Usage();
		usage.cacheWrite = 1_000_000;
		usage.cacheWrite1h = 1_000_000L;
		Models.calculateCost(m, usage);
		assertEquals(6.0, usage.cost.cacheWrite, 1e-9); // 2x input rate
	}

	@Test
	void nonReasoningModelSupportsOnlyOff() {
		Model m = model(ModelCost.FREE);
		assertEquals(List.of(ThinkingLevel.OFF), Models.getSupportedThinkingLevels(m));
		assertEquals(ThinkingLevel.OFF, Models.clampThinkingLevel(m, ThinkingLevel.HIGH));
	}

	@Test
	void xhighAndMaxRequireExplicitMapping() {
		Model m = Model.builder()
				.id("r")
				.api("test")
				.provider("p")
				.baseUrl("https://example.com")
				.reasoning(true)
				.build();
		List<ThinkingLevel> levels = Models.getSupportedThinkingLevels(m);
		assertTrue(levels.contains(ThinkingLevel.HIGH));
		assertFalse(levels.contains(ThinkingLevel.XHIGH));
		assertFalse(levels.contains(ThinkingLevel.MAX));
		assertEquals(ThinkingLevel.HIGH, Models.clampThinkingLevel(m, ThinkingLevel.XHIGH));
	}

	@Test
	void nullMappingDisablesLevelAndClampsUp() {
		Map<ThinkingLevel, String> map = new EnumMap<>(ThinkingLevel.class);
		map.put(ThinkingLevel.MEDIUM, null);
		Model m = Model.builder()
				.id("r")
				.api("test")
				.provider("p")
				.baseUrl("https://example.com")
				.reasoning(true)
				.thinkingLevelMap(map)
				.build();
		List<ThinkingLevel> levels = Models.getSupportedThinkingLevels(m);
		assertFalse(levels.contains(ThinkingLevel.MEDIUM));
		assertEquals(ThinkingLevel.HIGH, Models.clampThinkingLevel(m, ThinkingLevel.MEDIUM));
	}

	@Test
	void resolvesMappedThinkingLevelsForProviderRequests() {
		Map<ThinkingLevel, String> map = new EnumMap<>(ThinkingLevel.class);
		map.put(ThinkingLevel.XHIGH, "high");
		map.put(ThinkingLevel.MAX, "max");
		Model m = Model.builder()
				.id("r")
				.api("test")
				.provider("p")
				.baseUrl("https://example.com")
				.reasoning(true)
				.thinkingLevelMap(map)
				.build();

		assertEquals("max", Models.providerThinkingLevel(m, ThinkingLevel.MAX));
		assertEquals("high", Models.providerThinkingLevel(m, ThinkingLevel.XHIGH));
		assertEquals(null, Models.providerThinkingLevel(m, ThinkingLevel.OFF));
	}
}
