package com.pitch_shifter;

import com.google.common.collect.ImmutableSet;
import com.google.inject.Provides;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.Player;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.events.AreaSoundEffectPlayed;
import net.runelite.api.events.ClientTick;
import net.runelite.api.events.SoundEffectPlayed;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;

import javax.sound.sampled.*;
import java.io.InputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

@Slf4j
@PluginDescriptor(
		name = "Pitch Shifter",
		description = "Pitches game SFX by 15% using local WAV files",
		tags = {"sound", "sfx", "pitch", "chaotic"}
)
public class PitchShifter extends Plugin
{
	@Inject
	private Client client;

	@Inject
	private PitchShifterConfig config;

	// Add ids of sounds that should not be shifted
	private static final Set<Integer> EXCLUDED_SOUNDS = ImmutableSet.of(
			240 // UI button click, add more later if you want.
	);

	// Queue to hold sounds so we can tie them to the game engine
	private final List<QueuedSound> soundQueue = new ArrayList<>();

	private static class QueuedSound
	{
		final int soundId;
		final int baseVolume;
		final int distance;
		final int playAtCycle;

		QueuedSound(int soundId, int baseVolume, int distance, int playAtCycle)
		{
			this.soundId = soundId;
			this.baseVolume = baseVolume;
			this.distance = distance;
			this.playAtCycle = playAtCycle;
		}
	}

	@Override
	protected void startUp() throws Exception
	{
		log.info("Pitch Shifter started! Prepare for chaos.");
	}

	@Override
	protected void shutDown() throws Exception
	{
		soundQueue.clear();
		log.info("Pitch Shifter stopped.");
	}

