package com.animegirlthralls;

import java.util.Map;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * Everything loaded for one girl: her model and a complete matching animation set.
 */
@Getter
@RequiredArgsConstructor
final class GirlAssets
{
	private final BlenderModel model;
	private final Map<ClipType, VertexClip> clips;

	VertexClip getClip(ClipType type)
	{
		return clips.get(type);
	}

	boolean isComplete()
	{
		if (model == null || model.getVertices() == null)
		{
			return false;
		}
		for (ClipType type : ClipType.values())
		{
			VertexClip clip = clips.get(type);
			if (clip == null || clip.getVertexCount() != model.getVertices().length
				|| clip.getFrameCount() < 2 || clip.getAnchorCount() != 2 || clip.isLoop() != type.isLoop())
			{
				return false;
			}
		}
		return true;
	}
}
