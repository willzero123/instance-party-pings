package com.instancepartypings;

import net.runelite.client.RuneLite;
import net.runelite.client.externalplugins.ExternalPluginManager;

public class InstancePartyPingsPluginTest
{
	public static void main(String[] args) throws Exception
	{
		ExternalPluginManager.loadBuiltin(InstancePartyPingsPlugin.class);
		RuneLite.main(args);
	}
}
