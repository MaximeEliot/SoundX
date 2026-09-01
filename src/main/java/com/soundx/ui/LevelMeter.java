package com.soundx.ui;

import javax.swing.JComponent;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;

/**
 * Petit vu-mètre horizontal : montre le niveau du signal micro capté
 * (0.0 .. 1.0). Sert à vérifier d'un coup d'œil que le micro est bien pris.
 */
public final class LevelMeter extends JComponent {

    private double level;

    public LevelMeter() {
        setPreferredSize(new Dimension(150, 12));
        setToolTipText("Niveau du micro capté — parlez pour vérifier");
    }

    public void setLevel(double value) {
        double v = Math.max(0.0, Math.min(1.0, value));
        if (Math.abs(v - level) > 0.005) {
            level = v;
            repaint();
        }
    }

    @Override
    protected void paintComponent(Graphics g) {
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        int w = getWidth();
        int h = getHeight();
        int arc = h;

        g2.setColor(Theme.CARD);
        g2.fillRoundRect(0, 0, w, h, arc, arc);

        // courbe douce : la parole normale (~0.2-0.5) remplit bien la barre
        double shown = Math.pow(level, 0.6);
        int fill = (int) Math.round((w - 2) * shown);
        if (fill > 0) {
            Color c;
            if (shown < 0.65) {
                c = new Color(0x3E, 0xC1, 0x6B);
            } else if (shown < 0.9) {
                c = new Color(0xE6, 0xB0, 0x3A);
            } else {
                c = Theme.RED_BRIGHT;
            }
            g2.setColor(c);
            g2.fillRoundRect(1, 1, Math.max(fill, h - 2), h - 2, arc, arc);
        }

        g2.setColor(Theme.BORDER_SOFT);
        g2.drawRoundRect(0, 0, w - 1, h - 1, arc, arc);
        g2.dispose();
    }
}
