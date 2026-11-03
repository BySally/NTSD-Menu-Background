// SPDX-License-Identifier: LGPL-2.1-only
package dev.ntsd.menu;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.screens.TitleScreen;

/** Project attribution only; upstream Minecraft/Forge notices remain visible. */
public final class ProjectCredit {
    public static final String TEXT = "Created by BySally";

    public static int[] bounds(TitleScreen screen) {
        int width = Minecraft.getInstance().font.width(TEXT);
        int x = Math.max(4, screen.width - width - 8);
        // Reserve the last 20 pixels for vanilla copyright and Forge footer text.
        for (int y = screen.height - 24; y >= 8; y -= 12) {
            boolean overlaps = false;
            for (var child : screen.children()) {
                if (child instanceof AbstractWidget w && w.visible
                        && x < w.getX() + w.getWidth() + 3 && x + width + 3 > w.getX()
                        && y < w.getY() + w.getHeight() + 3 && y + 10 > w.getY()) {
                    overlaps = true;
                    break;
                }
            }
            if (!overlaps) return new int[]{x, y, width, 9};
        }
        return null; // Extremely small window: never obscure an existing button.
    }

    public static void draw(GuiGraphics graphics, TitleScreen screen) {
        int[] rect = bounds(screen);
        if (rect != null) graphics.drawString(Minecraft.getInstance().font, TEXT,
                rect[0], rect[1], 0xFFD0C8B8, true);
    }
}
