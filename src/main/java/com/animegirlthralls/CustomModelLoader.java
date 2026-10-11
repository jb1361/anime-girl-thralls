package com.animegirlthralls;

import java.util.ArrayList;
import java.util.List;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.JagexColor;
import net.runelite.api.Model;
import net.runelite.api.ModelData;

/**
 * Builds a {@link Model} from a {@link BlenderModel}.
 * <p>
 * The client API has no way to create a {@link ModelData} from scratch, so this follows the approach
 * used by creators-kit: merge copies of tiny cache models until the merged model has exactly the
 * required number of vertices and faces, then overwrite its vertex, face and colour arrays in place.
 * {@link Client#mergeModels} de-duplicates vertices that share a position, which is what lets the
 * same one-face building block contribute three, two or zero new vertices depending on how it is
 * translated before merging.
 */
@Slf4j
@Singleton
class CustomModelLoader
{
	/** Models larger than this do not render fully in the client. */
	static final int MAX_VERTICES = 3900;
	static final int MAX_FACES = 6200;

	// Cache models used as building blocks. These are the same ids creators-kit uses.
	private static final int BASIC_MODEL = 823; // one face, three vertices
	private static final int TRANSPARENT_MODEL = 18871; // carries a face transparency array
	private static final int PRIORITY_MODEL = 6733; // carries a face render priority array

	// Lighting the client applies to npc models
	private static final int AMBIENT = 64;
	private static final int CONTRAST = 850;
	private static final int LIGHT_X = -30;
	private static final int LIGHT_Y = -50;
	private static final int LIGHT_Z = -30;

	private final Client client;

	@Inject
	CustomModelLoader(Client client)
	{
		this.client = client;
	}

	/**
	 * Builds a lit model. Must be called on the client thread.
	 *
	 * @param targetHeight height to scale the model to in local units, or 0 to keep its own size
	 * @return the model, or null if it could not be built
	 */
	Model build(BlenderModel bm, int targetHeight)
	{
		int[][] vertices = bm.getVertices();
		int[][] faces = bm.getFaces();
		if (vertices == null || faces == null || vertices.length < 3 || faces.length < 1)
		{
			log.warn("Blender model has no geometry");
			return null;
		}

		int vertexCount = vertices.length;
		int faceCount = faces.length;
		if (vertexCount > MAX_VERTICES || faceCount > MAX_FACES)
		{
			log.warn("Blender model has {} vertices and {} faces; the client only renders up to {} vertices and {} faces",
				vertexCount, faceCount, MAX_VERTICES, MAX_FACES);
		}

		// The priority and transparent building blocks together contribute 3 faces and 3 vertices
		int[] polys = countPolys(faceCount - 3, vertexCount - 3);
		if (polys == null)
		{
			log.warn("Cannot build a model with {} faces and {} vertices", faceCount, vertexCount);
			return null;
		}

		ModelData md = constructModel(polys);
		if (md == null)
		{
			log.debug("Building block models are not available yet");
			return null;
		}

		if (md.getVerticesCount() != vertexCount || md.getFaceCount() != faceCount)
		{
			log.warn("Merged model has {} vertices and {} faces, expected {} and {}",
				md.getVerticesCount(), md.getFaceCount(), vertexCount, faceCount);
			return null;
		}

		float scale = 1f;
		if (targetHeight > 0)
		{
			int minY = Integer.MAX_VALUE;
			int maxY = Integer.MIN_VALUE;
			for (int[] v : vertices)
			{
				minY = Math.min(minY, v[1]);
				maxY = Math.max(maxY, v[1]);
			}
			int height = maxY - minY;
			if (height > 0)
			{
				scale = (float) targetHeight / height;
			}
		}

		float[] vx = md.getVerticesX();
		float[] vy = md.getVerticesY();
		float[] vz = md.getVerticesZ();
		for (int i = 0; i < vertexCount; i++)
		{
			int[] v = vertices[i];
			vx[i] = v[0] * scale;
			vy[i] = v[1] * scale;
			vz[i] = v[2] * scale;
		}

		int[] f1 = md.getFaceIndices1();
		int[] f2 = md.getFaceIndices2();
		int[] f3 = md.getFaceIndices3();
		for (int i = 0; i < faceCount; i++)
		{
			int[] f = faces[i];
			f1[i] = f[0];
			f2[i] = f[1];
			f3[i] = f[2];
		}

		if (!bm.isUseVertexColours())
		{
			applyFaceColours(bm, md.getFaceColors(), md.getFaceTransparencies(), faceCount);
		}

		Model model = md.light(AMBIENT, CONTRAST, LIGHT_X, LIGHT_Y, LIGHT_Z);
		if (model == null)
		{
			return null;
		}

		if (bm.isUseVertexColours())
		{
			applyVertexColours(bm, model);
		}

		byte[] priorities = bm.getPriorities();
		byte[] facePriorities = model.getFaceRenderPriorities();
		if (priorities != null && facePriorities != null)
		{
			int n = Math.min(Math.min(priorities.length, facePriorities.length), faceCount);
			System.arraycopy(priorities, 0, facePriorities, 0, n);
		}

		return model;
	}

