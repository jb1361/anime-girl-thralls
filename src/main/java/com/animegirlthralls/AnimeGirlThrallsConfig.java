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
		keyName = "ghostModel",
		name = "Ghost thralls",
		description = "Model to use for lesser, superior and greater ghost thralls",
		position = 0
	)
	default AnimeGirl ghostModel()
	{
		return AnimeGirl.ESDEATH;
	}

	@ConfigItem(
		keyName = "skeletonModel",
		name = "Skeleton thralls",
		description = "Model to use for lesser, superior and greater skeleton thralls",
		position = 1
	)
	default AnimeGirl skeletonModel()
	{
		return AnimeGirl.ESDEATH;
	}

	@ConfigItem(
		keyName = "zombieModel",
		name = "Zombie thralls",
		description = "Model to use for lesser, superior and greater zombie thralls",
		position = 2
	)
	default AnimeGirl zombieModel()
	{
		return AnimeGirl.ESDEATH;
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

	@ConfigItem(
		keyName = "mimicAnimations",
		name = "Mimic thrall animations",
		description = "Deform the custom model to follow the animation of the thrall it replaces",
		position = 4
	)
	default boolean mimicAnimations()
	{
		return true;
	}
}
