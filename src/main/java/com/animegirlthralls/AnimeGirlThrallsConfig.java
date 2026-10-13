package com.animegirlthralls;

import net.runelite.api.gameval.ItemID;
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
		return AnimeGirl.RORY;
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
		return 210;
	}

	@ConfigItem(
		keyName = "animationMode",
		name = "Animation",
		description = "Custom: play bundled clips when available; other characters keep their standing pose.<br>"
			+ "Mimic thrall: deform the model to follow the thrall's own animation.<br>"
			+ "None: draw the model in its rest pose.",
		position = 4
	)
	default AnimationMode animationMode()
	{
		return AnimationMode.CUSTOM;
	}

	@ConfigItem(
		keyName = "magicWeapon",
		name = "Ghost thrall weapon",
		description = "Item ID of the staff held by ghost thrall replacements, or -1 for none. Default 1383 (Staff of water).",
		position = 5
	)
	default int magicWeapon()
	{
		return ItemID.STAFF_OF_WATER;
	}

	@ConfigItem(
		keyName = "rangedWeapon",
		name = "Skeleton thrall weapon",
		description = "Item ID of the bow held by skeleton thrall replacements, or -1 for none. Default 861 (Magic shortbow).",
		position = 6
	)
	default int rangedWeapon()
	{
		return ItemID.MAGIC_SHORTBOW;
	}

	@ConfigItem(
		keyName = "meleeWeapon",
		name = "Zombie thrall weapon",
		description = "Item ID of a melee weapon held by zombie thrall replacements, or -1 for bare hands.",
		position = 7
	)
	default int meleeWeapon()
	{
		return -1;
	}
}
