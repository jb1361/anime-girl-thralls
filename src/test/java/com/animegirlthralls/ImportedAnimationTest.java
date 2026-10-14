package com.animegirlthralls;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import net.runelite.http.api.RuneLiteAPI;
import java.util.Arrays;
import java.util.Collection;
import java.util.stream.Collectors;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import static org.junit.Assert.*;

@RunWith(Parameterized.class)
public class ImportedAnimationTest
{
	@Parameterized.Parameters(name = "{0}")
	public static Collection<Object[]> characters()
	{
		return Arrays.stream(AnimeGirl.values())
			.filter(character -> character != AnimeGirl.ORIGINAL)
			.map(character -> new Object[]{character})
			.collect(Collectors.toList());
	}

	@Parameterized.Parameter
	public AnimeGirl character;

	@Test
	public void importedClipsAnimateMatchingMeshesAndHaveValidHandAnchors() throws Exception
	{
		BlenderModel model;
		try (InputStream in = AnimeGirlThrallsPlugin.class.getResourceAsStream(character.getModelResource()))
		{
			assertNotNull(in);
			model = new ModelJsonDecoder(RuneLiteAPI.GSON).decode(new String(in.readAllBytes(), StandardCharsets.UTF_8), character.name());
		}
		for (ClipType type : ClipType.values())
		{
			VertexClip clip;
			try (InputStream in = AnimeGirlThrallsPlugin.class.getResourceAsStream(character.getClipResource(type)))
			{
				assertNotNull(character + " " + type, in);
				clip = VertexClip.read(in);
				assertEquals("Unexpected trailing clip bytes", -1, in.read());
			}
			int count = model.getVertices().length;
			assertEquals(count, clip.getVertexCount());
			assertEquals(type.isLoop(), clip.isLoop());
			assertEquals(2, clip.getAnchorCount());
			float[] x = new float[count], y = new float[count], z = new float[count];
			float[] first = new float[count];
			boolean moved = false;
			for (int f = 0; f < clip.getFrameCount(); f++)
			{
				float time = f / (float) clip.getFps();
				clip.sample(time, 1f, x, y, z);
				for (int v = 0; v < count; v++)
				{
					assertTrue(Float.isFinite(x[v]) && Float.isFinite(y[v]) && Float.isFinite(z[v]));
					if (f == 0) { first[v] = x[v] + y[v] * 3f + z[v] * 7f; }
					else if (Math.abs(first[v] - (x[v] + y[v] * 3f + z[v] * 7f)) > 0.5f) { moved = true; }
				}
				for (int hand = 0; hand < 2; hand++)
				{
					float[] anchor = new float[7];
					assertTrue(clip.sampleAnchor(hand, time, 1f, anchor));
					float length = 0;
					for (int i = 0; i < 7; i++)
					{
						assertTrue(Float.isFinite(anchor[i]));
						if (i >= 3) { length += anchor[i] * anchor[i]; }
					}
					assertEquals(1f, length, 0.001f);
					if (character.getModelResource().startsWith("v2/"))
					{
						float nearestSquared = Float.POSITIVE_INFINITY;
						for (int v = 0; v < count; v++)
						{
							float dx = x[v] - anchor[0], dy = y[v] - anchor[1], dz = z[v] - anchor[2];
							nearestSquared = Math.min(nearestSquared, dx * dx + dy * dy + dz * dz);
						}
						int minY = Integer.MAX_VALUE, maxY = Integer.MIN_VALUE;
						for (int[] vertex : model.getVertices())
						{
							minY = Math.min(minY, vertex[1]);
							maxY = Math.max(maxY, vertex[1]);
						}
						// Scale-independent guard against detached handles, including higher-precision meshes.
						float limit = (maxY - minY) * .03f;
						assertTrue(character + " " + type + " detached hand anchor", nearestSquared <= limit * limit);
					}
				}
			}
			assertTrue(character + " " + type + " has no movement", moved);
			if (!clip.isLoop() && character.getModelResource().startsWith("v2/"))
			{
				// Imported attacks recover exactly; the original clips also have independent hair/cloth sway.
				clip.sample(0f, 1f, x, y, z);
				float[] startX = x.clone(), startY = y.clone(), startZ = z.clone();
				clip.sample(clip.getDuration(), 1f, x, y, z);
				assertArrayEquals(character + " " + type + " recovery X", startX, x, 1f);
				assertArrayEquals(character + " " + type + " recovery Y", startY, y, 1f);
				assertArrayEquals(character + " " + type + " recovery Z", startZ, z, 1f);
			}

		}
	}
}
