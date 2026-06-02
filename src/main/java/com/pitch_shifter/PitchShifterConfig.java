package com.pitch_shifter;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;

@ConfigGroup("pitch-shifter")
public interface PitchShifterConfig extends Config
{
	@ConfigItem(
			keyName = "pitchShiftAmount",
			name = "Shift Amount (%)",
			description = "Currently unused. Placeholder for future chaos scaling."
	)
	default int pitchShiftAmount()
	{
		return 15;
	}
}