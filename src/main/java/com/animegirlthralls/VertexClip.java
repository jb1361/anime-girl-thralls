package com.animegirlthralls;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import lombok.Getter;

/**
 * A baked vertex animation: every frame stores a full set of vertex positions for the model, in the
 * same coordinates as the model's JSON, plus the transform of each hand so weapons can follow it.
 * Frames are sampled with linear interpolation.
 * <p>
 * File format (little endian): magic "AGTC", version u8, fps u8, loop u8, anchor count u8,
 * vertex count u16, frame count u16, then frames x vertices x (x, y, z) as int16, then
 * frames x anchors x (px, py, pz, qw, qx, qy, qz) as float32. Version 1 files have no anchors.
 * Anchor 0 is the left hand and anchor 1 the right hand.
 */
@Getter
final class VertexClip
{
	static final int LEFT_HAND = 0;
	static final int RIGHT_HAND = 1;
	/** Floats per anchor: position xyz and rotation quaternion wxyz. */
	static final int ANCHOR_FLOATS = 7;

	private static final int MAGIC = 'A' | 'G' << 8 | 'T' << 16 | 'C' << 24;
	private static final int HEADER_BYTES = 12;

	private final int fps;
	private final boolean loop;
	private final int vertexCount;
	private final int frameCount;
	private final int anchorCount;
	private final short[] data;
	private final float[] anchors;

	VertexClip(int fps, boolean loop, int vertexCount, int frameCount, short[] data, int anchorCount, float[] anchors)
	{
		if (fps <= 0 || vertexCount <= 0 || frameCount <= 0 || data.length != frameCount * vertexCount * 3
			|| anchorCount < 0 || anchors.length != frameCount * anchorCount * ANCHOR_FLOATS)
		{
			throw new IllegalArgumentException("Invalid clip dimensions");
		}
		this.fps = fps;
		this.loop = loop;
		this.vertexCount = vertexCount;
		this.frameCount = frameCount;
		this.data = data;
		this.anchorCount = anchorCount;
		this.anchors = anchors;
	}

	static VertexClip read(InputStream in) throws IOException
	{
		DataInputStream din = new DataInputStream(in);
		byte[] header = new byte[HEADER_BYTES];
		din.readFully(header);
		ByteBuffer hb = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN);
		if (hb.getInt() != MAGIC)
		{
			throw new IOException("Not a vertex clip");
		}
		int version = hb.get() & 0xFF;
		if (version != 1 && version != 2)
		{
			throw new IOException("Unsupported clip version " + version);
		}
		int fps = hb.get() & 0xFF;
		boolean loop = hb.get() != 0;
		int anchorCount = version >= 2 ? hb.get() & 0xFF : 0;
		if (version < 2)
		{
			hb.get();
		}
		int vertexCount = hb.getShort() & 0xFFFF;
		int frameCount = hb.getShort() & 0xFFFF;

		int values = frameCount * vertexCount * 3;
		byte[] body = new byte[values * 2];
		din.readFully(body);
		short[] data = new short[values];
		ByteBuffer.wrap(body).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(data);

		int anchorValues = frameCount * anchorCount * ANCHOR_FLOATS;
		byte[] anchorBytes = new byte[anchorValues * 4];
		din.readFully(anchorBytes);
		float[] anchors = new float[anchorValues];
		ByteBuffer.wrap(anchorBytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(anchors);

		return new VertexClip(fps, loop, vertexCount, frameCount, data, anchorCount, anchors);
	}

	/** Length of the clip in seconds. A looping clip wraps after this long. */
	float getDuration()
	{
		return (loop ? frameCount : frameCount - 1) / (float) fps;
	}

	/** Frame pair and blend weight for a time. Returns {a, b} and writes the weight to t[0]. */
	private int[] frameAt(float seconds, float[] t)
	{
		float f = seconds * fps;
		if (loop)
		{
			f = f % frameCount;
			if (f < 0)
			{
				f += frameCount;
			}
			int a = (int) f;
			t[0] = f - a;
			return new int[]{a, (a + 1) % frameCount};
		}
		if (f <= 0)
		{
			t[0] = 0;
			return new int[]{0, 0};
		}
		if (f >= frameCount - 1)
		{
			t[0] = 0;
			return new int[]{frameCount - 1, frameCount - 1};
		}
		int a = (int) f;
		t[0] = f - a;
		return new int[]{a, a + 1};
	}

	/**
	 * Writes the interpolated pose at the given time into the output arrays, multiplied by scale.
	 * Times past the end wrap for looping clips and hold the last frame otherwise.
	 */
	void sample(float seconds, float scale, float[] outX, float[] outY, float[] outZ)
	{
		float[] tt = new float[1];
		int[] ab = frameAt(seconds, tt);
		float t = tt[0];
		float s = 1 - t;
		int oa = ab[0] * vertexCount * 3;
		int ob = ab[1] * vertexCount * 3;
		for (int i = 0; i < vertexCount; i++)
		{
			int ia = oa + i * 3;
			int ib = ob + i * 3;
			outX[i] = (data[ia] * s + data[ib] * t) * scale;
			outY[i] = (data[ia + 1] * s + data[ib + 1] * t) * scale;
			outZ[i] = (data[ia + 2] * s + data[ib + 2] * t) * scale;
		}
	}

	/**
	 * Writes the interpolated anchor at the given time: position xyz multiplied by scale, then the
	 * unit quaternion wxyz.
	 *
	 * @return false if the clip has no such anchor
	 */
	boolean sampleAnchor(int anchor, float seconds, float scale, float[] out)
	{
		if (anchor < 0 || anchor >= anchorCount)
		{
			return false;
		}
		float[] tt = new float[1];
		int[] ab = frameAt(seconds, tt);
		float t = tt[0];
		int oa = (ab[0] * anchorCount + anchor) * ANCHOR_FLOATS;
		int ob = (ab[1] * anchorCount + anchor) * ANCHOR_FLOATS;
		blendAnchor(anchors, oa, anchors, ob, t, out);
		for (int k = 0; k < 3; k++)
		{
			out[k] *= scale;
		}
		return true;
	}

	/**
	 * Linearly blends two anchors (position lerp, normalised quaternion lerp along the shorter arc).
	 */
	static void blendAnchor(float[] a, int oa, float[] b, int ob, float t, float[] out)
	{
		float s = 1 - t;
		for (int k = 0; k < 3; k++)
		{
			out[k] = a[oa + k] * s + b[ob + k] * t;
		}
		float dot = 0;
		for (int k = 3; k < ANCHOR_FLOATS; k++)
		{
			dot += a[oa + k] * b[ob + k];
		}
		float sign = dot < 0 ? -1 : 1;
		float len = 0;
		for (int k = 3; k < ANCHOR_FLOATS; k++)
		{
			out[k] = a[oa + k] * s + b[ob + k] * t * sign;
			len += out[k] * out[k];
		}
		len = (float) Math.sqrt(len);
		if (len > 1e-6f)
		{
			for (int k = 3; k < ANCHOR_FLOATS; k++)
			{
				out[k] /= len;
			}
		}
		else
		{
			out[3] = 1;
			out[4] = out[5] = out[6] = 0;
		}
	}
}
