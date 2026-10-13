package com.animegirlthralls;

import com.google.inject.Provides;
import java.io.IOException;
import java.io.InputStream;
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
import net.runelite.api.ItemComposition;
import net.runelite.api.ModelData;
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
	private ModelJsonDecoder modelDecoder;

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

	// Loaded resources keyed by girl, and loaded animations keyed by id. Client thread only.
	private final Map<AnimeGirl, GirlAssets> assets = new EnumMap<>(AnimeGirl.class);
	private final Map<Integer, Animation> animations = new HashMap<>();
	private Future<?> loadFuture;
	private volatile int loadGeneration;
	// Unanimated base models are shared read-only; each replacement owns its pose arrays.
	private final ModelCache<CustomModelLoader.BuiltModel> staticModels = new ModelCache<>(8);

	@Override
	protected void startUp()
	{
		renderCallbackManager.register(renderCallback);
		int generation = ++loadGeneration;
		loadFuture = executor.submit(() -> loadAssets(generation));
	}

	@Override
	protected void shutDown()
	{
		int generation = ++loadGeneration;
		renderCallbackManager.unregister(renderCallback);
		if (loadFuture != null)
		{
			loadFuture.cancel(true);
			loadFuture = null;
		}
		clientThread.invoke(() ->
		{
			if (generation != loadGeneration)
			{
				return;
			}
			removeAllReplacements();
			assets.clear();
			animations.clear();
			staticModels.clear();
		});
	}

	@Provides
	AnimeGirlThrallsConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(AnimeGirlThrallsConfig.class);
	}

	private void loadAssets(int generation)
	{
		Map<AnimeGirl, GirlAssets> loaded = new EnumMap<>(AnimeGirl.class);
		for (AnimeGirl girl : AnimeGirl.values())
		{
			if (Thread.currentThread().isInterrupted() || generation != loadGeneration)
			{
				return;
			}
			String modelResource = girl.getModelResource();
			if (modelResource == null)
			{
				continue;
			}

			BlenderModel bm = loadModelResource(modelResource);
			if (bm == null || bm.getVertices() == null)
			{
				continue;
			}

			Map<ClipType, VertexClip> clips = new EnumMap<>(ClipType.class);
			for (ClipType type : ClipType.values())
			{
				VertexClip clip = loadClipResource(girl.getClipResource(type));
				if (clip == null)
				{
					continue;
				}
				if (clip.getVertexCount() != bm.getVertices().length)
				{
					log.warn("Clip {} for {} has {} vertices but the model has {}; ignoring it",
						type, girl, clip.getVertexCount(), bm.getVertices().length);
					continue;
				}
				clips.put(type, clip);
			}
			loaded.put(girl, new GirlAssets(bm, clips));
			log.debug("Loaded {} with clips {}", girl, clips.keySet());
		}

		clientThread.invoke(() ->
		{
			if (generation != loadGeneration)
			{
				return;
			}
			assets.clear();
			staticModels.clear();
			assets.putAll(loaded);
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
			byte[] bytes = in.readNBytes(2 * 1024 * 1024 + 1);
			if (bytes.length > 2 * 1024 * 1024)
			{
				throw new ModelResourceException("Model resource exceeds 2 MiB: " + resource);
			}
			return modelDecoder.decode(new String(bytes, StandardCharsets.UTF_8), resource);
		}
		catch (IOException | ModelResourceException e)
		{
			log.error("Failed to read model resource {}", resource, e);
			return null;
		}
	}

	private VertexClip loadClipResource(String resource)
	{
		try (InputStream in = AnimeGirlThrallsPlugin.class.getResourceAsStream(resource))
		{
			if (in == null)
			{
				log.debug("No clip resource {}", resource);
				return null;
			}
			return VertexClip.read(in);
		}
		catch (IOException | IllegalArgumentException e)
		{
			log.error("Failed to read clip resource {}", resource, e);
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

		AnimationMode mode = config.animationMode();
		for (ThrallReplacement replacement : replacements.values())
		{
			replacement.sync(mode, this::getAnimation);
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

	private void refreshReplacements()
	{
		// Replace live objects; unchanged static appearances reuse the bounded model cache.
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

	/**
	 * Builds a fresh model for the npc and draws it in the npc's place, if a girl is configured for it
	 * and her resources are loaded. Static base geometry is shared read-only. Client thread only.
	 */
	private void addReplacement(NPC npc)
	{
		AnimeGirl girl = girlFor(npc);
		if (girl == null || girl.getModelResource() == null)
		{
			return;
		}

		GirlAssets girlAssets = assets.get(girl);
		if (girlAssets == null)
		{
			return;
		}

		ThrallKind kind = ThrallKind.forNpc(npc.getId());
		if (kind == null)
		{
			return;
		}

		int height = config.modelHeight();
		// Weapons follow the hand anchors stored in the clips, so a girl without clips holds none
		ModelData weapon = girl.isOwnWeapon() || !girlAssets.hasClips() ? null : loadWeaponModel(kind);
		CustomModelLoader.BuiltModel built = girlAssets.hasClips()
			? modelLoader.build(girlAssets.getModel(), height, girl.getBodyFraction(), weapon, kind.getWeaponKind())
			: staticModels.getOrBuild(girl.getModelResource(), height,
				() -> modelLoader.build(girlAssets.getModel(), height, girl.getBodyFraction(), null, null));
		if (built == null)
		{
			return;
		}
		log.debug("Built {} model with {} vertices and {} faces for npc {} ({}), weapon vertices {}",
			girl, built.getModel().getVerticesCount(), built.getModel().getFaceCount(), npc.getId(), kind,
			built.getWeaponVertexCount());

		float scale = CustomModelLoader.scaleFor(girlAssets.getModel(), height, girl.getBodyFraction());
		float weaponScale = CustomModelLoader.weaponScaleFor(height);
		ThrallReplacement replacement = new ThrallReplacement(client, npc, kind, built, girlAssets, scale, weaponScale);
		replacement.sync(config.animationMode(), this::getAnimation);
		replacement.activate();
		replacements.put(npc, replacement);
	}

	/**
	 * The inventory model of the weapon configured for this kind of thrall, or null for none or not
	 * yet loaded. Client thread only.
	 */
	private ModelData loadWeaponModel(ThrallKind kind)
	{
		int itemId;
		switch (kind)
		{
			case MAGIC:
				itemId = config.magicWeapon();
				break;
			case RANGED:
				itemId = config.rangedWeapon();
				break;
			default:
				itemId = config.meleeWeapon();
				break;
		}
		if (itemId < 0)
		{
			return null;
		}

		ItemComposition item = client.getItemDefinition(itemId);
		if (item == null)
		{
			log.warn("No item {} for {} thrall weapon", itemId, kind);
			return null;
		}
		int modelId = item.getInventoryModel();
		ModelData weapon = modelId > 0 ? client.loadModelData(modelId) : null;
		if (weapon == null)
		{
			log.warn("Item {} ({}) has no loadable model {}", itemId, item.getName(), modelId);
		}
		return weapon;
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
