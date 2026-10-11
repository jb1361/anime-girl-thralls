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
 * A custom model drawn in place of one thrall npc. The npc's own model, animated by the client with the
 * npc's current animation, drives a deformation of the custom model so it follows the thrall's motion.
 * <p>
 * The deformed vertices are written into the client's shared scratch model inside {@link #getModel()},
 * exactly as the client does for its own animations. The scratch model recomputes its bounds, whereas a
 * model's own bounds are cached after the first draw and renderers assert that vertices stay inside them.
 * All methods must be called on the client thread.
 */
@Slf4j
final class ThrallReplacement extends RuneLiteObjectController
{
	private static final int NEIGHBOURS = 4;
	private static final int DEFAULT_SCALE = 128;
	private static final int OBJECT_RADIUS = 60;
	private static final int DEBUG_EVERY = 300;

	private final Client client;
	private final NPC npc;
	private final Model model;
	private final int vertexCount;
	private final float[][] base;
	private final float[][] deformedVertices;

	private boolean mimic;
	private IntFunction<Animation> animations;
	private Model thrallRest;
	private float[][] rest;
	private WrapDeformer deformer;
	private int frames;

	ThrallReplacement(Client client, NPC npc, Model model)
	{
		this.client = client;
		this.npc = npc;
		this.model = model;
		setRadius(OBJECT_RADIUS);

		// The live arrays may be longer than the vertex count, so only keep the real vertices
		vertexCount = model.getVerticesCount();
		base = new float[][]{
			Arrays.copyOf(model.getVerticesX(), vertexCount),
			Arrays.copyOf(model.getVerticesY(), vertexCount),
			Arrays.copyOf(model.getVerticesZ(), vertexCount),
		};
		deformedVertices = new float[3][vertexCount];
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
	 * @param mimic      whether to follow the npc's animation
	 * @param animations loads an animation by id, may return null
	 */
	void sync(boolean mimic, IntFunction<Animation> animations)
	{
		this.mimic = mimic;
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

		if (mimic && deformer == null)
		{
			bind();
		}
	}

	@Override
	public Model getModel()
	{
		if (!mimic || deformer == null)
		{
			return model;
		}

		frames++;
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
		deformer.apply(base, rest, current, deformedVertices);
		float maxDisplacement = debug ? maxDisplacement(current) : 0f;

		// With no animations this clones the base vertices into the scratch model and resets its bounds
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
		System.arraycopy(deformedVertices[0], 0, sx, 0, vertexCount);
		System.arraycopy(deformedVertices[1], 0, sy, 0, vertexCount);
		System.arraycopy(deformedVertices[2], 0, sz, 0, vertexCount);

		if (debug)
		{
			log.debug("Npc {} anim {} frame {} pose {} frame {} source {} max driver displacement {}",
				npc.getId(), npc.getAnimation(), npc.getAnimationFrame(), npc.getPoseAnimation(),
				npc.getPoseAnimationFrame(), source, maxDisplacement);
		}
		return scratch;
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
}
