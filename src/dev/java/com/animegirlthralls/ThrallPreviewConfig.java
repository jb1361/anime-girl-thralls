package com.animegirlthralls;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.Range;

@ConfigGroup(ThrallPreviewConfig.GROUP)
public interface ThrallPreviewConfig extends Config
{
	String GROUP = "anime-girl-thralls-preview";

	@ConfigItem(keyName = "character", name = "Character", position = 0,
		description = "Bundled model to preview; Original hides the preview")
	default AnimeGirl character() { return AnimeGirl.GOHAN; }

	@Range(min = 50, max = 1000)
	@ConfigItem(keyName = "height", name = "Height", position = 1,
		description = "Model height in game units, using the plugin's normal scaling")
	default int height() { return 210; }

	@ConfigItem(keyName = "rotate", name = "Rotate", position = 2,
		description = "Slowly rotate the model to inspect it from all sides")
	default boolean rotate() { return false; }

	@ConfigItem(keyName = "animation", name = "Animation", position = 3,
		description = "Play a bundled clip; attacks repeat.")
	default PreviewAnimation animation() { return PreviewAnimation.IDLE; }
}
