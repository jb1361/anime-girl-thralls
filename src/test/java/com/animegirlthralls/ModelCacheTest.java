package com.animegirlthralls;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;
import static org.junit.Assert.*;

public class ModelCacheTest
{
	@Test
	public void identicalAppearanceReusesTheSameObject()
	{
		ModelCache<Object> cache = new ModelCache<>(3);
		Object model = cache.getOrBuild("gojo_osrs", 210, Object::new);
		assertSame(model, cache.getOrBuild("gojo_osrs", 210, () -> { throw new AssertionError(); }));
	}

	@Test
	public void characterStyleAndHeightAreDistinct()
	{
		ModelCache<Object> cache = new ModelCache<>(4);
		Object first = cache.getOrBuild("gojo_osrs", 210, Object::new);
		assertNotSame(first, cache.getOrBuild("goku_osrs", 210, Object::new));
		assertNotSame(first, cache.getOrBuild("gojo_flat", 210, Object::new));
		assertNotSame(first, cache.getOrBuild("gojo_osrs", 300, Object::new));
	}

	@Test
	public void clearDropsAllPreviousSelections()
	{
		ModelCache<Object> cache = new ModelCache<>(3);
		Object first = cache.getOrBuild("gojo_osrs", 210, Object::new);
		cache.clear();
		assertNull(cache.get("gojo_osrs", 210));
		assertNotSame(first, cache.getOrBuild("gojo_osrs", 210, Object::new));
	}

	@Test
	public void capacityEvictsLeastRecentlyUsedModel()
	{
		ModelCache<Object> cache = new ModelCache<>(2);
		Object first = cache.getOrBuild("a", 210, Object::new);
		cache.getOrBuild("b", 210, Object::new);
		assertSame(first, cache.get("a", 210));
		cache.getOrBuild("c", 210, Object::new);
		assertNull(cache.get("b", 210));
		assertSame(first, cache.get("a", 210));
		assertNotNull(cache.get("c", 210));
	}

	@Test
	public void failedBuildNeitherCachesNullNorEvictsWorkingModel()
	{
		ModelCache<Object> cache = new ModelCache<>(1);
		Object first = cache.getOrBuild("a", 210, Object::new);
		assertNull(cache.getOrBuild("b", 210, () -> null));
		assertSame(first, cache.get("a", 210));
		assertNotNull(cache.getOrBuild("b", 210, Object::new));
	}

	@Test
	public void repeatedSpawnsOnlyBuildTheThreeSelectedAppearances()
	{
		ModelCache<Object> cache = new ModelCache<>(3);
		AtomicInteger builds = new AtomicInteger();
		String[] resources = {"gojo_osrs", "goku_osrs", "pikachu_osrs"};
		for (int i = 0; i < 100_000; i++)
		{
			assertNotNull(cache.getOrBuild(resources[i % 3], 210, () -> {
				builds.incrementAndGet();
				return new Object();
			}));
		}
		assertEquals(3, builds.get());
	}

	@Test(expected = IllegalArgumentException.class)
	public void rejectsZeroCapacity()
	{
		new ModelCache<>(0);
	}
}
