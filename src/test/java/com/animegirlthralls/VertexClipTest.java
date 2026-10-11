package com.animegirlthralls;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.junit.Test;

public class VertexClipTest
{
	// Two vertices, three frames: vertex 0 rises 100 per frame on y, vertex 1 stays put
	private static final short[] DATA = {
		0, 0, 0, 10, 20, 30,
		0, -100, 0, 10, 20, 30,
		0, -200, 0, 10, 20, 30,
	};
	// One anchor per frame: moves along x, rotates from identity to 90 degrees about z
	private static final float HALF = (float) Math.sqrt(0.5);
	private static final float[] ANCHORS = {
		0, 0, 0, 1, 0, 0, 0,
		100, 0, 0, HALF, 0, 0, HALF,
		200, 0, 0, 0, 0, 0, 1,
	};

	private static byte[] encode(int version, boolean loop)
	{
		boolean anchors = version >= 2;
		ByteBuffer b = ByteBuffer.allocate(12 + DATA.length * 2 + (anchors ? ANCHORS.length * 4 : 0)).order(ByteOrder.LITTLE_ENDIAN);
		b.put((byte) 'A').put((byte) 'G').put((byte) 'T').put((byte) 'C');
		b.put((byte) version).put((byte) 10).put((byte) (loop ? 1 : 0)).put((byte) (anchors ? 1 : 0));
		b.putShort((short) 2).putShort((short) 3);
		for (short s : DATA)
		{
			b.putShort(s);
		}
		if (anchors)
		{
			for (float f : ANCHORS)
			{
				b.putFloat(f);
			}
		}
		return b.array();
	}

	@Test
	public void readsHeaderAndData() throws IOException
	{
		VertexClip clip = VertexClip.read(new ByteArrayInputStream(encode(1, true)));
		assertEquals(10, clip.getFps());
		assertTrue(clip.isLoop());
		assertEquals(2, clip.getVertexCount());
		assertEquals(3, clip.getFrameCount());
		assertEquals(0, clip.getAnchorCount());
		assertEquals(0.3f, clip.getDuration(), 1e-6f);
	}

	@Test
	public void interpolatesAndScales() throws IOException
	{
		VertexClip clip = VertexClip.read(new ByteArrayInputStream(encode(1, false)));
		assertEquals(0.2f, clip.getDuration(), 1e-6f);
		float[] x = new float[2];
		float[] y = new float[2];
		float[] z = new float[2];

		clip.sample(0.05f, 2f, x, y, z);
		assertEquals(-100f, y[0], 1e-3f);
		assertEquals(20f, x[1], 1e-3f);
		assertEquals(60f, z[1], 1e-3f);

		// Non-looping clips hold the last frame
		clip.sample(5f, 1f, x, y, z);
		assertEquals(-200f, y[0], 1e-3f);
	}

	@Test
	public void loopsBackToFirstFrame() throws IOException
	{
		VertexClip clip = VertexClip.read(new ByteArrayInputStream(encode(1, true)));
		float[] x = new float[2];
		float[] y = new float[2];
		float[] z = new float[2];

		// Halfway between the last frame and the wrap to frame 0
		clip.sample(0.25f, 1f, x, y, z);
		assertEquals(-100f, y[0], 1e-3f);

		clip.sample(0.3f, 1f, x, y, z);
		assertEquals(0f, y[0], 1e-3f);
	}

	@Test
	public void readsAndInterpolatesAnchors() throws IOException
	{
		VertexClip clip = VertexClip.read(new ByteArrayInputStream(encode(2, false)));
		assertEquals(1, clip.getAnchorCount());
		float[] a = new float[VertexClip.ANCHOR_FLOATS];

		assertTrue(clip.sampleAnchor(0, 0.05f, 2f, a));
		assertEquals(100f, a[0], 1e-3f);
		// Halfway between identity and 45 degrees about z, renormalised
		double len = Math.sqrt(a[3] * a[3] + a[4] * a[4] + a[5] * a[5] + a[6] * a[6]);
		assertEquals(1.0, len, 1e-5);
		assertTrue(a[6] > 0 && a[6] < HALF);
		assertEquals(0f, a[4], 1e-6f);

		assertFalse(clip.sampleAnchor(1, 0f, 1f, a));
	}

	@Test(expected = IOException.class)
	public void rejectsWrongMagic() throws IOException
	{
		byte[] bytes = encode(1, true);
		bytes[0] = 'X';
		VertexClip.read(new ByteArrayInputStream(bytes));
	}
}
