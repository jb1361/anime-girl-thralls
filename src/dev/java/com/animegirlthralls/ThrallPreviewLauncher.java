package com.animegirlthralls;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import net.runelite.client.RuneLite;
import net.runelite.client.externalplugins.ExternalPluginManager;

/** Local-only launcher. It needs neither real thralls nor the production plugin enabled. */
public final class ThrallPreviewLauncher
{
	public static void main(String[] args) throws Exception
	{
		List<String> arguments = new ArrayList<>(Arrays.asList(args));
		if (!arguments.contains("--developer-mode"))
		{
			arguments.add("--developer-mode");
		}
		ExternalPluginManager.loadBuiltin(ThrallPreviewPlugin.class);
		RuneLite.main(arguments.toArray(new String[0]));
	}
}
