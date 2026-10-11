package com.animegirlthralls;

import java.util.Arrays;
import java.util.function.IntFunction;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Animation;
import net.runelite.api.Client;
import net.runelite.api.Model;
import net.runelite.api.ModelData;
import net.runelite.api.NPC;
import net.runelite.api.NPCComposition;
import net.runelite.api.Perspective;
import net.runelite.api.RuneLiteObjectController;
import net.runelite.api.WorldView;
import net.runelite.api.coords.LocalPoint;

/**
 * A custom model drawn in place of one thrall npc.
 * <p>
 * In custom mode the girl's own baked clips play: the attack clip for the thrall's kind when the thrall
 * starts its attack animation, the walk clip while the thrall's pose animation is a walk, and the idle
 * clip otherwise. A weapon merged into the model follows the hand anchor stored in each clip.
 * In mimic mode the npc's own animated model drives a deformation of the custom model instead.
 * <p>
 * Either way the posed vertices are written into the client's shared scratch model inside
 * {@link #getModel()}, exactly as the client does for its own animations. The scratch model recomputes
 * its bounds, whereas a model's own bounds are cached after the first draw and renderers assert that
 * vertices stay inside them. All methods must be called on the client thread.
 */
@Slf4j
final class ThrallReplacement extends RuneLiteObjectController
{
	private static final int NEIGHBOURS = 4;
	private static final int DEFAULT_SCALE = 128;
	private static final int OBJECT_RADIUS = 60;
	private static final int DEBUG_EVERY = 300;
	private static final float CYCLE_SECONDS = 0.02f;
	private static final float BLEND_SECONDS = 0.15f;

	private final Client client;
	private final NPC npc;
	private final ThrallKind kind;
	private final Model model;
	private final GirlAssets assets;
	private final float scale;
	private final float weaponScale;
	private final int girlVertexCount;
	private final int weaponVertexCount;
	private final int vertexCount;
	private final float[][] weaponHand;
	private final float[][] base;
	private final float[][] work;
	private final float[][] blendFrom;
	private final float[] anchor = new float[VertexClip.ANCHOR_FLOATS];
	private final float timeOffset;

	private AnimationMode mode = AnimationMode.NONE;
	private IntFunction<Animation> animations;
	private int frames;

	// custom clips
	private ClipType currentClip;
	private float attackStart = Float.NaN;
	private float blendStart = Float.NaN;
	private int lastAnimId = -1;
	private int lastAnimFrame = -1;
	private boolean clipMismatchLogged;

	// mimic
	private Model thrallRest;
	private float[][] rest;
	private WrapDeformer deformer;

	/**
	 * @param built       the built, scaled model with any weapon merged in
	 * @param assets      the girl's resources, whose clips share the model's unscaled coordinates
	 * @param scale       the factor the girl was scaled by when built
	 * @param weaponScale the factor the weapon's game units were scaled by
	 */
	ThrallReplacement(Client client, NPC npc, ThrallKind kind, CustomModelLoader.BuiltModel built, GirlAssets assets,
					  float scale, float weaponScale)
	{
		this.client = client;
		this.npc = npc;
		this.kind = kind;
		this.model = built.getModel();
		this.assets = assets;
		this.scale = scale;
		this.weaponScale = weaponScale;
		this.girlVertexCount = built.getGirlVertexCount();
		this.weaponVertexCount = built.getWeaponVertexCount();
		this.weaponHand = built.getWeaponHandSpace();
		setRadius(OBJECT_RADIUS);

		// The live arrays may be longer than the vertex count, so only keep the real vertices
		vertexCount = girlVertexCount + weaponVertexCount;
		base = new float[][]{
			Arrays.copyOf(model.getVerticesX(), vertexCount),
			Arrays.copyOf(model.getVerticesY(), vertexCount),
			Arrays.copyOf(model.getVerticesZ(), vertexCount),
		};
		// Rest the weapon in the idle clip's starting hand position when there is one
		VertexClip idle = assets.getClip(ClipType.IDLE);
		if (weaponVertexCount > 0 && idle != null && idle.sampleAnchor(kind.getWeaponHand(), 0f, scale, anchor))
		{
			placeWeapon(base);
		}
		work = new float[][]{base[0].clone(), base[1].clone(), base[2].clone()};
		blendFrom = new float[3][vertexCount];
		// Desynchronise looping clips between thralls
		timeOffset = (npc.getIndex() % 97) * 0.173f;
	}

