package com.soundx.ui;

import com.formdev.flatlaf.FlatDarkLaf;

import javax.swing.UIManager;
import java.awt.Color;

/**
 * Palette et installation du look & feel sombre (noir / rouge).
 *
 * A appeler UNE fois, avant la creation du moindre composant Swing.
 */
public final class Theme {

    private Theme() { }

    // ---- Palette ----------------------------------------------------
    public static final Color BG          = new Color(0x0F, 0x0F, 0x10);
    public static final Color PANEL       = new Color(0x17, 0x17, 0x19);
    public static final Color PANEL_ALT   = new Color(0x1C, 0x1C, 0x1F);
    public static final Color CARD        = new Color(0x21, 0x21, 0x24);
    public static final Color CARD_HOVER  = new Color(0x2E, 0x20, 0x22);
    public static final Color BORDER      = new Color(0x37, 0x37, 0x3C);
    public static final Color BORDER_SOFT = new Color(0x2A, 0x2A, 0x2E);

    public static final Color RED         = new Color(0xE6, 0x33, 0x33);
    public static final Color RED_BRIGHT  = new Color(0xFF, 0x4D, 0x4D);
    public static final Color RED_DARK    = new Color(0xB0, 0x22, 0x22);
    public static final Color RED_DEEP    = new Color(0x4A, 0x16, 0x18);

    public static final Color TEXT        = new Color(0xED, 0xED, 0xEF);
    public static final Color TEXT_MUTED  = new Color(0x93, 0x93, 0x99);

    /** Installe FlatLaf + les surcharges noir/rouge. */
    public static void install() {
        // Decorations de fenetre dessinees par FlatLaf (barre de titre sombre).
        System.setProperty("flatlaf.useWindowDecorations", "true");
        System.setProperty("flatlaf.menuBarEmbedded", "true");

        FlatDarkLaf.setup();

        // Accent / focus
        put("Component.accentColor", RED);
        put("Component.focusColor", RED);
        put("Component.focusWidth", 1);
        put("Component.innerFocusWidth", 1);
        put("Component.arc", 9);
        put("Button.arc", 9);
        put("TextComponent.arc", 8);
        put("CheckBox.arc", 5);
        put("Component.borderColor", BORDER);
        put("Component.disabledBorderColor", BORDER_SOFT);

        // Fonds
        put("control", PANEL);
        put("Panel.background", PANEL);
        put("Viewport.background", PANEL);
        put("ScrollPane.background", PANEL);
        put("MenuBar.background", BG);
        put("TitlePane.background", BG);
        put("TitlePane.inactiveBackground", BG);
        put("TitlePane.foreground", TEXT);
        put("TitlePane.buttonHoverBackground", RED_DEEP);
        put("TitlePane.buttonPressedBackground", RED_DARK);

        // Texte
        put("Label.foreground", TEXT);
        put("Label.disabledForeground", TEXT_MUTED);
        put("CheckBox.foreground", TEXT);

        // Champs / listes / combos
        put("TextField.background", CARD);
        put("FormattedTextField.background", CARD);
        put("ComboBox.background", CARD);
        put("ComboBox.buttonBackground", CARD);
        put("ComboBox.selectionBackground", RED_DARK);
        put("ComboBox.selectionForeground", Color.WHITE);
        put("List.background", PANEL_ALT);
        put("List.selectionBackground", RED_DARK);
        put("List.selectionForeground", Color.WHITE);

        // Menus
        put("PopupMenu.background", PANEL_ALT);
        put("MenuItem.selectionBackground", RED_DARK);
        put("Menu.selectionBackground", RED_DARK);
        put("MenuItem.selectionForeground", Color.WHITE);

        // Boutons "normaux"
        put("Button.background", CARD);
        put("Button.foreground", TEXT);
        put("Button.hoverBackground", CARD_HOVER);
        put("Button.pressedBackground", RED_DEEP);
        put("Button.default.background", RED_DARK);
        put("Button.default.hoverBackground", RED);
        put("Button.default.foreground", Color.WHITE);
        put("Button.default.focusColor", RED);

        // Sliders
        put("Slider.thumbColor", RED);
        put("Slider.trackValueColor", RED);
        put("Slider.trackColor", BORDER);
        put("Slider.focusedColor", RED_BRIGHT);
        put("Slider.hoverThumbColor", RED_BRIGHT);
        put("Slider.pressedThumbColor", RED_BRIGHT);

        // Barres de defilement
        put("ScrollBar.track", PANEL);
        put("ScrollBar.thumb", BORDER);
        put("ScrollBar.hoverThumbColor", RED_DEEP);
        put("ScrollBar.width", 12);

        // Tooltips / séparateurs
        put("ToolTip.background", PANEL_ALT);
        put("ToolTip.foreground", TEXT);
        put("Separator.foreground", BORDER_SOFT);

        // Infobulles d'aide
        put("OptionPane.background", PANEL);
        put("OptionPane.messageForeground", TEXT);
    }

    private static void put(String key, Object value) {
        UIManager.put(key, value);
    }
}
