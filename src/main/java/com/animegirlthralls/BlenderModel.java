package com.animegirlthralls;

import lombok.Getter;
import lombok.Setter;

/**
 * A model exported from Blender in the JSON format used by the creators-kit plugin
 * (https://github.com/ScreteMonge/creators-kit). Coordinates are in local units where
 * one tile is 128 units, x is east, z is north and y is up with negative values.
 */
@Getter
@Setter
class BlenderModel
{
	private boolean useVertexColours;
	private int[][] vertices;
	private int[][] faces;
	private double[][] vertexColours;
	private int[] vertexColourIndex;
	private double[][] faceColours;
	private int[] faceColourIndex;
	private byte[] priorities;
	private int[] clientTicks;
	private int[][][] animVertices;
}
