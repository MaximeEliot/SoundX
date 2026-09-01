package com.soundx.ui;

import com.soundx.model.SoundEntry;

import javax.swing.JButton;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.geom.RoundRectangle2D;

/**
 * Carte d'un son, entierement peinte a la main pour le theme noir/rouge :
 * titre centre + pastille rappelant le raccourci assigne. Survol et clic
 * s'illuminent en rouge ; un liseret rouge a gauche signale un raccourci actif.
 */
public final class SoundButton extends JButton {

    private static final int ARC = 16;

    private final SoundEntry entry;

    public SoundButton(SoundEntry entry) {
        this.entry = entry;
        setPreferredSize(new Dimension(172, 78));
        setFocusable(false);
        setFocusPainted(false);
        setBorderPainted(false);
        setContentAreaFilled(false);
        setRolloverEnabled(true);
        setOpaque(false);
        setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        setFont(getFont().deriveFont(Font.BOLD, 13f));
        refresh();
    }

    public SoundEntry getEntry() {
        return entry;
    }

    /** Met a jour l'infobulle apres un changement de raccourci. */
    public void refresh() {
        setToolTipText(entry.getFileName()
                + (entry.getHotkey() != null ? "   |   raccourci : " + entry.getHotkey() : ""));
        repaint();
    }

    @Override
    protected void paintComponent(Graphics g) {
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

        int w = getWidth();
        int h = getHeight();
        boolean pressed = getModel().isArmed() && getModel().isPressed();
        boolean hover = getModel().isRollover();
        boolean bound = entry.getHotkey() != null;

        Color fill;
        Color border;
        float borderWidth;
        if (pressed) {
            fill = Theme.RED_DARK;
            border = Theme.RED_BRIGHT;
            borderWidth = 1.6f;
        } else if (hover) {
            fill = Theme.CARD_HOVER;
            border = Theme.RED;
            borderWidth = 1.6f;
        } else {
            fill = Theme.CARD;
            border = Theme.BORDER;
            borderWidth = 1.1f;
        }

        Shape card = new RoundRectangle2D.Float(0.5f, 0.5f, w - 1f, h - 1f, ARC, ARC);
        g2.setColor(fill);
        g2.fill(card);

        // liseret rouge a gauche si un raccourci est assigne (proprement clippe)
        if (bound && !pressed) {
            Shape oldClip = g2.getClip();
            g2.clip(card);
            g2.setColor(Theme.RED);
            g2.fillRect(0, 0, 4, h);
            g2.setClip(oldClip);
        }

        g2.setStroke(new BasicStroke(borderWidth));
        g2.setColor(border);
        g2.draw(card);

        // --- titre ---
        g2.setColor(pressed ? Color.WHITE : Theme.TEXT);
        g2.setFont(getFont().deriveFont(Font.BOLD, 13f));
        String title = fit(g2, entry.getDisplayName(), w - 24);
        double tw = g2.getFontMetrics().getStringBounds(title, g2).getWidth();
        int titleY = bound ? h / 2 - 4 : h / 2 + 5;
        g2.drawString(title, (int) Math.round((w - tw) / 2), titleY);

        // --- pastille du raccourci ---
        if (bound) {
            g2.setFont(getFont().deriveFont(Font.BOLD, 10.5f));
            String hk = fit(g2, entry.getHotkey().toString(), w - 26);
            double hw = g2.getFontMetrics().getStringBounds(hk, g2).getWidth();
            int padX = 9;
            int pillW = (int) Math.round(hw) + padX * 2;
            int pillH = 18;
            int pillX = (w - pillW) / 2;
            int pillY = h / 2 + 3;
            g2.setColor(pressed ? new Color(255, 255, 255, 45) : Theme.RED_DEEP);
            g2.fillRoundRect(pillX, pillY, pillW, pillH, pillH, pillH);
            g2.setColor(pressed ? Color.WHITE : Theme.RED_BRIGHT);
            int baseline = pillY + (pillH - g2.getFontMetrics().getHeight()) / 2
                    + g2.getFontMetrics().getAscent();
            g2.drawString(hk, pillX + padX, baseline);
        } else if (hover) {
            g2.setColor(Theme.TEXT_MUTED);
            g2.setFont(getFont().deriveFont(Font.PLAIN, 10f));
            String hint = "clic droit → raccourci";
            double hw = g2.getFontMetrics().getStringBounds(hint, g2).getWidth();
            g2.drawString(hint, (int) Math.round((w - hw) / 2), h - 12);
        }

        g2.dispose();
    }

    /** Tronque le texte avec une ellipse s'il depasse {@code maxWidth}. */
    private static String fit(Graphics2D g2, String s, int maxWidth) {
        if (g2.getFontMetrics().stringWidth(s) <= maxWidth) {
            return s;
        }
        StringBuilder sb = new StringBuilder(s);
        while (sb.length() > 1 && g2.getFontMetrics().stringWidth(sb + "…") > maxWidth) {
            sb.deleteCharAt(sb.length() - 1);
        }
        return sb + "…";
    }
}
