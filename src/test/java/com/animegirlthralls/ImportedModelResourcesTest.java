package com.animegirlthralls;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashSet;
import java.util.Set;
import net.runelite.http.api.RuneLiteAPI;
import org.junit.Test;
import static org.junit.Assert.*;

public class ImportedModelResourcesTest
{
	@Test
	public void everyImportedSelectionHasMatchingGeometryAndCredits() throws Exception
	{
		JsonArray manifest = RuneLiteAPI.GSON.fromJson(text("v2/manifest.json"), JsonArray.class);
		Set<String> resources = new HashSet<>();
		Set<String> hashes = new HashSet<>();
		for (JsonElement entry : manifest)
		{
			JsonObject record = entry.getAsJsonObject();
			String resource = record.get("resource").getAsString();
			assertTrue("Duplicate resource: " + resource, resources.add(resource));
			byte[] data = read(resource);
			assertTrue(resource, data.length <= 2 * 1024 * 1024);
			StringBuilder hash = new StringBuilder();
			for (byte b : MessageDigest.getInstance("SHA-256").digest(data))
			{
				hash.append(String.format("%02x", b & 0xff));
			}
			assertEquals(resource, record.get("sha256").getAsString(), hash.toString());
			assertTrue("Duplicate model geometry: " + resource, hashes.add(hash.toString()));
			String credits = text(resource.replace(".json", ".attribution.txt"));
			assertTrue(resource, credits.contains(record.get("sourceUrl").getAsString()));
			assertTrue(resource, credits.contains(record.getAsJsonObject("author").get("displayName").getAsString()));
			assertTrue(resource, credits.contains(record.getAsJsonObject("license").get("url").getAsString()));
			assertTrue(resource, credits.contains("Modified for"));
			JsonArray animations = record.getAsJsonArray("animations");
			assertNotNull("Missing animation manifest: " + resource, animations);
			Set<String> clipNames = new HashSet<>();
			for (JsonElement animation : animations)
			{
				JsonObject clip = animation.getAsJsonObject();
				String suffix = clip.get("clip").getAsString();
				assertTrue("Duplicate clip: " + resource, clipNames.add(suffix));
				byte[] bytes = read(resource.replace(".json", "_" + suffix + ".bin"));
				StringBuilder clipHash = new StringBuilder();
				for (byte b : MessageDigest.getInstance("SHA-256").digest(bytes))
				{
					clipHash.append(String.format("%02x", b & 0xff));
				}
				assertEquals(resource + " " + suffix, clip.get("sha256").getAsString(), clipHash.toString());
			}
			assertEquals(ClipType.values().length, clipNames.size());
			for (ClipType type : ClipType.values())
			{
				assertTrue(resource + " " + type, clipNames.contains(type.getSuffix()));
			}
		}
		int selections = 0;
		for (AnimeGirl character : AnimeGirl.values())
		{
			String resource = character.getModelResource();
			if (resource == null || !resource.startsWith("v2/"))
			{
				continue;
			}
			assertTrue(character.name(), resources.remove(resource));
			assertNotNull(read(character.getAttributionResource()));
			selections++;
		}
		assertEquals(7, selections);
		assertTrue("Unselectable resources: " + resources, resources.isEmpty());
	}

	private static String text(String resource) throws Exception
	{
		return new String(read(resource), StandardCharsets.UTF_8);
	}

	private static byte[] read(String resource) throws Exception
	{
		try (InputStream stream = AnimeGirlThrallsPlugin.class.getResourceAsStream(resource))
		{
			assertNotNull(resource, stream);
			return stream.readAllBytes();
		}
	}
}
