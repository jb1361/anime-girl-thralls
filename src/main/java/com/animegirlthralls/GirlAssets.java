package com.animegirlthralls;

import java.util.Map;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * Everything loaded for one girl: her model and whichever animation clips she ships with.
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

	boolean hasClips()
	{
		return !clips.isEmpty();
	}
}
