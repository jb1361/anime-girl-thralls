package com.animegirlthralls;

import java.util.EnumMap;
import java.util.Map;
import org.junit.Test;
import static org.junit.Assert.*;

public class GirlAssetsTest
{
	private static VertexClip clip(int vertices, int anchors, boolean loop)
	{
		return new VertexClip(12, loop, vertices, 2, new short[2 * vertices * 3],
			anchors, new float[2 * anchors * VertexClip.ANCHOR_FLOATS]);
	}

	private static Map<ClipType, VertexClip> completeClips()
	{
		Map<ClipType, VertexClip> clips = new EnumMap<>(ClipType.class);
		for (ClipType type : ClipType.values())
		{
			clips.put(type, clip(3, 2, type.isLoop()));
		}
		return clips;
	}

	private static GirlAssets assets(Map<ClipType, VertexClip> clips)
	{
		BlenderModel model = new BlenderModel();
		model.setVertices(new int[3][3]);
		return new GirlAssets(model, clips);
	}

	@Test
	public void acceptsCompleteMatchingSet()
	{
		assertTrue(assets(completeClips()).isComplete());
	}

	@Test
	public void rejectsAnyMissingClip()
	{
		for (ClipType type : ClipType.values())
		{
			Map<ClipType, VertexClip> clips = completeClips();
			clips.remove(type);
			assertFalse(type.name(), assets(clips).isComplete());
		}
	}

	@Test
	public void rejectsMismatchedMesh()
	{
		Map<ClipType, VertexClip> clips = completeClips();
		ClipType type = ClipType.values()[0];
		clips.put(type, clip(4, 2, type.isLoop()));
		assertFalse(assets(clips).isComplete());
	}

	@Test
	public void rejectsMissingHandAnchors()
	{
		Map<ClipType, VertexClip> clips = completeClips();
		ClipType type = ClipType.values()[0];
		clips.put(type, clip(3, 0, type.isLoop()));
		assertFalse(assets(clips).isComplete());
	}

	@Test
	public void rejectsWrongLoopMode()
	{
		Map<ClipType, VertexClip> clips = completeClips();
		ClipType type = ClipType.values()[0];
		clips.put(type, clip(3, 2, !type.isLoop()));
		assertFalse(assets(clips).isComplete());
	}
}
