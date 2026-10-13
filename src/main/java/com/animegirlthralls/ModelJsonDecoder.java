package com.animegirlthralls;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;
import javax.inject.Inject;

/** Parses and validates mesh data in memory on a worker thread. */
final class ModelJsonDecoder
{
	private final Gson gson;

	@Inject
	ModelJsonDecoder(Gson gson)
	{
		this.gson = gson;
	}

	BlenderModel decode(String json, String resourcePath) throws ModelResourceException
	{
		try
		{
			BlenderModel model = gson.fromJson(json, BlenderModel.class);
			if (model == null)
			{
				throw new ModelResourceException("Empty model JSON: " + resourcePath);
			}
			decodeDeltaGeometry(model, resourcePath);
			return model;
		}
		catch (JsonParseException e)
		{
			throw new ModelResourceException("Invalid model JSON: " + resourcePath, e);
		}
	}

	private static void decodeDeltaGeometry(BlenderModel model, String resourcePath) throws ModelResourceException
	{
		String encoding = model.getGeometryEncoding();
		if (encoding == null)
		{
			return;
		}
		if (!"delta-v1".equals(encoding))
		{
			throw new ModelResourceException("Unsupported geometry encoding in " + resourcePath + ": " + encoding);
		}
		try
		{
			decodeDeltaRows(model.getVertices(), CustomModelLoader.MAX_VERTICES, resourcePath);
			decodeDeltaRows(model.getFaces(), CustomModelLoader.MAX_FACES, resourcePath);
		}
		catch (ArithmeticException e)
		{
			throw new ModelResourceException("Geometry overflow in " + resourcePath, e);
		}
		for (int[] face : model.getFaces())
		{
			for (int index : face)
			{
				if (index < 0 || index >= model.getVertices().length)
				{
					throw new ModelResourceException("Invalid face index in " + resourcePath);
				}
			}
		}
		model.setGeometryEncoding(null);
	}

	/** Each column stores the difference from the preceding row, starting at zero. */
	private static void decodeDeltaRows(int[][] rows, int limit, String resourcePath) throws ModelResourceException
	{
		if (rows == null || rows.length == 0 || rows.length > limit)
		{
			throw new ModelResourceException("Invalid geometry size in " + resourcePath);
		}
		int[] previous = new int[3];
		for (int[] row : rows)
		{
			if (row == null || row.length != 3)
			{
				throw new ModelResourceException("Invalid geometry row in " + resourcePath);
			}
			for (int column = 0; column < 3; column++)
			{
				row[column] = Math.addExact(previous[column], row[column]);
				previous[column] = row[column];
			}
		}
	}
}
