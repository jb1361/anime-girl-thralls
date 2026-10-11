package com.animegirlthralls;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;
import com.google.inject.Provides;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.Model;
import net.runelite.api.NPC;
import net.runelite.api.Renderable;
import net.runelite.api.RuneLiteObject;
import net.runelite.api.WorldView;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.events.BeforeRender;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.NpcChanged;
import net.runelite.api.events.NpcDespawned;
import net.runelite.api.events.NpcSpawned;
import net.runelite.api.gameval.NpcID;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.callback.RenderCallback;
import net.runelite.client.callback.RenderCallbackManager;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;

@Slf4j
@PluginDescriptor(
	name = "Anime Girl Thralls",
	description = "Replaces the Arceuus thrall models with anime girl models",
	tags = {"thrall", "thralls", "arceuus", "cosmetic", "anime", "model"}
)
public class AnimeGirlThrallsPlugin extends Plugin
{
	@Inject
	private Client client;

	@Inject
	private ClientThread clientThread;

	@Inject
	private RenderCallbackManager renderCallbackManager;

	@Inject
	private Gson gson;

	@Inject
	private ScheduledExecutorService executor;

	@Inject
	private AnimeGirlThrallsConfig config;

	@Inject
	private CustomModelLoader modelLoader;

	// Thrall npcs currently hidden, and the object drawn in their place. Client thread only.
	private final Map<NPC, RuneLiteObject> replacements = new HashMap<>();
	private final RenderCallback renderCallback = new RenderCallback()
	{
		@Override
		public boolean addEntity(Renderable renderable, boolean drawingUi)
		{
			// Only hide thralls that have a replacement drawn in their place
			return !(renderable instanceof NPC) || !replacements.containsKey(renderable);
		}
	};

	// Parsed model resources and the built models, keyed by girl. Client thread only.
	private final Map<AnimeGirl, BlenderModel> blenderModels = new EnumMap<>(AnimeGirl.class);
	private final Map<AnimeGirl, Model> models = new EnumMap<>(AnimeGirl.class);
	private int modelHeight = -1;
	private Future<?> loadFuture;

	@Override
	protected void startUp()
	{
		renderCallbackManager.register(renderCallback);
		loadFuture = executor.submit(this::loadModelResources);
	}

	@Override
	protected void shutDown()
	{
		renderCallbackManager.unregister(renderCallback);
		if (loadFuture != null)
		{
			loadFuture.cancel(true);
			loadFuture = null;
		}
		clientThread.invoke(() ->
		{
			removeAllReplacements();
			blenderModels.clear();
			models.clear();
			modelHeight = -1;
		});
	}

