package com.animegirlthralls;

/**
 * Binds the vertices of a custom model to the nearest vertices of a driver model, then moves each
 * custom vertex by the weighted displacement of its driver vertices. Both models are compared in
 * normalised bounding box space so their sizes and proportions do not need to match, and displacements
 * are scaled by the ratio of the two models' heights.
 */
final class WrapDeformer
{
	private final int[][] indices;
	private final float[][] weights;
	private final float scale;

	private WrapDeformer(int[][] indices, float[][] weights, float scale)
	{
		this.indices = indices;
		this.weights = weights;
		this.scale = scale;
	}

	float getScale()
	{
		return scale;
	}

	/**
	 * @param base the custom model's rest vertices
	 * @param rest the driver model's rest vertices
	 * @param neighbours how many driver vertices each custom vertex follows
	 */
	static WrapDeformer bind(float[][] base, float[][] rest, int neighbours)
	{
		int n = base[0].length;
		int m = rest[0].length;
		int k = Math.min(neighbours, m);

		float[][] nb = normalise(base);
		float[][] nr = normalise(rest);

		int[][] indices = new int[n][k];
		float[][] weights = new float[n][k];
		float[] best = new float[k];
		for (int i = 0; i < n; i++)
		{
			int[] idx = indices[i];
			int found = 0;
			float bx = nb[0][i];
			float by = nb[1][i];
			float bz = nb[2][i];
			for (int j = 0; j < m; j++)
			{
				float dx = nr[0][j] - bx;
				float dy = nr[1][j] - by;
				float dz = nr[2][j] - bz;
				float d = dx * dx + dy * dy + dz * dz;
				if (found == k && d >= best[k - 1])
				{
					continue;
				}

				// insert into the sorted k-best list
				int pos = found < k ? found : k - 1;
				while (pos > 0 && best[pos - 1] > d)
				{
					best[pos] = best[pos - 1];
					idx[pos] = idx[pos - 1];
					pos--;
				}
				best[pos] = d;
				idx[pos] = j;
				if (found < k)
				{
					found++;
				}
			}

			float[] w = weights[i];
			float total = 0f;
			for (int j = 0; j < found; j++)
			{
				w[j] = 1f / ((float) Math.sqrt(best[j]) + 1e-3f);
				total += w[j];
			}
			for (int j = 0; j < found; j++)
			{
				w[j] /= total;
			}
		}

		float baseHeight = extent(base[1]);
		float restHeight = extent(rest[1]);
		float scale = restHeight > 0f ? baseHeight / restHeight : 1f;
		return new WrapDeformer(indices, weights, scale);
	}

	/**
	 * Writes the deformed custom vertices to out.
	 *
	 * @param base     the custom model's rest vertices
	 * @param rest     the driver model's rest vertices
	 * @param animated the driver model's current vertices
	 * @param out      arrays to receive the deformed custom vertices
	 */
	void apply(float[][] base, float[][] rest, float[][] animated, float[][] out)
	{
		int n = indices.length;
		for (int i = 0; i < n; i++)
		{
			int[] idx = indices[i];
			float[] w = weights[i];
			float dx = 0f;
			float dy = 0f;
			float dz = 0f;
			for (int j = 0; j < idx.length; j++)
			{
				int v = idx[j];
				dx += w[j] * (animated[0][v] - rest[0][v]);
				dy += w[j] * (animated[1][v] - rest[1][v]);
				dz += w[j] * (animated[2][v] - rest[2][v]);
			}
			out[0][i] = base[0][i] + scale * dx;
			out[1][i] = base[1][i] + scale * dy;
			out[2][i] = base[2][i] + scale * dz;
		}
	}

	private static float[][] normalise(float[][] v)
	{
		float[][] out = new float[3][];
		for (int axis = 0; axis < 3; axis++)
		{
			float min = Float.MAX_VALUE;
			float max = -Float.MAX_VALUE;
			for (float f : v[axis])
			{
				min = Math.min(min, f);
				max = Math.max(max, f);
			}
			float range = max - min;
			float[] o = new float[v[axis].length];
			for (int i = 0; i < o.length; i++)
			{
				o[i] = range > 0f ? (v[axis][i] - min) / range : 0.5f;
			}
			out[axis] = o;
		}
		return out;
	}

	private static float extent(float[] v)
	{
		float min = Float.MAX_VALUE;
		float max = -Float.MAX_VALUE;
		for (float f : v)
		{
			min = Math.min(min, f);
			max = Math.max(max, f);
		}
		return max - min;
	}
}
