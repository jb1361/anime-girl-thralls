package com.animegirlthralls;

/**
 * Places an item's inventory model into a canonical hand frame from its shape alone, so any bow,
 * staff or one-handed weapon can be gripped without per-item tuning.
 * <p>
 * Hand frame: x is the fist axis (little finger to index finger, "up" when the thumb is up), y runs
 * from the wrist to the knuckles, z is x cross y, and the origin is the middle of the fist. The
 * weapon's long axis, found by a principal axis fit, is laid along x. A bow's belly, the side away
 * from the string, faces +y; a staff's heavier end, the head, points to +x; a melee weapon is held
 * near its lighter end with the heavy end pointing to +x.
 */
final class WeaponAlign
{
	enum Kind
	{
		BOW,
		STAFF,
		MELEE,
	}

	private WeaponAlign()
	{
	}

	/**
	 * @param x model vertex coordinates
	 * @return the vertices in hand space, as {x[], y[], z[]}
	 */
	static float[][] toHandSpace(float[] x, float[] y, float[] z, int count, Kind kind)
	{
		double[] c = new double[3];
		for (int i = 0; i < count; i++)
		{
			c[0] += x[i];
			c[1] += y[i];
			c[2] += z[i];
		}
		for (int k = 0; k < 3; k++)
		{
			c[k] /= count;
		}

		double[][] cov = new double[3][3];
		for (int i = 0; i < count; i++)
		{
			double dx = x[i] - c[0];
			double dy = y[i] - c[1];
			double dz = z[i] - c[2];
			cov[0][0] += dx * dx;
			cov[0][1] += dx * dy;
			cov[0][2] += dx * dz;
			cov[1][1] += dy * dy;
			cov[1][2] += dy * dz;
			cov[2][2] += dz * dz;
		}
		cov[1][0] = cov[0][1];
		cov[2][0] = cov[0][2];
		cov[2][1] = cov[1][2];

		double[][] axes = principalAxes(cov);
		double[] e1 = axes[0];
		double[] e2 = axes[1];

		// Coordinates along and across the long axis
		double[] along = new double[count];
		double[][] perp = new double[count][3];
		double lo = Double.MAX_VALUE;
		double hi = -Double.MAX_VALUE;
		for (int i = 0; i < count; i++)
		{
			double dx = x[i] - c[0];
			double dy = y[i] - c[1];
			double dz = z[i] - c[2];
			double a = dx * e1[0] + dy * e1[1] + dz * e1[2];
			along[i] = a;
			perp[i][0] = dx - a * e1[0];
			perp[i][1] = dy - a * e1[1];
			perp[i][2] = dz - a * e1[2];
			lo = Math.min(lo, a);
			hi = Math.max(hi, a);
		}
		double length = hi - lo;

		double[] xAxis;
		double[] yAxis;
		double gripAlong;
		if (kind == Kind.BOW)
		{
			// The tips lie on the string chord; the limbs and grip bulge toward the belly
			double[] tips = new double[3];
			int tipCount = 0;
			double[] mean = new double[3];
			for (int i = 0; i < count; i++)
			{
				for (int k = 0; k < 3; k++)
				{
					mean[k] += perp[i][k];
				}
				if (along[i] > hi - 0.08 * length || along[i] < lo + 0.08 * length)
				{
					for (int k = 0; k < 3; k++)
					{
						tips[k] += perp[i][k];
					}
					tipCount++;
				}
			}
			double[] belly = new double[3];
			for (int k = 0; k < 3; k++)
			{
				belly[k] = mean[k] / count - (tipCount > 0 ? tips[k] / tipCount : 0);
			}
			double dot = dot(belly, e1);
			for (int k = 0; k < 3; k++)
			{
				belly[k] -= dot * e1[k];
			}
			xAxis = e1;
			yAxis = norm(belly) > 1e-6 ? belly : e2;
			gripAlong = (lo + hi) / 2;
		}
		else
		{
			// The head is the end with the larger cross section
			double topR = 0;
			double botR = 0;
			int topN = 0;
			int botN = 0;
			for (int i = 0; i < count; i++)
			{
				double r = norm(perp[i]);
				if (along[i] > hi - 0.25 * length)
				{
					topR += r;
					topN++;
				}
				else if (along[i] < lo + 0.25 * length)
				{
					botR += r;
					botN++;
				}
			}
			double headSign = (topN > 0 ? topR / topN : 0) >= (botN > 0 ? botR / botN : 0) ? 1 : -1;
			xAxis = scale(e1, headSign);
			yAxis = e2;
			double fromBottom = kind == Kind.STAFF ? 0.42 : 0.12;
			gripAlong = headSign > 0 ? lo + fromBottom * length : hi - fromBottom * length;
		}

		xAxis = normalize(xAxis);
		double d = dot(yAxis, xAxis);
		yAxis = normalize(new double[]{yAxis[0] - d * xAxis[0], yAxis[1] - d * xAxis[1], yAxis[2] - d * xAxis[2]});
		double[] zAxis = cross(xAxis, yAxis);

		double[] grip = {c[0] + e1[0] * gripAlong, c[1] + e1[1] * gripAlong, c[2] + e1[2] * gripAlong};
		float[][] out = new float[3][count];
		for (int i = 0; i < count; i++)
		{
			double dx = x[i] - grip[0];
			double dy = y[i] - grip[1];
			double dz = z[i] - grip[2];
			out[0][i] = (float) (dx * xAxis[0] + dy * xAxis[1] + dz * xAxis[2]);
			out[1][i] = (float) (dx * yAxis[0] + dy * yAxis[1] + dz * yAxis[2]);
			out[2][i] = (float) (dx * zAxis[0] + dy * zAxis[1] + dz * zAxis[2]);
		}
		return out;
	}

