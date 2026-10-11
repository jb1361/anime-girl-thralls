package com.animegirlthralls;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.Range;

@ConfigGroup(AnimeGirlThrallsConfig.GROUP)
public interface AnimeGirlThrallsConfig extends Config
{
	String GROUP = "anime-girl-thralls";

	@ConfigItem(
		keyName = "replaceGhostThralls",
		name = "Replace ghost thralls",
		description = "Replace lesser, superior and greater ghost thralls with the custom model",
		position = 0
	)
	default boolean replaceGhostThralls()
	{
		return true;
	}

	@ConfigItem(
		keyName = "replaceSkeletonThralls",
		name = "Replace skeleton thralls",
		description = "Replace lesser, superior and greater skeleton thralls with the custom model",
		position = 1
	)
	default boolean replaceSkeletonThralls()
	{
		return true;
	}

	@ConfigItem(
		keyName = "replaceZombieThralls",
		name = "Replace zombie thralls",
		description = "Replace lesser, superior and greater zombie thralls with the custom model",
		position = 2
	)
	default boolean replaceZombieThralls()
	{
		return true;
	}

	@Range(min = 50, max = 1000)
	@ConfigItem(
		keyName = "modelHeight",
		name = "Model height",
		description = "Height of the custom model in game units. A player character is roughly 180 units tall.",
		position = 3
	)
	default int modelHeight()
	{
		return 190;
	}
}