	void activate()
	{
		client.registerRuneLiteObject(this);
	}

	void deactivate()
	{
		client.removeRuneLiteObject(this);
	}

	/**
	 * Moves the object onto the npc and records how the next draw should animate it.
	 *
	 * @param mode       how to animate
	 * @param animations loads an animation by id, may return null
	 */
	void sync(AnimationMode mode, IntFunction<Animation> animations)
	{
		this.mode = mode;
		this.animations = animations;

		LocalPoint lp = npc.getLocalLocation();
		WorldView wv = npc.getWorldView();
		if (lp != null && wv != null)
		{
			int plane = wv.getPlane();
			setLocation(lp, plane);
			setZ(Perspective.getTileHeight(client, lp, plane));
			setOrientation(npc.getCurrentOrientation());
		}

		// Watch the npc's animation every frame so a restarted attack is not missed
		int anim = npc.getAnimation();
		int frame = npc.getAnimationFrame();
		if (ThrallKind.isAttackAnimation(anim) && (anim != lastAnimId || frame < lastAnimFrame))
		{
			attackStart = now();
		}
		lastAnimId = anim;
		lastAnimFrame = frame;

		if (mode == AnimationMode.MIMIC_THRALL && deformer == null)
		{
			bind();
		}
	}

	@Override
	public Model getModel()
	{
		frames++;
		switch (mode)
		{
			case CUSTOM:
				return clipModel();
			case MIMIC_THRALL:
				return mimicModel();
			default:
				return model;
		}
	}

	private float now()
	{
		return client.getGameCycle() * CYCLE_SECONDS;
	}

	// ---------------------------------------------------------------- custom clips

	private Model clipModel()
	{
		float now = now();
		ClipType desired;
		float time;

		VertexClip attack = assets.getClip(kind.getAttackClip());
		if (attack != null && !Float.isNaN(attackStart) && now - attackStart < attack.getDuration())
		{
			desired = kind.getAttackClip();
			time = now - attackStart;
		}
		else if (ThrallKind.isWalkAnimation(npc.getPoseAnimation()) && assets.getClip(ClipType.WALK) != null)
		{
			desired = ClipType.WALK;
			time = now + timeOffset;
		}
		else if (assets.getClip(ClipType.IDLE) != null)
		{
			desired = ClipType.IDLE;
			time = now + timeOffset;
		}
		else
		{
			return model;
		}

		VertexClip clip = assets.getClip(desired);
		if (clip.getVertexCount() != girlVertexCount)
		{
			if (!clipMismatchLogged)
			{
				clipMismatchLogged = true;
				log.warn("Clip {} has {} vertices but the model has {}", desired, clip.getVertexCount(), girlVertexCount);
			}
			return model;
		}

		if (desired != currentClip)
		{
			if (currentClip != null)
			{
				// Blend from whatever pose was last drawn into the new clip
				for (int axis = 0; axis < 3; axis++)
				{
					System.arraycopy(work[axis], 0, blendFrom[axis], 0, vertexCount);
				}
				blendStart = now;
			}
			currentClip = desired;
		}

		clip.sample(time, scale, work[0], work[1], work[2]);
		if (weaponVertexCount > 0 && clip.sampleAnchor(kind.getWeaponHand(), time, scale, anchor))
		{
			placeWeapon(work);
		}

		if (!Float.isNaN(blendStart))
		{
			float u = (now - blendStart) / BLEND_SECONDS;
			if (u >= 1f)
			{
				blendStart = Float.NaN;
			}
			else
			{
				float s = u * u * (3 - 2 * u);
				for (int axis = 0; axis < 3; axis++)
				{
					float[] from = blendFrom[axis];
					float[] to = work[axis];
					for (int i = 0; i < vertexCount; i++)
					{
						to[i] = from[i] + (to[i] - from[i]) * s;
					}
				}
			}
		}

		if (log.isDebugEnabled() && frames % DEBUG_EVERY == 1)
		{
			log.debug("Npc {} ({}) clip {} time {} anim {} pose {} weapon vertices {}",
				npc.getId(), kind, desired, time, npc.getAnimation(), npc.getPoseAnimation(), weaponVertexCount);
		}
		return writeScratch();
	}