	/**
	 * Eigenvectors of a symmetric 3x3 matrix by Jacobi rotation, largest eigenvalue first.
	 */
	static double[][] principalAxes(double[][] m)
	{
		double[][] a = {m[0].clone(), m[1].clone(), m[2].clone()};
		double[][] v = {{1, 0, 0}, {0, 1, 0}, {0, 0, 1}};
		for (int sweep = 0; sweep < 50; sweep++)
		{
			double off = Math.abs(a[0][1]) + Math.abs(a[0][2]) + Math.abs(a[1][2]);
			if (off < 1e-12)
			{
				break;
			}
			for (int p = 0; p < 3; p++)
			{
				for (int q = p + 1; q < 3; q++)
				{
					if (Math.abs(a[p][q]) < 1e-15)
					{
						continue;
					}
					double theta = (a[q][q] - a[p][p]) / (2 * a[p][q]);
					double t = Math.signum(theta) / (Math.abs(theta) + Math.sqrt(theta * theta + 1));
					if (theta == 0)
					{
						t = 1;
					}
					double cs = 1 / Math.sqrt(t * t + 1);
					double sn = t * cs;
					for (int k = 0; k < 3; k++)
					{
						double akp = a[k][p];
						double akq = a[k][q];
						a[k][p] = cs * akp - sn * akq;
						a[k][q] = sn * akp + cs * akq;
					}
					for (int k = 0; k < 3; k++)
					{
						double apk = a[p][k];
						double aqk = a[q][k];
						a[p][k] = cs * apk - sn * aqk;
						a[q][k] = sn * apk + cs * aqk;
					}
					for (int k = 0; k < 3; k++)
					{
						double vkp = v[k][p];
						double vkq = v[k][q];
						v[k][p] = cs * vkp - sn * vkq;
						v[k][q] = sn * vkp + cs * vkq;
					}
				}
			}
		}

		Integer[] order = {0, 1, 2};
		java.util.Arrays.sort(order, (i, j) -> Double.compare(a[j][j], a[i][i]));
		double[][] axes = new double[3][3];
		for (int n = 0; n < 3; n++)
		{
			int col = order[n];
			axes[n] = new double[]{v[0][col], v[1][col], v[2][col]};
		}
		return axes;
	}

	private static double dot(double[] a, double[] b)
	{
		return a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
	}

	private static double norm(double[] a)
	{
		return Math.sqrt(dot(a, a));
	}

	private static double[] normalize(double[] a)
	{
		double n = norm(a);
		return n > 0 ? scale(a, 1 / n) : new double[]{1, 0, 0};
	}

	private static double[] scale(double[] a, double s)
	{
		return new double[]{a[0] * s, a[1] * s, a[2] * s};
	}

	private static double[] cross(double[] a, double[] b)
	{
		return new double[]{
			a[1] * b[2] - a[2] * b[1],
			a[2] * b[0] - a[0] * b[2],
			a[0] * b[1] - a[1] * b[0],
		};
	}
}
