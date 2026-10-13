package com.animegirlthralls;

import com.google.inject.Provides;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicInteger;
import javax.inject.Inject;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Model;
import net.runelite.api.Perspective;
import net.runelite.api.Player;
import net.runelite.api.RuneLiteObjectController;
import net.runelite.api.WorldView;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.events.BeforeRender;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Local-only preview using the production decoder, builder, clip sampler and cache. */
@PluginDescriptor(name = "Thrall Model Preview", internalName = "anime-girl-thralls-preview",
	description = "Inspect bundled models beside your player without summoning thralls",
	developerPlugin = true, enabledByDefault = true)
public class ThrallPreviewPlugin extends Plugin
{
	private static final Logger log = LoggerFactory.getLogger(ThrallPreviewPlugin.class);
	private static final int MAX_RESOURCE_BYTES = 2 * 1024 * 1024;
	@Inject private Client client;
	@Inject private ClientThread clientThread;
	@Inject private ScheduledExecutorService executor;
	@Inject private ModelJsonDecoder decoder;
	@Inject private CustomModelLoader modelLoader;
	@Inject private ThrallPreviewConfig config;

	private volatile boolean enabled;
	private volatile Future<?> loadFuture;
	private final AtomicInteger generation = new AtomicInteger();
	// All model maps and render objects are owned by the client thread.
	private final Map<AnimeGirl, GirlAssets> assets = new EnumMap<>(AnimeGirl.class);
	private final ModelCache<CustomModelLoader.BuiltModel> models = new ModelCache<>(8);
	private AnimeGirl character;
	private GirlAssets asset;
	private PreviewObject object;

	@Provides
	ThrallPreviewConfig provideConfig(ConfigManager manager)
	{
		return manager.getConfig(ThrallPreviewConfig.class);
	}

	@Override
	protected void startUp()
	{
		enabled = true;
		clientThread.invoke(this::selectCharacter);
	}

	@Override
	protected void shutDown()
	{
		enabled = false;
		int stoppedGeneration = generation.incrementAndGet();
		Future<?> loading = loadFuture;
		if (loading != null)
		{
			loading.cancel(true);
		}
		clientThread.invoke(() ->
		{
			if (enabled || generation.get() != stoppedGeneration)
			{
				return;
			}
			removeObject();
			asset = null;
			assets.clear();
			models.clear();
		});
	}

	private void selectCharacter()
	{
		if (!enabled)
		{
			return;
		}
		int requestedGeneration = generation.incrementAndGet();
		if (loadFuture != null)
		{
			loadFuture.cancel(true);
		}
		removeObject();
		character = config.character();
		asset = assets.get(character);
		if (asset != null)
		{
			ensureObject();
			return;
		}
		String resource = character.getModelResource();
		if (resource == null)
		{
			return;
		}
		AnimeGirl requestedCharacter = character;
		loadFuture = executor.submit(() ->
		{
			try (InputStream stream = AnimeGirlThrallsPlugin.class.getResourceAsStream(resource))
			{
				if (stream == null)
				{
					throw new ModelResourceException("Missing bundled model: " + resource);
				}
				byte[] bytes = stream.readNBytes(MAX_RESOURCE_BYTES + 1);
				if (bytes.length > MAX_RESOURCE_BYTES)
				{
					throw new ModelResourceException("Bundled model exceeds 2 MiB: " + resource);
				}
				BlenderModel loaded = decoder.decode(new String(bytes, StandardCharsets.UTF_8), resource);
				Map<ClipType, VertexClip> clips = new EnumMap<>(ClipType.class);
				for (ClipType type : ClipType.values())
				{
					try (InputStream clipStream = AnimeGirlThrallsPlugin.class.getResourceAsStream(requestedCharacter.getClipResource(type)))
					{
						if (clipStream == null)
						{
							continue;
						}
						VertexClip clip = VertexClip.read(clipStream);
						if (clip.getVertexCount() != loaded.getVertices().length)
						{
							throw new ModelResourceException("Clip vertex count differs from " + resource);
						}
						clips.put(type, clip);
					}
				}
				GirlAssets loadedAssets = new GirlAssets(loaded, clips);
				clientThread.invoke(() ->
				{
					if (enabled && generation.get() == requestedGeneration)
					{
						assets.put(requestedCharacter, loadedAssets);
						asset = loadedAssets;
						ensureObject();
					}
				});
			}
			catch (IOException | ModelResourceException e)
			{
				if (enabled && generation.get() == requestedGeneration)
				{
					log.warn("Could not load preview model {}", resource, e);
				}
			}
		});
	}