	@Provides
	PitchShifterConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(PitchShifterConfig.class);
	}

	@Subscribe
	public void onSoundEffectPlayed(SoundEffectPlayed event)
	{
		int soundId = event.getSoundId();

		if (EXCLUDED_SOUNDS.contains(soundId) || !doesSoundExist(soundId))
		{
			return;
		}

		event.consume();

		int globalVolume = client.getPreferences().getSoundEffectVolume();
		int delayCycles = event.getDelay();

		// Ditch Thread.sleep, its trash. Tie the delay to the game engine cycle
		int targetCycle = client.getGameCycle() + delayCycles;
		soundQueue.add(new QueuedSound(soundId, globalVolume, 0, targetCycle));
	}

	@Subscribe
	public void onAreaSoundEffectPlayed(AreaSoundEffectPlayed event)
	{
		int soundId = event.getSoundId();

		if (EXCLUDED_SOUNDS.contains(soundId) || !doesSoundExist(soundId))
		{
			return;
		}

		event.consume();

		int areaVolume = client.getPreferences().getAreaSoundEffectVolume();
		int distance = 0;
		Player localPlayer = client.getLocalPlayer();

		if (localPlayer != null)
		{
			LocalPoint playerLocation = localPlayer.getLocalLocation();
			int deltaX = playerLocation.getSceneX() - event.getSceneX();
			int deltaY = playerLocation.getSceneY() - event.getSceneY();
			distance = (int) Math.sqrt((deltaX * deltaX) + (deltaY * deltaY));
		}

		int targetCycle = client.getGameCycle() + event.getDelay();
		soundQueue.add(new QueuedSound(soundId, areaVolume, distance, targetCycle));
	}

	// Process the queue thats now synched with the game engine's ticks
	@Subscribe
	public void onClientTick(ClientTick event)
	{
		if (soundQueue.isEmpty())
		{
			return;
		}

		int currentCycle = client.getGameCycle();
		Iterator<QueuedSound> it = soundQueue.iterator();

		while (it.hasNext())
		{
			QueuedSound qs = it.next();
			if (currentCycle >= qs.playAtCycle)
			{
				playPitchShiftedSound(qs.soundId, qs.baseVolume, qs.distance);
				it.remove();
			}
		}
	}

	private void playPitchShiftedSound(int soundId, int baseVolume, int distance)
	{
		if (baseVolume <= 0) return;

		// Audio processing runs on a background thread so we dont lag the client tick
		new Thread(() -> {
			String resourcePath = "/sounds/" + soundId + ".wav";
			try (InputStream is = getClass().getResourceAsStream(resourcePath))
			{
				if (is == null) return;

				AudioInputStream stream = AudioSystem.getAudioInputStream(is);
				AudioFormat format = stream.getFormat();

				java.io.ByteArrayOutputStream byteStream = new java.io.ByteArrayOutputStream();
				byte[] buffer = new byte[4096];
				int bytesRead;
				while ((bytesRead = stream.read(buffer)) != -1)
				{
					byteStream.write(buffer, 0, bytesRead);
				}
				byte[] rawBytes = byteStream.toByteArray();
				stream.close();

				int numChannels = format.getChannels();
				int bytesPerFrame = format.getFrameSize();
				int totalFrames = rawBytes.length / bytesPerFrame;

				float pitchFactor = (float) ThreadLocalRandom.current().nextDouble(0.85, 1.15);
				int targetFrames = (int) (totalFrames / pitchFactor);
				byte[] outputBytes = new byte[targetFrames * bytesPerFrame];

				// This fixes static and audio popping issues
				float volumePercent = (float) baseVolume / 127.0f;
				if (distance > 0)
				{
					// Tiny falloff per tile, this should fix the distance issue.
					float distanceModifier = Math.max(0.0f, 1.0f - (distance * 0.08f));
					volumePercent *= distanceModifier;
				}

				float volumeAmplitude = volumePercent * volumePercent;

				if (format.getSampleSizeInBits() == 8)
				{
					// 8 bit with volume scale applied
					for (int frame = 0; frame < targetFrames; frame++)
					{
						float sourceFrameIndex = frame * pitchFactor;
						int index1 = (int) sourceFrameIndex;
						int index2 = Math.min(totalFrames - 1, index1 + 1);
						float fraction = sourceFrameIndex - index1;

						for (int channel = 0; channel < numChannels; channel++)
						{
							int s1 = rawBytes[index1 * numChannels + channel] & 0xFF;
							int s2 = rawBytes[index2 * numChannels + channel] & 0xFF;

							int interpolatedSample = (int) (s1 + fraction * (s2 - s1));

							int centered = interpolatedSample - 128;
							centered = (int) (centered * volumeAmplitude);
							interpolatedSample = Math.max(0, Math.min(255, centered + 128));

							outputBytes[frame * numChannels + channel] = (byte) (interpolatedSample & 0xFF);
						}
					}
				}
				else
				{
					// 16 bit with volume scale applied
					int totalSamples = rawBytes.length / 2;
					short[] sourceSamples = new short[totalSamples];
					boolean isBigEndian = format.isBigEndian();

					for (int i = 0; i < totalSamples; i++)
					{
						int b1 = rawBytes[i * 2] & 0xFF;
						int b2 = rawBytes[i * 2 + 1] & 0xFF;
						sourceSamples[i] = (short) (isBigEndian ? (b1 << 8) | b2 : b1 | (b2 << 8));
					}

					for (int frame = 0; frame < targetFrames; frame++)
					{
						float sourceFrameIndex = frame * pitchFactor;
						int index1 = (int) sourceFrameIndex;
						int index2 = Math.min(totalFrames - 1, index1 + 1);
						float fraction = sourceFrameIndex - index1;

						for (int channel = 0; channel < numChannels; channel++)
						{
							short s1 = sourceSamples[index1 * numChannels + channel];
							short s2 = sourceSamples[index2 * numChannels + channel];

							short interpolatedSample = (short) (s1 + fraction * (s2 - s1));

							interpolatedSample = (short) (interpolatedSample * volumeAmplitude);

							int outByteIdx = (frame * numChannels + channel) * 2;
							if (isBigEndian)
							{
								outputBytes[outByteIdx] = (byte) ((interpolatedSample >> 8) & 0xFF);
								outputBytes[outByteIdx + 1] = (byte) (interpolatedSample & 0xFF);
							}
							else
							{
								outputBytes[outByteIdx] = (byte) (interpolatedSample & 0xFF);
								outputBytes[outByteIdx + 1] = (byte) ((interpolatedSample >> 8) & 0xFF);
							}
						}
					}
				}

				DataLine.Info info = new DataLine.Info(SourceDataLine.class, format);
				SourceDataLine line = (SourceDataLine) AudioSystem.getLine(info);

				line.open(format);
				line.start();
				line.write(outputBytes, 0, outputBytes.length);
				line.drain();
				line.close();
			}
			catch (UnsupportedAudioFileException | IOException | LineUnavailableException e)
			{
				e.printStackTrace();
			}
		}).start();
	}

	private boolean doesSoundExist(int soundId)
	{
		String resourcePath = "/sounds/" + soundId + ".wav";
		try (InputStream is = getClass().getResourceAsStream(resourcePath))
		{
			return is != null;
		}
		catch (IOException e)
		{
			return false;
		}
	}
}