	/**
	 * Works out how many building blocks of each kind are needed so that the merged model ends up with
	 * exactly the requested number of extra faces and vertices.
	 *
	 * @return {blocks adding 3 vertices, blocks adding 2 vertices, blocks adding 0 vertices}, or null
	 */
	static int[] countPolys(int facesRemaining, int verticesRemaining)
	{
		if (facesRemaining < 0 || verticesRemaining < 0)
		{
			return null;
		}

		// Use as many three-vertex blocks as possible, leaving an even remainder for two-vertex blocks.
		// That minimises the number of blocks that carry vertices, leaving the most room for faces.
		int three = verticesRemaining / 3;
		if ((verticesRemaining - three * 3) % 2 != 0)
		{
			three--;
		}
		if (three < 0)
		{
			return null;
		}
		int two = (verticesRemaining - three * 3) / 2;
		int zero = facesRemaining - three - two;
		if (zero < 0)
		{
			return null;
		}
		return new int[]{three, two, zero};
	}

	private ModelData constructModel(int[] polys)
	{
		List<ModelData> parts = new ArrayList<>(polys[0] + polys[1] + polys[2] + 2);

		ModelData priority = client.loadModelData(PRIORITY_MODEL);
		ModelData transparent = client.loadModelData(TRANSPARENT_MODEL);
		if (priority == null || transparent == null)
		{
			return null;
		}

		// Collapse both fixed blocks onto the same three positions so together they add 3 faces and 3 vertices
		priority.cloneColors().cloneVertices();
		float[] vx = priority.getVerticesX();
		float[] vy = priority.getVerticesY();
		float[] vz = priority.getVerticesZ();
		if (vx.length < 6)
		{
			return null;
		}
		vx[0] = vx[1] = 64;
		vy[0] = vy[1] = 0;
		vz[0] = vz[1] = -64;
		vx[2] = vx[3] = -64;
		vy[2] = vy[3] = 0;
		vz[2] = vz[3] = 64;
		vx[4] = vx[5] = -64;
		vy[4] = vy[5] = 0;
		vz[4] = vz[5] = -64;
		parts.add(priority);

		transparent.cloneColors().cloneVertices().cloneTransparencies();
		vx = transparent.getVerticesX();
		vy = transparent.getVerticesY();
		vz = transparent.getVerticesZ();
		if (vx.length < 3)
		{
			return null;
		}
		vx[0] = 64;
		vy[0] = 0;
		vz[0] = -64;
		vx[1] = -64;
		vy[1] = 0;
		vz[1] = 64;
		vx[2] = -64;
		vy[2] = 0;
		vz[2] = -64;
		parts.add(transparent);

		// Each copy at a unique offset: +1 face, +3 vertices
		for (int i = 0; i < polys[0]; i++)
		{
			ModelData block = client.loadModelData(BASIC_MODEL);
			if (block == null)
			{
				return null;
			}
			block.cloneColors().cloneVertices().translate(i + 1, i + 1, i + 1);
			parts.add(block);
		}

		// Each copy shifted a tile along x shares one vertex with its neighbour: +1 face, +2 vertices
		for (int i = 0; i < polys[1]; i++)
		{
			ModelData block = client.loadModelData(BASIC_MODEL);
			if (block == null)
			{
				return null;
			}
			block.cloneColors().cloneVertices().translate((i + 1) * 128, 0, 0);
			parts.add(block);
		}

		// Untranslated copies all share the same vertices: +1 face, +0 vertices
		for (int i = 0; i < polys[2]; i++)
		{
			ModelData block = client.loadModelData(BASIC_MODEL);
			if (block == null)
			{
				return null;
			}
			block.cloneColors().cloneVertices();
			parts.add(block);
		}

		return client.mergeModels(parts.toArray(new ModelData[0]));
	}

