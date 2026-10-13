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
	AINZ("v2/ainz", 1f, true),
	ALLMIGHT("v2/allmight", 1f, true),
	AQUA("v2/aqua", 1f, true),
	ASUKA("v2/asuka", 1f, true),
	CHARIZARD("v2/charizard", 1f, true),
	DENJI("v2/denji", 1f, true),
	EDWARD("v2/edward", 1f, true),
	ESCANOR("v2/escanor", 1f, true),
	FRIEREN("v2/frieren", 1f, false),
	GOHAN("v2/gohan", 1f, false),
	GOJO("v2/gojo", 1f, true),
	GOKU("v2/goku", 1f, false),
	GON("v2/gon", 1f, true),
	HISOKA("v2/hisoka", 1f, false),
	ICHIGO("v2/ichigo", 1f, true),
	JINWOO("v2/jinwoo", 1f, true),
	KAKASHI("v2/kakashi", 1f, false),
	KANEKI("v2/kaneki", 1f, true),
	KURAPIKA("v2/kurapika", 1f, true),
	LEORIO("v2/leorio", 1f, true),
	LEVI("v2/levi", 1f, true),
	LUFFY("v2/luffy", 1f, true),
	MAJIN("v2/majin", 1f, true),
	MAKI("v2/maki", 1f, true),
	MAKIMA("v2/makima", 1f, true),
	MEGUMI("v2/megumi", 1f, true),
	NANAMI("v2/nanami", 1f, true),
	NARUTO("v2/naruto", 1f, true),
	POWER("v2/power", 1f, true),
	RIMURU("v2/rimuru", 1f, true),
	SAITAMA("v2/saitama", 1f, false),
	SANJI("v2/sanji", 1f, false),
	SASUKE("v2/sasuke", 1f, true),
	SEIYA("v2/seiya", 1f, true),
	SHINJI("v2/shinji", 1f, false),
	SUKUNA("v2/sukuna", 1f, true),
	TATSUMAKI("v2/tatsumaki", 1f, false),
	USOPP("v2/usopp", 1f, true),
	YUGI("v2/yugi", 1f, true),
	YUSUKE("v2/yusuke", 1f, true),
	ZORO("v2/zoro", 1f, true);

	private final String resourceName;
	private final float bodyFraction;
	private final boolean ownWeapon;

	@Override
	public String toString()
	{
		if (this == ORIGINAL) { return "Original game thrall"; }
		if (this == ALLMIGHT) { return "All Might"; }
		if (this == MAJIN) { return "Kid Buu"; }
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
