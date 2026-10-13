package com.animegirlthralls;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

/** Client-thread-only cache. Callers must not mutate shared model geometry. */
final class ModelCache<T>
{
	private final int capacity;
	private final Map<Key, T> entries = new LinkedHashMap<>(4, 0.75f, true);

	ModelCache(int capacity)
	{
		if (capacity < 1)
		{
			throw new IllegalArgumentException("Cache capacity must be positive");
		}
		this.capacity = capacity;
	}

	T get(String resource, int height)
	{
		return entries.get(new Key(resource, height));
	}

	T getOrBuild(String resource, int height, Supplier<T> builder)
	{
		Key key = new Key(resource, height);
		T model = entries.get(key);
		if (model != null)
		{
			return model;
		}
		model = builder.get();
		if (model != null)
		{
			if (entries.size() == capacity)
			{
				entries.remove(entries.keySet().iterator().next());
			}
			entries.put(key, model);
		}
		return model;
	}

	void clear()
	{
		entries.clear();
	}

	private static final class Key
	{
		private final String resource;
		private final int height;

		private Key(String resource, int height)
		{
			this.resource = resource;
			this.height = height;
		}

		@Override
		public boolean equals(Object other)
		{
			if (!(other instanceof Key))
			{
				return false;
			}
			Key key = (Key) other;
			return height == key.height && resource.equals(key.resource);
		}

		@Override
		public int hashCode()
		{
			return 31 * resource.hashCode() + height;
		}
	}
}
