package dev.gravaai.services.recording;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Tests for the WebAudio-analyser-equivalent mic meter maths (plan §5.6.9). */
class AudioLevelMeterTest {

	private static float[] sine(double amplitude, double frequencyHz, int samples) {
		float[] values = new float[samples];
		for (int index = 0; index < samples; index++) {
			values[index] = (float) (amplitude * Math.sin(2 * Math.PI * frequencyHz * index / 48_000.0));
		}
		return values;
	}

	@Test
	@DisplayName("reads silence as an empty meter")
	void readsSilenceAsEmpty() {
		AudioLevelMeter meter = new AudioLevelMeter();
		assertEquals(0, meter.update(new float[AudioLevelMeter.FFT_SIZE]));
		assertEquals(0, meter.update(new float[0]));
		assertEquals(0, meter.update(null));
	}

	@Test
	@DisplayName("scales a loud tone above a quiet one and stays within 0..100")
	void scalesTheToneLevel() {
		AudioLevelMeter loud = new AudioLevelMeter();
		AudioLevelMeter quiet = new AudioLevelMeter();
		double loudLevel = loud.update(sine(1.0, 1000, 512));
		double quietLevel = quiet.update(sine(0.01, 1000, 512));
		assertTrue(loudLevel >= quietLevel);
		assertTrue(loudLevel > 0);
		assertTrue(loudLevel <= 100);
		assertTrue(quietLevel >= 0);
		assertTrue(quietLevel <= 100);
	}

	@Test
	@DisplayName("smooths between updates so a steady tone ramps up")
	void smoothsBetweenUpdates() {
		AudioLevelMeter meter = new AudioLevelMeter();
		float[] tone = sine(0.5, 1000, 512);
		double first = meter.update(tone);
		double second = meter.update(tone);
		assertTrue(second > first);
	}

	@Test
	@DisplayName("scales the RMS of the analyser bytes like the hook")
	void scalesTheRmsOfTheAnalyserBytes() {
		assertEquals(0, AudioLevelMeter.levelFromFrequencyData(new byte[128]));
		assertEquals(100, AudioLevelMeter.levelFromFrequencyData(new byte[] {(byte) 0xFF}));
		byte[] half = new byte[128];
		for (int index = 0; index < half.length; index++) {
			half[index] = (byte) 128;
		}
		double level = AudioLevelMeter.levelFromFrequencyData(half);
		assertEquals(100, level, 0.01);
	}
}