	@Provides
	AnimeGirlThrallsConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(AnimeGirlThrallsConfig.class);
	}

	private void loadModelResources()
	{
		Map<AnimeGirl, BlenderModel> loaded = new EnumMap<>(AnimeGirl.class);
		for (AnimeGirl girl : AnimeGirl.values())
		{
			String resource = girl.getResource();
			if (resource == null)
			{
				continue;
			}

			BlenderModel bm = loadModelResource(resource);
			if (bm != null)
			{
				loaded.put(girl, bm);
			}
		}

		clientThread.invoke(() ->
		{
			blenderModels.clear();
			blenderModels.putAll(loaded);
			models.clear();
			refreshReplacements();
		});
	}

	private BlenderModel loadModelResource(String resource)
	{
		try (InputStream in = AnimeGirlThrallsPlugin.class.getResourceAsStream(resource))
		{
			if (in == null)
			{
				log.error("Model resource {} is missing", resource);
				return null;
			}
			Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8);
			BlenderModel bm = gson.fromJson(reader, BlenderModel.class);
			if (bm == null)
			{
				log.error("Model resource {} is empty", resource);
			}
			return bm;
		}
		catch (IOException | JsonParseException e)
		{
			log.error("Failed to read model resource {}", resource, e);
			return null;
		}
	}

	@Subscribe
	public void onNpcSpawned(NpcSpawned event)
	{
		addReplacement(event.getNpc());
	}

	@Subscribe
	public void onNpcDespawned(NpcDespawned event)
	{
		removeReplacement(event.getNpc());
	}

	@Subscribe
	public void onNpcChanged(NpcChanged event)
	{
		NPC npc = event.getNpc();
		removeReplacement(npc);
		addReplacement(npc);
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged event)
	{
		switch (event.getGameState())
		{
			case LOADING:
			case LOGIN_SCREEN:
			case HOPPING:
			case CONNECTION_LOST:
				removeAllReplacements();
				break;
			case LOGGED_IN:
				// Npcs survive a scene load without respawning, so pick them up again
				refreshReplacements();
				break;
			default:
				break;
		}
	}

	@Subscribe
	public void onConfigChanged(ConfigChanged event)
	{
		if (AnimeGirlThrallsConfig.GROUP.equals(event.getGroup()))
		{
			clientThread.invoke(this::refreshReplacements);
		}
	}

	@Subscribe
	public void onBeforeRender(BeforeRender event)
	{
		for (Map.Entry<NPC, RuneLiteObject> entry : replacements.entrySet())
		{
			syncToNpc(entry.getKey(), entry.getValue());
		}
	}

	/**
	 * Which girl, if any, is configured for this npc.
	 */
	private AnimeGirl girlFor(NPC npc)
	{
		switch (npc.getId())
		{
			case NpcID.ARCEUUS_THRALL_GHOST_LESSER:
			case NpcID.ARCEUUS_THRALL_GHOST_SUPERIOR:
			case NpcID.ARCEUUS_THRALL_GHOST_GREATER:
				return config.ghostModel();
			case NpcID.ARCEUUS_THRALL_SKELETON_LESSER:
			case NpcID.ARCEUUS_THRALL_SKELETON_SUPERIOR:
			case NpcID.ARCEUUS_THRALL_SKELETON_GREATER:
				return config.skeletonModel();
			case NpcID.ARCEUUS_THRALL_ZOMBIE_LESSER:
			case NpcID.ARCEUUS_THRALL_ZOMBIE_SUPERIOR:
			case NpcID.ARCEUUS_THRALL_ZOMBIE_GREATER:
				return config.zombieModel();
			default:
				return AnimeGirl.ORIGINAL;
		}
	}

	/**
	 * Returns the model configured for this npc, building it on first use or when the configured
	 * height changes. Null when the npc should keep its own model or the model is not available.
	 * Client thread only.
	 */
	private Model getModel(NPC npc)
	{
		AnimeGirl girl = girlFor(npc);
		if (girl == null || girl.getResource() == null)
		{
			return null;
		}

		BlenderModel bm = blenderModels.get(girl);
		if (bm == null)
		{
			return null;
		}

		int height = config.modelHeight();
		if (height != modelHeight)
		{
			models.clear();
			modelHeight = height;
		}

		Model model = models.get(girl);
		if (model == null)
		{
			model = modelLoader.build(bm, height);
			if (model != null)
			{
				models.put(girl, model);
				log.debug("Built {} model with {} vertices and {} faces at height {}",
					girl, model.getVerticesCount(), model.getFaceCount(), height);
			}
		}
		return model;
	}

	private void refreshReplacements()
	{
		Iterator<Map.Entry<NPC, RuneLiteObject>> it = replacements.entrySet().iterator();
		while (it.hasNext())
		{
			Map.Entry<NPC, RuneLiteObject> entry = it.next();
			Model m = getModel(entry.getKey());
			if (m == null)
			{
				entry.getValue().setActive(false);
				it.remove();
			}
			else
			{
				entry.getValue().setModel(m);
			}
		}

		WorldView wv = client.getTopLevelWorldView();
		if (wv == null)
		{
			return;
		}
		for (NPC npc : wv.npcs())
		{
			if (npc != null && !replacements.containsKey(npc))
			{
				addReplacement(npc);
			}
		}
	}

	private void addReplacement(NPC npc)
	{
		Model m = getModel(npc);
		if (m == null)
		{
			return;
		}

		RuneLiteObject obj = client.createRuneLiteObject();
		obj.setModel(m);
		syncToNpc(npc, obj);
		obj.setActive(true);
		replacements.put(npc, obj);
	}

	private void removeReplacement(NPC npc)
	{
		RuneLiteObject obj = replacements.remove(npc);
		if (obj != null)
		{
			obj.setActive(false);
		}
	}

	private void removeAllReplacements()
	{
		for (RuneLiteObject obj : replacements.values())
		{
			obj.setActive(false);
		}
		replacements.clear();
	}

	private void syncToNpc(NPC npc, RuneLiteObject obj)
	{
		LocalPoint lp = npc.getLocalLocation();
		WorldView wv = npc.getWorldView();
		if (lp == null || wv == null)
		{
			return;
		}
		obj.setLocation(lp, wv.getPlane());
		obj.setOrientation(npc.getCurrentOrientation());
	}
}
