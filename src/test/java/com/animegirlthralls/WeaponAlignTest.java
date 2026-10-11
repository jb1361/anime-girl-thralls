package com.animegirlthralls;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

public class WeaponAlignTest
{
	private static float[][] toArrays(List<float[]> points)
	{
		float[][] out = new float[3][points.size()];
		for (int i = 0; i < points.size(); i++)
		{
			out[0][i] = points.get(i)[0];
			out[1][i] = points.get(i)[1];
			out[2][i] = points.get(i)[2];
		}
		return out;
	}

	private static float min(float[] v)
	{
		float m = Float.MAX_VALUE;
		for (float f : v)
		{
			m = Math.min(m, f);
		}
		return m;
	}

	private static float max(float[] v)
	{
		float m = -Float.MAX_VALUE;
		for (float f : v)
		{
			m = Math.max(m, f);
		}
		return m;
	}

	@Test
	public void staffHeadPointsUpAndIsGrippedBelowMiddle()
	{
		// A thin rod along z from -50 to 100 with a bulky head at the +z end
		List<float[]> pts = new ArrayList<>();
		for (int z = -50; z <= 100; z += 5)
		{
			pts.add(new float[]{2, 0, z});
			pts.add(new float[]{-2, 0, z});
			pts.add(new float[]{0, 2, z});
			pts.add(new float[]{0, -2, z});
		}
		for (int k = 0; k < 16; k++)
		{
			double a = k * Math.PI / 8;
			pts.add(new float[]{(float) (12 * Math.cos(a)), (float) (12 * Math.sin(a)), 95});
			pts.add(new float[]{(float) (12 * Math.cos(a)), (float) (12 * Math.sin(a)), 100});
		}
		float[][] v = toArrays(pts);
		float[][] hand = WeaponAlign.toHandSpace(v[0], v[1], v[2], v[0].length, WeaponAlign.Kind.STAFF);

		// Long axis along hand x, head toward +x, grip 42% up from the bottom
		assertEquals(150f, max(hand[0]) - min(hand[0]), 1f);
		assertEquals(-0.42f * 150f, min(hand[0]), 1.5f);
		assertEquals(0.58f * 150f, max(hand[0]), 1.5f);
		assertTrue(max(hand[1]) - min(hand[1]) < 30f);
	}

	@Test
	public void bowBellyFacesPlusYAndIsGrippedAtCentre()
	{
		// An arc in the x/y plane bulging toward +y, with a straight string along y = 0
		List<float[]> pts = new ArrayList<>();
		for (int x = -40; x <= 40; x += 4)
		{
			float bulge = 15f * (1f - (x / 40f) * (x / 40f));
			pts.add(new float[]{x, bulge, 1});
			pts.add(new float[]{x, bulge, -1});
			pts.add(new float[]{x, 0, 0});
		}
		float[][] v = toArrays(pts);
		float[][] hand = WeaponAlign.toHandSpace(v[0], v[1], v[2], v[0].length, WeaponAlign.Kind.BOW);

		assertEquals(-40f, min(hand[0]), 1f);
		assertEquals(40f, max(hand[0]), 1f);
		// Grip sits at the centroid, between string and belly; the bow is 15 deep and the belly side
		// (further from the string) extends further in +y
		assertEquals(15f, max(hand[1]) - min(hand[1]), 1f);
		assertTrue("belly should face +y", max(hand[1]) > -min(hand[1]) + 1f);
	}

	@Test
	public void principalAxesAreSortedAndOrthonormal()
	{
		double[][] cov = {{4, 1, 0}, {1, 3, 0}, {0, 0, 1}};
		double[][] axes = WeaponAlign.principalAxes(cov);
		for (int i = 0; i < 3; i++)
		{
			double len = 0;
			for (int k = 0; k < 3; k++)
			{
				len += axes[i][k] * axes[i][k];
			}
			assertEquals(1.0, len, 1e-9);
		}
		// Largest eigenvalue direction lies mostly along x, smallest along z
		assertTrue(Math.abs(axes[0][0]) > Math.abs(axes[0][1]));
		assertEquals(1.0, Math.abs(axes[2][2]), 1e-9);
	}
}
