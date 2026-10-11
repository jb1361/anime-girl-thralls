package com.animegirlthralls;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * The animation clips a girl can provide. The suffix names the clip resource: {@code <girl>_<suffix>.bin}.
 */
@Getter
@RequiredArgsConstructor
enum ClipType
{
	IDLE("idle", true),
	WALK("walk", true),
	ATTACK_MELEE("attack_melee", false),
	ATTACK_RANGED("attack_ranged", false),
	ATTACK_MAGIC("attack_magic", false);

	private final String suffix;
	private final boolean loop;
}
