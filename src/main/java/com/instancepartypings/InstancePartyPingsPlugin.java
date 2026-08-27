/*
 * Copyright (c) 2019, Tomas Slusny <slusnucky@gmail.com>
 * Copyright (c) 2021, Jonathan Rousseau <https://github.com/JoRouss>
 * Copyright (c) 2026, willzero123 <willzerodev@gmail.com>
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR
 * ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 * ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package com.instancepartypings;

import java.awt.Color;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import javax.inject.Inject;
import lombok.Getter;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.SoundEffectID;
import net.runelite.api.WorldView;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.PartyChanged;
import net.runelite.client.party.PartyMember;
import net.runelite.client.party.PartyService;
import net.runelite.client.party.WSClient;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDependency;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.plugins.PluginManager;
import net.runelite.client.plugins.party.PartyConfig;
import net.runelite.client.plugins.party.PartyPlugin;
import net.runelite.client.plugins.party.data.PartyData;
import net.runelite.client.plugins.party.messages.TilePing;
import net.runelite.client.ui.overlay.OverlayManager;

@PluginDependency(PartyPlugin.class)
@PluginDescriptor(
	name = "Instance Party Pings",
	description = "Ping tiles in different instances of the same map, like the Colosseum or the Inferno",
	tags = {"party", "ping", "tile", "instance", "colosseum", "inferno", "coaching"}
)
public class InstancePartyPingsPlugin extends Plugin
{
	@Inject
	private Client client;

	@Inject
	private ClientThread clientThread;

	@Inject
	private PartyService party;

	@Inject
	private WSClient wsClient;

	@Inject
	private OverlayManager overlayManager;

	@Inject
	private InstancePingOverlay pingOverlay;

	@Inject
	private PartyConfig partyConfig;

	@Inject
	private PluginManager pluginManager;

	@Inject
	private PartyPlugin partyPlugin;

	@Getter
	private final List<InstanceTilePingData> pendingTilePings = Collections.synchronizedList(new ArrayList<>());

	private static final long NATIVE_PING_TTL_NANOS = TimeUnit.SECONDS.toNanos(5);
	private final List<NativeTilePing> nativeTilePings = Collections.synchronizedList(new ArrayList<>());
	private final AtomicLong pingSession = new AtomicLong();

	@Override
	protected void startUp() throws Exception
	{
		pingSession.incrementAndGet();
		overlayManager.add(pingOverlay);
		wsClient.registerMessage(InstanceTilePing.class);
	}

	@Override
	protected void shutDown() throws Exception
	{
		pingSession.incrementAndGet();
		wsClient.unregisterMessage(InstanceTilePing.class);
		overlayManager.remove(pingOverlay);
		pendingTilePings.clear();
		nativeTilePings.clear();
	}

	@Subscribe
	public void onPartyChanged(PartyChanged event)
	{
		pingSession.incrementAndGet();
		pendingTilePings.clear();
		nativeTilePings.clear();
	}

	@Subscribe
	public void onTilePing(TilePing event)
	{
		final long session = pingSession.get();
		clientThread.invoke(() -> handleTilePing(event, session));
	}

	@Subscribe
	public void onInstanceTilePing(InstanceTilePing event)
	{
		final long session = pingSession.get();
		clientThread.invoke(() -> handleInstanceTilePing(event, session));
	}

	private void handleTilePing(TilePing event, long session)
	{
		if (!isCurrentSession(session) || party.getMemberById(event.getMemberId()) == null)
		{
			return;
		}

		final WorldPoint rawPoint = event.getPoint();
		final WorldView worldView = client.getTopLevelWorldView();
		if (rawPoint == null || worldView == null || rawPoint.getPlane() != worldView.getPlane())
		{
			return;
		}

		final LocalPoint localPoint = LocalPoint.fromWorld(worldView, rawPoint);
		if (localPoint == null)
		{
			return;
		}

		final WorldPoint templatePoint = WorldPoint.fromLocalInstance(client, localPoint, rawPoint.getPlane());
		final long now = System.nanoTime();
		final boolean rendered = partyConfig.pings();
		final boolean sounded = partyConfig.sounds();
		pruneNativeTilePings(now);
		if (rendered || sounded)
		{
			nativeTilePings.add(new NativeTilePing(
				event.getMemberId(), rawPoint, templatePoint, rendered, sounded, now));
		}

		final PartyMember localMember = party.getLocalMember();
		if (localMember != null && localMember.getMemberId() == event.getMemberId())
		{
			party.send(new InstanceTilePing(templatePoint));
		}
	}

	private void handleInstanceTilePing(InstanceTilePing event, long session)
	{
		if (!isCurrentSession(session) || party.getMemberById(event.getMemberId()) == null)
		{
			return;
		}

		final WorldPoint templatePoint = event.getPoint();
		final WorldView worldView = client.getTopLevelWorldView();
		if (templatePoint == null || worldView == null)
		{
			return;
		}

		final NativeTilePing nativePing = consumeNativeTilePing(
			event.getMemberId(), templatePoint, System.nanoTime());
		final boolean render = partyConfig.pings();
		final boolean sound = partyConfig.sounds();
		if (!render && !sound)
		{
			return;
		}

		final Collection<WorldPoint> points = WorldPoint.toLocalInstance(worldView, templatePoint);
		if (points.isEmpty())
		{
			return;
		}

		Color color = null;
		boolean visible = false;
		for (WorldPoint point : points)
		{
			visible |= point.getPlane() == worldView.getPlane();
			if (!render || (nativePing != null && nativePing.rendered && point.equals(nativePing.rawPoint)))
			{
				continue;
			}

			if (color == null)
			{
				color = memberColor(event.getMemberId());
			}
			pendingTilePings.add(new InstanceTilePingData(point, color));
		}

		final boolean nativeSounded = nativePing != null && nativePing.sounded;
		if (sound && visible && !nativeSounded)
		{
			client.playSoundEffect(SoundEffectID.SMITH_ANVIL_TINK);
		}
	}

	private boolean isCurrentSession(long session)
	{
		return session == pingSession.get()
			&& pluginManager.isPluginActive(this)
			&& client.getGameState() == GameState.LOGGED_IN
			&& party.isInParty()
			&& pluginManager.isPluginActive(partyPlugin);
	}

	private Color memberColor(long memberId)
	{
		final PartyData partyData = partyPlugin.getPartyDataMap().get(memberId);
		return partyData != null ? partyData.getColor() : Color.RED;
	}

	private NativeTilePing consumeNativeTilePing(long memberId, WorldPoint templatePoint, long now)
	{
		synchronized (nativeTilePings)
		{
			final Iterator<NativeTilePing> iterator = nativeTilePings.iterator();
			while (iterator.hasNext())
			{
				final NativeTilePing nativePing = iterator.next();
				if (now - nativePing.creationTime > NATIVE_PING_TTL_NANOS)
				{
					iterator.remove();
					continue;
				}

				if (nativePing.memberId == memberId && nativePing.templatePoint.equals(templatePoint))
				{
					iterator.remove();
					return nativePing;
				}
			}
			return null;
		}
	}

	private void pruneNativeTilePings(long now)
	{
		synchronized (nativeTilePings)
		{
			nativeTilePings.removeIf(
				nativePing -> now - nativePing.creationTime > NATIVE_PING_TTL_NANOS);
		}
	}

	private static final class NativeTilePing
	{
		private final long memberId;
		private final WorldPoint rawPoint;
		private final WorldPoint templatePoint;
		private final boolean rendered;
		private final boolean sounded;
		private final long creationTime;

		private NativeTilePing(
			long memberId,
			WorldPoint rawPoint,
			WorldPoint templatePoint,
			boolean rendered,
			boolean sounded,
			long creationTime)
		{
			this.memberId = memberId;
			this.rawPoint = rawPoint;
			this.templatePoint = templatePoint;
			this.rendered = rendered;
			this.sounded = sounded;
			this.creationTime = creationTime;
		}
	}
}
