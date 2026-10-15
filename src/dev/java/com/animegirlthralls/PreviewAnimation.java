package com.animegirlthralls;

public enum PreviewAnimation
{
	REST(null), IDLE(ClipType.IDLE), WALK(ClipType.WALK),
	MELEE(ClipType.ATTACK_MELEE), RANGED(ClipType.ATTACK_RANGED), MAGIC(ClipType.ATTACK_MAGIC);

	private final ClipType clip;
	PreviewAnimation(ClipType clip) { this.clip = clip; }
	ClipType clip() { return clip; }
}