	@Subscribe
	public void onConfigChanged(ConfigChanged event)
	{
		if (!ThrallPreviewConfig.GROUP.equals(event.getGroup()))
		{
			return;
		}
		if ("character".equals(event.getKey()))
		{
			clientThread.invoke(this::selectCharacter);
		}
		else if ("height".equals(event.getKey()))
		{
			clientThread.invoke(() ->
			{
				removeObject();
				ensureObject();
			});
		}
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged event)
	{
		if (event.getGameState() != GameState.LOGGED_IN)
		{
			removeObject();
		}
	}

	@Subscribe
	public void onGameTick(GameTick event)
	{
		// Retry unavailable cache building blocks once per tick, never once per frame.
		ensureObject();
	}

	private void ensureObject()
	{
		Player player = client.getLocalPlayer();
		if (!enabled || object != null || asset == null || client.getGameState() != GameState.LOGGED_IN
			|| player == null || player.getWorldView() == null || player.getLocalLocation() == null)
		{
			return;
		}
		CustomModelLoader.BuiltModel built = models.getOrBuild(character.getModelResource(), config.height(),
			() -> modelLoader.build(asset.getModel(), config.height(), character.getBodyFraction(), null, null));
		if (built != null)
		{
			object = new PreviewObject(built.getModel(), asset,
				CustomModelLoader.scaleFor(asset.getModel(), config.height(), character.getBodyFraction()));
			syncObject();
			if (object != null)
			{
				client.registerRuneLiteObject(object);
			}
		}
	}

	@Subscribe
	public void onBeforeRender(BeforeRender event)
	{
		syncObject();
	}

	private void syncObject()
	{
		if (object == null)
		{
			return;
		}
		Player player = client.getLocalPlayer();
		if (!enabled || client.getGameState() != GameState.LOGGED_IN || player == null
			|| player.getWorldView() == null || player.getLocalLocation() == null)
		{
			removeObject();
			return;
		}
		WorldView world = player.getWorldView();
		LocalPoint location = player.getLocalLocation();
		int x = location.getX() + Perspective.LOCAL_TILE_SIZE;
		if (x >= world.getSizeX() * Perspective.LOCAL_TILE_SIZE)
		{
			x = location.getX() - Perspective.LOCAL_TILE_SIZE;
		}
		LocalPoint besidePlayer = new LocalPoint(x, location.getY(), world);
		object.setLocation(besidePlayer, world.getPlane());
		object.setOrientation(config.rotate() ? (client.getGameCycle() * 4) & 2047 : player.getCurrentOrientation());
		object.setZ(Perspective.getTileHeight(client, besidePlayer, world.getPlane()));
	}

	private void removeObject()
	{
		if (object != null)
		{
			client.removeRuneLiteObject(object);
			object = null;
		}
	}

	private final class PreviewObject extends RuneLiteObjectController
	{
		private final Model model;
		private final GirlAssets modelAssets;
		private final float scale;
		private PreviewAnimation playing;
		private int started;

		private PreviewObject(Model model, GirlAssets modelAssets, float scale)
		{
			this.model = model;
			this.modelAssets = modelAssets;
			this.scale = scale;
			setRadius(60);
		}

		@Override
		public Model getModel()
		{
			PreviewAnimation selected = config.animation();
			if (selected != playing)
			{
				playing = selected;
				started = client.getGameCycle();
			}
			VertexClip clip = selected.clip() == null ? null : modelAssets.getClip(selected.clip());
			if (clip == null)
			{
				return model;
			}
			Model scratch = client.applyTransformations(model, null, 0, null, 0);
			if (scratch == null || scratch == model || scratch.getVerticesX() == model.getVerticesX()
				|| scratch.getVerticesY() == model.getVerticesY() || scratch.getVerticesZ() == model.getVerticesZ())
			{
				return model;
			}
			float seconds = (client.getGameCycle() - started) * 0.02f;
			if (!clip.isLoop())
			{
				seconds %= clip.getDuration() + 0.5f;
			}
			clip.sample(seconds, scale, scratch.getVerticesX(), scratch.getVerticesY(), scratch.getVerticesZ());
			return scratch;
		}
	}
}