	/**
	 * Writes the weapon's vertices into out, placed at the current anchor: position xyz then
	 * quaternion wxyz, with the position already scaled.
	 */
	private void placeWeapon(float[][] out)
	{
		float px = anchor[0];
		float py = anchor[1];
		float pz = anchor[2];
		float qw = anchor[3];
		float qx = anchor[4];
		float qy = anchor[5];
		float qz = anchor[6];
		float r00 = 1 - 2 * (qy * qy + qz * qz);
		float r01 = 2 * (qx * qy - qw * qz);
		float r02 = 2 * (qx * qz + qw * qy);
		float r10 = 2 * (qx * qy + qw * qz);
		float r11 = 1 - 2 * (qx * qx + qz * qz);
		float r12 = 2 * (qy * qz - qw * qx);
		float r20 = 2 * (qx * qz - qw * qy);
		float r21 = 2 * (qy * qz + qw * qx);
		float r22 = 1 - 2 * (qx * qx + qy * qy);
		for (int i = 0; i < weaponVertexCount; i++)
		{
			float hx = weaponHand[0][i] * weaponScale;
			float hy = weaponHand[1][i] * weaponScale;
			float hz = weaponHand[2][i] * weaponScale;
			int v = girlVertexCount + i;
			out[0][v] = px + r00 * hx + r01 * hy + r02 * hz;
			out[1][v] = py + r10 * hx + r11 * hy + r12 * hz;
			out[2][v] = pz + r20 * hx + r21 * hy + r22 * hz;
		}
	}

	// ---------------------------------------------------------------- mimic

	private Model mimicModel()
	{
		if (deformer == null)
		{
			return model;
		}

		boolean debug = log.isDebugEnabled() && frames % DEBUG_EVERY == 1;

		// Prefer the model the client itself would draw for the npc: it already has the current
		// animation, pose and interpolation applied. Fall back to animating our own copy.
		String source = "npc";
		Model driver = npc.getModel();
		if (driver == null || driver.getVerticesCount() != rest[0].length)
		{
			source = "rest";
			driver = animateRestModel();
		}
		if (driver == null)
		{
			return model;
		}

		// The driver is the shared scratch model, so take what we need from it before touching it again
		float[][] current = {driver.getVerticesX(), driver.getVerticesY(), driver.getVerticesZ()};
		deformer.apply(base, rest, current, work);
		if (debug)
		{
			log.debug("Npc {} anim {} frame {} pose {} frame {} source {} max driver displacement {}",
				npc.getId(), npc.getAnimation(), npc.getAnimationFrame(), npc.getPoseAnimation(),
				npc.getPoseAnimationFrame(), source, maxDisplacement(current));
		}
		return writeScratch();
	}

