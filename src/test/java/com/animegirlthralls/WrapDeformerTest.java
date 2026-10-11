package com.animegirlthralls;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import org.junit.Test;

public class WrapDeformerTest
{
	// A driver model twice the size of the custom model, same shape: a unit cube's corners
	private static final float[][] DRIVER = {
		{0, 200, 0, 200, 0, 200, 0, 200},
		{0, 0, -200, -200, 0, 0, -200, -200},
		{0, 0, 0, 0, 200, 200, 200, 200},
	};
	private static final float[][] CUSTOM = {
		{0, 100, 0, 100, 0, 100, 0, 100, 50},
		{0, 0, -100, -100, 0, 0, -100, -100, -50},
		{0, 0, 0, 0, 100, 100, 100, 100, 50},
	};

	private static float[][] copy(float[][] v)
	{
		return new float[][]{v[0].clone(), v[1].clone(), v[2].clone()};
	}

	@Test
	public void restPoseIsUnchanged()
	{
		WrapDeformer deformer = WrapDeformer.bind(CUSTOM, DRIVER, 4);
		float[][] out = copy(CUSTOM);
		deformer.apply(CUSTOM, DRIVER, DRIVER, out);
		for (int axis = 0; axis < 3; axis++)
		{
			assertArrayEquals(CUSTOM[axis], out[axis], 1e-4f);
		}
	}

	@Test
	public void translationIsScaledByHeightRatio()
	{
		WrapDeformer deformer = WrapDeformer.bind(CUSTOM, DRIVER, 4);
		assertEquals(0.5f, deformer.getScale(), 1e-6f);

		float[][] animated = copy(DRIVER);
		for (int i = 0; i < animated[0].length; i++)
		{
			animated[0][i] += 40;
			animated[1][i] -= 20;
		}

		float[][] out = copy(CUSTOM);
		deformer.apply(CUSTOM, DRIVER, animated, out);
		for (int i = 0; i < out[0].length; i++)
		{
			assertEquals(CUSTOM[0][i] + 20, out[0][i], 1e-3f);
			assertEquals(CUSTOM[1][i] - 10, out[1][i], 1e-3f);
			assertEquals(CUSTOM[2][i], out[2][i], 1e-3f);
		}
	}

	@Test
	public void nearestCornerFollowsItsDriver()
	{
		WrapDeformer deformer = WrapDeformer.bind(CUSTOM, DRIVER, 1);

		// Move only the driver corner at (200, -200, 200), which binds the custom corner (100, -100, 100)
		float[][] animated = copy(DRIVER);
		animated[1][7] -= 100;

		float[][] out = copy(CUSTOM);
		deformer.apply(CUSTOM, DRIVER, animated, out);
		assertEquals(-150f, out[1][7], 1e-3f);
		assertEquals(0f, out[1][0], 1e-3f);
	}
}
