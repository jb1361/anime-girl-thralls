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
	RORY("rory", 0.747f, true);

	private final String resourceName;
	private final float bodyFraction;
	private final boolean ownWeapon;

	String getModelResource()
	{
		return resourceName == null ? null : resourceName + ".json";
	}

	String getClipResource(ClipType type)
	{
		return resourceName == null ? null : resourceName + "_" + type.getSuffix() + ".bin";
	}
}
