package com.animegirlthralls.tools;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import net.runelite.cache.IndexType;
import net.runelite.cache.ItemManager;
import net.runelite.cache.definitions.ItemDefinition;
import net.runelite.cache.definitions.ModelDefinition;
import net.runelite.cache.definitions.loaders.ModelLoader;
import net.runelite.cache.fs.Archive;
import net.runelite.cache.fs.Index;
import net.runelite.cache.fs.Store;
import org.junit.Assume;
import org.junit.Test;

/**
 * Development helper, not a test: dumps item inventory models from the local game cache as OBJ files
 * so weapon grip alignment can be previewed in Blender. Runs only when AGT_DUMP_ITEMS is set, e.g.
 * AGT_DUMP_ITEMS=861,1383 AGT_DUMP_OUT=/tmp/models ./gradlew test --tests '*ItemModelDump*'
 */
public class ItemModelDump
{
	@Test
	public void dump() throws IOException
	{
		String items = System.getenv("AGT_DUMP_ITEMS");
		Assume.assumeNotNull(items);
		File out = new File(System.getenv().getOrDefault("AGT_DUMP_OUT", "build/item-models"));
		out.mkdirs();
		File cacheDir = new File(System.getenv().getOrDefault("AGT_CACHE",
			System.getProperty("user.home") + "/jagexcache/oldschool/LIVE"));

		try (Store store = new Store(cacheDir))
		{
			store.load();
			ItemManager itemManager = new ItemManager(store);
			itemManager.load();
			Index models = store.getIndex(IndexType.MODELS);

			for (String s : items.split(","))
			{
				int itemId = Integer.parseInt(s.trim());
				ItemDefinition def = itemManager.getItem(itemId);
				int modelId = def.inventoryModel;
				Archive archive = models.getArchive(modelId);
				byte[] data = archive.decompress(store.getStorage().loadArchive(archive));
				ModelDefinition md = new ModelLoader().load(modelId, data);
				File file = new File(out, itemId + ".obj");
				try (PrintWriter w = new PrintWriter(file, "UTF-8"))
				{
					w.println("# item " + itemId + " " + def.name + " model " + modelId
						+ " male " + def.maleModel0 + " female " + def.femaleModel0);
					for (int i = 0; i < md.vertexCount; i++)
					{
						w.println("v " + md.vertexX[i] + " " + md.vertexY[i] + " " + md.vertexZ[i]);
					}
					for (int i = 0; i < md.faceCount; i++)
					{
						w.println("f " + (md.faceIndices1[i] + 1) + " " + (md.faceIndices2[i] + 1) + " " + (md.faceIndices3[i] + 1));
					}
				}
				System.out.println("DUMPED " + file + " item " + itemId + " '" + def.name + "' model " + modelId
					+ " verts " + md.vertexCount + " faces " + md.faceCount + " male0 " + def.maleModel0);
			}
		}
	}
}
