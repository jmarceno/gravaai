package dev.gravaai.services.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import dev.gravaai.core.json.Json;
import dev.gravaai.core.project.ElementMotion;

class AgentElementMotionOpsTest {

	private static List<ElementMotion.Track> motion(String json) {
		return AgentElementOps.motion(Json.parseJsonWithByteOrderMark(json), 3000).orElseThrow();
	}

	@Test
	void presetsAndTracksCombine() {
		List<ElementMotion.Track> tracks = motion("{\"motion\":[\"fade\",{\"preset\":\"slide-up\",\"out\":false},"
				+ "{\"property\":\"rotate\",\"keyframes\":[{\"at\":0,\"value\":-8},{\"at\":-0.5,\"value\":0,\"easing\":\"spring\"}]}]}");
		assertTrue(tracks.stream().anyMatch(track -> track.property() == ElementMotion.Property.ROTATE));
		ElementMotion.Track rotate = tracks.stream()
				.filter(track -> track.property() == ElementMotion.Property.ROTATE).findFirst().orElseThrow();
		assertTrue(rotate.keyframes().get(1).fromEnd(), "negative at counts from the end");
		assertEquals(500, rotate.keyframes().get(1).atMs());
	}

	@Test
	void emptyListClears() {
		assertEquals(List.of(), motion("{\"motion\":[]}"));
	}

	@Test
	void badInputIsExplained() {
		assertThrows(AgentOpReader.OpError.class, () -> motion("{\"motion\":[\"wobble\"]}"));
		assertThrows(AgentOpReader.OpError.class, () -> motion(
				"{\"motion\":[{\"property\":\"rotate\",\"keyframes\":[{\"at\":0,\"value\":1,\"easing\":\"zigzag\"}]}]}"));
		assertThrows(AgentOpReader.OpError.class, () -> motion(
				"{\"motion\":[{\"property\":\"skew\",\"keyframes\":[{\"at\":0,\"value\":1}]}]}"));
	}
}
