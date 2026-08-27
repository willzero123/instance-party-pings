/*
 * Copyright (c) 2019, Tomas Slusny <slusnucky@gmail.com>
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
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.Polygon;
import java.util.Iterator;
import javax.inject.Inject;
import net.runelite.api.Client;
import net.runelite.api.Perspective;
import net.runelite.api.WorldView;
import net.runelite.api.coords.LocalPoint;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.OverlayUtil;

class InstancePingOverlay extends Overlay
{
	private final Client client;
	private final InstancePartyPingsPlugin plugin;

	@Inject
	private InstancePingOverlay(final Client client, final InstancePartyPingsPlugin plugin)
	{
		this.client = client;
		this.plugin = plugin;
		setPosition(OverlayPosition.DYNAMIC);
	}

	@Override
	public Dimension render(Graphics2D graphics)
	{
		synchronized (plugin.getPendingTilePings())
		{
			final Iterator<InstanceTilePingData> iterator = plugin.getPendingTilePings().iterator();

			while (iterator.hasNext())
			{
				InstanceTilePingData next = iterator.next();

				if (next.getAlpha() <= 0)
				{
					iterator.remove();
					continue;
				}

				renderPing(graphics, next);

				long elapsedTimeMillis = (System.nanoTime() - next.getCreationTime()) / 1000000;
				next.setAlpha((int) Math.max(0, 255 - (elapsedTimeMillis / 4)));
			}
		}

		return null;
	}

	private void renderPing(final Graphics2D graphics, final InstanceTilePingData ping)
	{
		final WorldView worldView = client.getTopLevelWorldView();
		if (worldView == null)
		{
			return;
		}

		final LocalPoint localPoint = LocalPoint.fromWorld(worldView, ping.getPoint());
		if (localPoint == null)
		{
			return;
		}

		final Polygon poly = Perspective.getCanvasTilePoly(client, localPoint);
		if (poly == null)
		{
			return;
		}

		final Color color = new Color(
			ping.getColor().getRed(),
			ping.getColor().getGreen(),
			ping.getColor().getBlue(),
			ping.getAlpha());

		OverlayUtil.renderPolygon(graphics, poly, color);
	}
}
