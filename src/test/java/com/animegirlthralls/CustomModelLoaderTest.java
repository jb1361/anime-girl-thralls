package com.animegirlthralls;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import net.runelite.http.api.RuneLiteAPI;
import org.junit.Test;

public class CustomModelLoaderTest
{
	private static void assertFits(int faces, int vertices)
	{
		// The two fixed building blocks contribute 3 faces and 3 vertices
		int[] polys = CustomModelLoader.countPolys(faces - 3, vertices - 3);
		assertNotNull("no block combination for " + faces + " faces / " + vertices + " vertices", polys);
		int mergedFaces = 3 + polys[0] + polys[1] + polys[2];
		int mergedVertices = 3 + polys[0] * 3 + polys[1] * 2;
		assertEquals(faces, mergedFaces);
		assertEquals(vertices, mergedVertices);
	}

	@Test
	public void countPolysExactRatios()
	{
		assertArrayEquals(new int[]{4, 0, 0}, CustomModelLoader.countPolys(4, 12));
		assertArrayEquals(new int[]{0, 0, 0}, CustomModelLoader.countPolys(0, 0));
		assertArrayEquals(new int[]{0, 0, 5}, CustomModelLoader.countPolys(5, 0));
	}

	@Test
	public void countPolysMixedRatios()
	{
		for (int faces = 1; faces < 400; faces++)
		{
			for (int vertices = 3; vertices <= faces * 3; vertices++)
			{
				assertFits(faces + 3, vertices + 3);
			}
		}
	}

	@Test
	public void countPolysRejectsUnusedVertices()
	{
		// More vertices than three per face cannot be produced from one-face blocks
		assertNull(CustomModelLoader.countPolys(1, 4));
		assertNull(CustomModelLoader.countPolys(0, 2));
	}

	@Test
	public void bundledModelsFitClientLimits() throws Exception
	{
		for (AnimeGirl girl : AnimeGirl.values())
		{
			if (girl.getModelResource() != null)
			{
				BlenderModel bm = assertModelFits(girl.getModelResource());
				assertClipsMatch(girl, bm);
				assertTrue(girl.name(), girl.getBodyFraction() > 0f && girl.getBodyFraction() <= 1f);
			}
		}
	}

	private static void assertClipsMatch(AnimeGirl girl, BlenderModel bm) throws Exception
	{
		for (ClipType type : ClipType.values())
		{
			String resource = girl.getClipResource(type);
			try (InputStream in = AnimeGirlThrallsPlugin.class.getResourceAsStream(resource))
			{
				assertNotNull("Missing animation: " + resource, in);
				VertexClip clip = VertexClip.read(in);
				assertEquals(resource, bm.getVertices().length, clip.getVertexCount());
				assertTrue(resource, clip.getFrameCount() > 1);
				assertEquals(resource, type.isLoop(), clip.isLoop());
				assertEquals(resource, 2, clip.getAnchorCount());
			}
		}
	}

	private static BlenderModel assertModelFits(String resource) throws Exception
	{
		BlenderModel bm;
		try (InputStream in = AnimeGirlThrallsPlugin.class.getResourceAsStream(resource))
		{
			assertNotNull(resource, in);
			bm = new ModelJsonDecoder(RuneLiteAPI.GSON).decode(new String(in.readAllBytes(), StandardCharsets.UTF_8), resource);
		}

		int vertices = bm.getVertices().length;
		int faces = bm.getFaces().length;
		assertTrue("too many vertices: " + vertices, vertices <= CustomModelLoader.MAX_VERTICES);
		assertTrue("too many faces: " + faces, faces <= CustomModelLoader.MAX_FACES);
		int[] colourIndices = bm.isUseVertexColours() ? bm.getVertexColourIndex() : bm.getFaceColourIndex();
		double[][] palette = bm.isUseVertexColours() ? bm.getVertexColours() : bm.getFaceColours();
		assertEquals(resource, faces * (bm.isUseVertexColours() ? 3 : 1), colourIndices.length);
		assertEquals(faces, bm.getPriorities().length);
		for (int[] face : bm.getFaces())
		{
			for (int index : face)
			{
				assertTrue(index >= 0 && index < vertices);
			}
		}
		for (int index : colourIndices)
		{
			assertTrue(resource, index >= 0 && index < palette.length);
		}
		assertFits(faces, vertices);
		return bm;
	}
}