	private static void applyFaceColours(BlenderModel bm, short[] faceColors, byte[] faceTransparencies, int faceCount)
	{
		double[][] palette = bm.getFaceColours();
		int[] index = bm.getFaceColourIndex();
		if (palette == null || index == null || faceColors == null)
		{
			return;
		}

		short[] colours = new short[palette.length];
		byte[] transparencies = new byte[palette.length];
		for (int i = 0; i < palette.length; i++)
		{
			colours[i] = packColour(palette[i]);
			transparencies[i] = packTransparency(palette[i]);
		}

		int n = Math.min(Math.min(faceCount, index.length), faceColors.length);
		for (int i = 0; i < n; i++)
		{
			int k = index[i];
			if (k < 0 || k >= colours.length)
			{
				continue;
			}
			faceColors[i] = colours[k];
			if (faceTransparencies != null && i < faceTransparencies.length)
			{
				faceTransparencies[i] = transparencies[k];
			}
		}
	}

	private static void applyVertexColours(BlenderModel bm, Model model)
	{
		double[][] palette = bm.getVertexColours();
		int[] index = bm.getVertexColourIndex();
		if (palette == null || index == null)
		{
			return;
		}

		int[] colours = new int[palette.length];
		byte[] transparencies = new byte[palette.length];
		for (int i = 0; i < palette.length; i++)
		{
			colours[i] = packColour(palette[i]) & 0xFFFF;
			transparencies[i] = packTransparency(palette[i]);
		}

		int[] fc1 = model.getFaceColors1();
		int[] fc2 = model.getFaceColors2();
		int[] fc3 = model.getFaceColors3();
		byte[] ftp = model.getFaceTransparencies();
		int faceCount = model.getFaceCount();
		for (int i = 0; i < faceCount && i * 3 + 2 < index.length; i++)
		{
			int a = index[i * 3];
			int b = index[i * 3 + 1];
			int c = index[i * 3 + 2];
			if (a < 0 || b < 0 || c < 0 || a >= colours.length || b >= colours.length || c >= colours.length)
			{
				continue;
			}
			fc1[i] = colours[a];
			fc2[i] = colours[b];
			fc3[i] = colours[c];
			if (ftp != null)
			{
				ftp[i] = (byte) ((transparencies[a] + transparencies[b] + transparencies[c]) / 3);
			}
		}
	}

	// Colours are stored as {hue, luminance, saturation, alpha} in the 0-1 range, matching creators-kit
	private static short packColour(double[] colour)
	{
		int hue = clamp((int) (63 - colour[0] * 63), 0, JagexColor.HUE_MAX);
		int lum = clamp((int) (colour[1] * 127), 0, JagexColor.LUMINANCE_MAX);
		int sat = clamp((int) (colour[2] * 7), 0, JagexColor.SATURATION_MAX);
		return JagexColor.packHSL(hue, sat, lum);
	}

	private static byte packTransparency(double[] colour)
	{
		double alpha = colour.length > 3 ? colour[3] : 1.0;
		double tp = alpha * -256;
		if (tp < -128)
		{
			tp += 256;
		}
		return (byte) tp;
	}

	private static int clamp(int v, int min, int max)
	{
		return Math.max(min, Math.min(max, v));
	}
}
