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
import java.util.Map;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Animation;
import net.runelite.api.Client;
import net.runelite.api.Model;
import net.runelite.api.NPC;
import net.runelite.api.Renderable;
import net.runelite.api.WorldView;
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

	// Thrall npcs currently hidden, and the replacement drawn in their place. Client thread only.
	private final Map<NPC, ThrallReplacement> replacements = new HashMap<>();
	private final RenderCallback renderCallback = new RenderCallback()
	{
		@Override
		public boolean addEntity(Renderable renderable, boolean drawingUi)
		{
			// Only hide thralls that have a replacement drawn in their place
			return !(renderable instanceof NPC) || !replacements.containsKey(renderable);
		}
	};

	// Parsed model resources keyed by girl, and loaded animations keyed by id. Client thread only.
	private final Map<AnimeGirl, BlenderModel> blenderModels = new EnumMap<>(AnimeGirl.class);
	private final Map<Integer, Animation> animations = new HashMap<>();
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
			animations.clear();
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
		if (replacements.isEmpty())
		{
			return;
		}

		boolean mimic = config.mimicAnimations();
		for (ThrallReplacement replacement : replacements.values())
		{
			replacement.sync(mimic, this::getAnimation);
		}
	}

	private Animation getAnimation(int id)
	{
		Animation animation = animations.get(id);
		if (animation == null)
		{
			animation = client.loadAnimation(id);
			if (animation != null)
			{
				animations.put(id, animation);
			}
		}
		return animation;
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
	 * Builds a fresh model for this npc. Each replacement gets its own model because its vertices
	 * are deformed in place to follow the npc's animation. Null when the npc should keep its own model
	 * or the custom model is not available. Client thread only.
	 */
	private Model buildModel(NPC npc)
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

		Model model = modelLoader.build(bm, config.modelHeight());
		if (model != null)
		{
			log.debug("Built {} model with {} vertices and {} faces for npc {}",
				girl, model.getVerticesCount(), model.getFaceCount(), npc.getId());
		}
		return model;
	}

	private void refreshReplacements()
	{
		// Models depend on the configured girl and height, so rebuild everything
		removeAllReplacements();

		WorldView wv = client.getTopLevelWorldView();
		if (wv == null)
		{
			return;
		}
		for (NPC npc : wv.npcs())
		{
			if (npc != null)
			{
				addReplacement(npc);
			}
		}
	}

	private void addReplacement(NPC npc)
	{
		Model m = buildModel(npc);
		if (m == null)
		{
			return;
		}

		ThrallReplacement replacement = new ThrallReplacement(client, npc, m);
		replacement.sync(config.mimicAnimations(), this::getAnimation);
		replacement.activate();
		replacements.put(npc, replacement);
	}

	private void removeReplacement(NPC npc)
	{
		ThrallReplacement replacement = replacements.remove(npc);
		if (replacement != null)
		{
			replacement.deactivate();
		}
	}

	private void removeAllReplacements()
	{
		for (ThrallReplacement replacement : replacements.values())
		{
			replacement.deactivate();
		}
		replacements.clear();
	}
}
