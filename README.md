# RuneLite Pitch Shifter

A RuneLite plugin that randomly pitches in-game sound effects up and down by 15%. Because bossing isn't chaotic enough until General Graardor sounds like a helium balloon.

## How it Works
Instead of relying on Java's notoriously bad volume controls and threading, this plugin:
1. Intercepts default OSRS game and area sounds.
2. Checks if a matching `.wav` file exists in the plugin's resources.
3. Directly manipulates the audio byte data (handling both 8-bit and 16-bit audio, with proper DC-offset math) to apply a ±15% pitch shift and scale amplitude.
4. Binds delayed sounds (like projectiles) to `client.getGameCycle()` instead of real-time threads to ensure perfect engine sync.

## Adding Sounds
1. Find the in-game Sound ID you want to shift.
2. Get the `.wav` file for it.
3. Name the file `[SoundID].wav` (e.g., `2266.wav`).
4. Place it in `src/main/resources/sounds/`.

## Notice
UI clicks and certain highly repetitive sounds are excluded by default in the code to preserve user sanity.
