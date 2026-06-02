# RuneLite Pitch Shifter (WeeeEEEE wOOOoooO)

Randomly pitches in-game SFX up and down by 15%. Because bossing just isnt chaotic enough until Graardor sounds like hes on helium.

## How it works
Java's default volume controls and threading are trash, so this plugin bypasses them completely:
* Grabs default game and area sounds before they play
* Looks for a matching `.wav` file in your resources folder
* Manipulates the raw audio bytes directly (handles both 8-bit and 16-bit with proper DC-offset math so it doesnt pop or static) to apply the 15% pitch shift
* Ditches `Thread.sleep()` for delayed sounds like projectiles. It ties them directly to `client.getGameCycle()` so the audio is perfectly synched with the engine

## Adding sounds
1. Find the sound ID you want to mess with
2. Get the `.wav` file for it
3. Name the file `[id].wav` (like `2266.wav`)
4. Dump it into `src/main/resources/sounds/`

## Note
UI button clicks and a few other super spammy sounds are blacklisted in the code by default so you dont completely lose your mind.
