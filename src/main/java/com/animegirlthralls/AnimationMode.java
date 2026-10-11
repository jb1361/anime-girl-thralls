package com.animegirlthralls;

/**
 * How a replacement model is animated.
 */
public enum AnimationMode
{
	/** Play the girl's own baked clips, cued by what the thrall is doing. */
	CUSTOM,
	/** Deform the model to follow the thrall's own animation. */
	MIMIC_THRALL,
	/** Draw the model in its rest pose. */
	NONE,
}
