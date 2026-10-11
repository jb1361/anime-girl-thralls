package com.animegirlthralls;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import net.runelite.api.gameval.AnimationID;
import net.runelite.api.gameval.NpcID;

/**
 * The three thrall families and how a girl stands in for each: which attack clip to play, which hand
 * holds the weapon, and how that weapon is gripped.
 */
@Getter
@RequiredArgsConstructor
enum ThrallKind
{
	/** Ghost thralls: a staff in the right hand. */
	MAGIC(ClipType.ATTACK_MAGIC, VertexClip.RIGHT_HAND, WeaponAlign.Kind.STAFF),
	/** Skeleton thralls: a bow in the left hand. */
	RANGED(ClipType.ATTACK_RANGED, VertexClip.LEFT_HAND, WeaponAlign.Kind.BOW),
	/** Zombie thralls: a punch, or a melee weapon in the right hand. */
	MELEE(ClipType.ATTACK_MELEE, VertexClip.RIGHT_HAND, WeaponAlign.Kind.MELEE);

	private final ClipType attackClip;
	private final int weaponHand;
	private final WeaponAlign.Kind weaponKind;

	static ThrallKind forNpc(int npcId)
	{
		switch (npcId)
		{
			case NpcID.ARCEUUS_THRALL_GHOST_LESSER:
			case NpcID.ARCEUUS_THRALL_GHOST_SUPERIOR:
			case NpcID.ARCEUUS_THRALL_GHOST_GREATER:
				return MAGIC;
			case NpcID.ARCEUUS_THRALL_SKELETON_LESSER:
			case NpcID.ARCEUUS_THRALL_SKELETON_SUPERIOR:
			case NpcID.ARCEUUS_THRALL_SKELETON_GREATER:
				return RANGED;
			case NpcID.ARCEUUS_THRALL_ZOMBIE_LESSER:
			case NpcID.ARCEUUS_THRALL_ZOMBIE_SUPERIOR:
			case NpcID.ARCEUUS_THRALL_ZOMBIE_GREATER:
				return MELEE;
			default:
				return null;
		}
	}

	static boolean isAttackAnimation(int animationId)
	{
		switch (animationId)
		{
			case AnimationID.GHOST_UPDATE_TENDRILL_ATTACK_THRALL:
			case AnimationID.SKELETON_UPDATE_CHAMPION_ATTACK_THRALL:
			case AnimationID.ZOMBIE_UPDATE_ATTACK_NORMAL_THRALL:
				return true;
			default:
				return false;
		}
	}

	static boolean isWalkAnimation(int poseAnimationId)
	{
		switch (poseAnimationId)
		{
			case AnimationID.GHOST_UPDATE_TENDRILL_WALK_THRALL:
			case AnimationID.SKELETON_UPDATE_CHAMPION_WALK_THRALL:
			case AnimationID.ZOMBIE_UPDATE_WALK_NORMAL_THRALL:
			case AnimationID.ZOMBIE_UPDATE_WALK_LEFT_NORMAL_THRALL:
			case AnimationID.ZOMBIE_UPDATE_WALK_RIGHT_NORMAL_THRALL:
			case AnimationID.ZOMBIE_UPDATE_WALKBACKWARDS_WEAPON_THRALL:
				return true;
			default:
				return false;
		}
	}
}
