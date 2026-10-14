package com.animegirlthralls;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * The custom models a thrall can be replaced with. Each girl's resources live in this package:
 * {@code <name>.json} for the model and {@code <name>_<clip>.bin} for her animation clips.
 * The body fraction is how much of the model's height is her body (from her feet to the top of her
 * head), so a weapon held above her head does not shrink her when the model is scaled. A girl modelled
 * with her own weapon is never given the configured item.
 */
@Getter
@RequiredArgsConstructor
public enum AnimeGirl
{
	ORIGINAL(null, 1f, false),
	ESDEATH("esdeath", 1f, false),
	RORY("rory", 0.747f, true),
	FRIEREN("v2/frieren", 1f, false),
	GOHAN("v2/gohan", 1f, false),
	GOKU("v2/goku", 1f, false),
	HISOKA("v2/hisoka", 1f, false),
	SAITAMA("v2/saitama", 1f, false),
	SANJI("v2/sanji", 1f, false),
	SHINJI("v2/shinji", 1f, false),
	TATSUMAKI("v2/tatsumaki", 1f, false);

	private final String resourceName;
	private final float bodyFraction;
	private final boolean ownWeapon;

	@Override
	public String toString()
	{
		if (this == ORIGINAL) { return "Original game thrall"; }
		String name = name().toLowerCase(java.util.Locale.ROOT);
		return Character.toUpperCase(name.charAt(0)) + name.substring(1);
	}

	String getAttributionResource()
	{
		return resourceName == null ? null : resourceName + ".attribution.txt";
	}

	String getModelResource()
	{
		return resourceName == null ? null : resourceName + ".json";
	}

	String getClipResource(ClipType type)
	{
		return resourceName == null ? null : resourceName + "_" + type.getSuffix() + ".bin";
	}
}
