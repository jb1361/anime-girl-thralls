package com.animegirlthralls;

import net.runelite.client.RuneLite;
import net.runelite.client.externalplugins.ExternalPluginManager;

public class AnimeGirlThrallsPluginTest
{
	public static void main(String[] args) throws Exception
	{
		ExternalPluginManager.loadBuiltin(AnimeGirlThrallsPlugin.class);
		RuneLite.main(args);
	}
}