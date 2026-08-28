package com.quaxt.codingagent.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import com.quaxt.codingagent.CodingAgentOperations;
import com.quaxt.codingagent.ai.types.Model;
import com.quaxt.codingagent.ai.types.ModelCost;
import com.quaxt.codingagent.ai.types.ThinkingLevel;
import com.quaxt.codingagent.ai.types.Usage;

class ModelsTest {
	private static Model model(ModelCost cost) {
		Model model = new Model();
		model.id = "m";
		model.name = "m";
		model.api = "test";
		model.provider = "p";
		model.baseUrl = "https://example.com";
		model.cost = cost;
		return model;
	}

	private static Model reasoningModel(Map<ThinkingLevel, String> thinkingLevelMap) {
		Model model = model(ModelCost.FREE);
		model.id = "r";
		model.name = "r";
		model.reasoning = true;
		model.thinkingLevelMap = thinkingLevelMap;
		return model;
	}

	@Test
	void calculatesBaseCost() {
		Model m = model(new ModelCost(3, 15, 0.3, 3.75, List.of()));
		Usage usage = new Usage();
		usage.input = 1_000_000;
		usage.output = 2_000_000;
		usage.cacheRead = 1_000_000;
		usage.cacheWrite = 1_000_000;
		CodingAgentOperations.calculateCost(m, usage);
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
		CodingAgentOperations.calculateCost(m, usage);
		assertEquals(0.6, usage.cost.input, 1e-9); // 2 $/M * 0.3M
	}

	@Test
	void oneHourCacheWritesCostDoubleInputRate() {
		Model m = model(new ModelCost(3, 15, 0.3, 3.75, List.of()));
		Usage usage = new Usage();
		usage.cacheWrite = 1_000_000;
		usage.cacheWrite1h = 1_000_000L;
		CodingAgentOperations.calculateCost(m, usage);
		assertEquals(6.0, usage.cost.cacheWrite, 1e-9); // 2x input rate
	}

	@Test
	void nonReasoningModelSupportsOnlyOff() {
		Model m = model(ModelCost.FREE);
		assertEquals(List.of(ThinkingLevel.OFF), CodingAgentOperations.getSupportedThinkingLevels(m));
		assertEquals(ThinkingLevel.OFF, CodingAgentOperations.clampThinkingLevel(m, ThinkingLevel.HIGH));
	}

	@Test
	void xhighAndMaxRequireExplicitMapping() {
		Model m = reasoningModel(null);
		List<ThinkingLevel> levels = CodingAgentOperations.getSupportedThinkingLevels(m);
		assertTrue(levels.contains(ThinkingLevel.HIGH));
		assertFalse(levels.contains(ThinkingLevel.XHIGH));
		assertFalse(levels.contains(ThinkingLevel.MAX));
		assertEquals(ThinkingLevel.HIGH, CodingAgentOperations.clampThinkingLevel(m, ThinkingLevel.XHIGH));
	}

	@Test
	void nullMappingDisablesLevelAndClampsUp() {
		Map<ThinkingLevel, String> map = new EnumMap<>(ThinkingLevel.class);
		map.put(ThinkingLevel.MEDIUM, null);
		Model m = reasoningModel(map);
		List<ThinkingLevel> levels = CodingAgentOperations.getSupportedThinkingLevels(m);
		assertFalse(levels.contains(ThinkingLevel.MEDIUM));
		assertEquals(ThinkingLevel.HIGH, CodingAgentOperations.clampThinkingLevel(m, ThinkingLevel.MEDIUM));
	}

	@Test
	void resolvesMappedThinkingLevelsForProviderRequests() {
		Map<ThinkingLevel, String> map = new EnumMap<>(ThinkingLevel.class);
		map.put(ThinkingLevel.XHIGH, "high");
		map.put(ThinkingLevel.MAX, "max");
		Model m = reasoningModel(map);

		assertEquals("max", CodingAgentOperations.providerThinkingLevel(m, ThinkingLevel.MAX));
		assertEquals("high", CodingAgentOperations.providerThinkingLevel(m, ThinkingLevel.XHIGH));
		assertEquals(null, CodingAgentOperations.providerThinkingLevel(m, ThinkingLevel.OFF));
	}
}
