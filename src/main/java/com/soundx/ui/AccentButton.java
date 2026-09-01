package com.soundx.ui;

import javax.swing.JButton;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;

/**
 * Bouton plein a coins arrondis, peint manuellement, pour les actions
 * importantes (STOP). Couleur de base + eclaircissement au survol / clic.
 */
public final class AccentButton extends JButton {

    private final Color base;

    public AccentButton(String text, Color base) {
        super(text);
        this.base = base;
        setForeground(Color.WHITE);
        setFocusPainted(false);
        setBorderPainted(false);
        setContentAreaFilled(false);
        setOpaque(false);
        setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        setFont(getFont().deriveFont(java.awt.Font.BOLD, 13f));
        setBorder(javax.swing.BorderFactory.createEmptyBorder(9, 20, 9, 20));
    }

    @Override
    public Dimension getPreferredSize() {
        Dimension d = super.getPreferredSize();
        d.height = Math.max(d.height, 36);
        return d;
    }

    @Override
    protected void paintComponent(Graphics g) {
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        Color fill = base;
        if (!isEnabled()) {
            fill = new Color(0x55, 0x55, 0x58);
        } else if (getModel().isArmed() || getModel().isPressed()) {
            fill = base.darker();
        } else if (getModel().isRollover()) {
            fill = brighten(base, 0.16f);
        }

        int arc = 10;
        g2.setColor(fill);
        g2.fillRoundRect(0, 0, getWidth(), getHeight(), arc, arc);

        if (isEnabled() && getModel().isRollover()) {
            g2.setColor(brighten(base, 0.35f));
            g2.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, arc, arc);
        }
        g2.dispose();

        super.paintComponent(g);
    }

    private static Color brighten(Color c, float f) {
        int r = (int) Math.min(255, c.getRed() + 255 * f);
        int g = (int) Math.min(255, c.getGreen() + 255 * f);
        int b = (int) Math.min(255, c.getBlue() + 255 * f);
        return new Color(r, g, b);
    }
}