	private Model animateRestModel()
	{
		if (animations == null)
		{
			return null;
		}

		Animation animA = null;
		Animation animB = null;
		int frameA = 0;
		int frameB = 0;

		int poseId = npc.getPoseAnimation();
		Animation pose = poseId != -1 ? animations.apply(poseId) : null;
		int poseFrame = npc.getPoseAnimationFrame();
		boolean poseValid = pose != null && frameValid(pose, poseFrame);

		int animId = npc.getAnimation();
		Animation anim = animId != -1 ? animations.apply(animId) : null;
		int animFrame = npc.getAnimationFrame();
		if (anim != null && frameValid(anim, animFrame))
		{
			animA = anim;
			frameA = animFrame;
			if (poseValid)
			{
				animB = pose;
				frameB = poseFrame;
			}
		}
		else if (poseValid)
		{
			animA = pose;
			frameA = poseFrame;
		}

		if (animA == null)
		{
			return null;
		}

		Model animated = client.applyTransformations(thrallRest, animA, frameA, animB, frameB);
		if (animated == null || animated.getVerticesCount() != rest[0].length)
		{
			return null;
		}
		return animated;
	}

	private float maxDisplacement(float[][] current)
	{
		float max = 0f;
		for (int i = 0; i < rest[0].length; i++)
		{
			float dx = current[0][i] - rest[0][i];
			float dy = current[1][i] - rest[1][i];
			float dz = current[2][i] - rest[2][i];
			max = Math.max(max, dx * dx + dy * dy + dz * dz);
		}
		return (float) Math.sqrt(max);
	}

	private static boolean frameValid(Animation animation, int frame)
	{
		int frames = animation.isMayaAnim() ? animation.getDuration() : animation.getNumFrames();
		return frame >= 0 && frame < frames;
	}

	/**
	 * Builds the npc's own unanimated model and binds the custom model to it.
	 *
	 * @return false if the npc's models are not loaded yet
	 */
	private boolean bind()
	{
		NPCComposition comp = npc.getTransformedComposition();
		if (comp == null)
		{
			comp = npc.getComposition();
		}
		if (comp == null)
		{
			return false;
		}

		int[] ids = comp.getModels();
		if (ids == null || ids.length == 0)
		{
			return false;
		}

		ModelData[] parts = new ModelData[ids.length];
		for (int i = 0; i < ids.length; i++)
		{
			parts[i] = client.loadModelData(ids[i]);
			if (parts[i] == null)
			{
				return false;
			}
		}

		// mergeModels always returns a new ModelData, so scaling it leaves the cached parts untouched
		ModelData merged = client.mergeModels(parts);
		if (merged == null)
		{
			return false;
		}
		if (comp.getWidthScale() != DEFAULT_SCALE || comp.getHeightScale() != DEFAULT_SCALE)
		{
			merged = merged.scale(comp.getWidthScale(), comp.getHeightScale(), comp.getWidthScale());
		}

		Model lit = merged.light();
		if (lit == null || lit.getVerticesCount() == 0)
		{
			return false;
		}

		int m = lit.getVerticesCount();
		thrallRest = lit;
		rest = new float[][]{
			Arrays.copyOf(lit.getVerticesX(), m),
			Arrays.copyOf(lit.getVerticesY(), m),
			Arrays.copyOf(lit.getVerticesZ(), m),
		};
		deformer = WrapDeformer.bind(base, rest, NEIGHBOURS);
		log.debug("Bound custom model ({} vertices) to npc {} model ({} vertices), displacement scale {}",
			vertexCount, npc.getId(), m, deformer.getScale());
		return true;
	}

	// ---------------------------------------------------------------- output

	/**
	 * Copies the work pose into the client's shared scratch model and returns it. With no animations,
	 * applyTransformations clones the base vertices into the scratch model and resets its bounds.
	 */
	private Model writeScratch()
	{
		Model scratch = client.applyTransformations(model, null, 0, null, 0);
		if (scratch == null || scratch == model)
		{
			return model;
		}
		float[] sx = scratch.getVerticesX();
		float[] sy = scratch.getVerticesY();
		float[] sz = scratch.getVerticesZ();
		if (sx == model.getVerticesX() || sx.length < vertexCount || sy.length < vertexCount || sz.length < vertexCount)
		{
			return model;
		}
		System.arraycopy(work[0], 0, sx, 0, vertexCount);
		System.arraycopy(work[1], 0, sy, 0, vertexCount);
		System.arraycopy(work[2], 0, sz, 0, vertexCount);
		return scratch;
	}
}
