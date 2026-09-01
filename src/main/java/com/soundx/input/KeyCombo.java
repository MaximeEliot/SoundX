package com.soundx.input;

import com.github.kwhat.jnativehook.NativeInputEvent;
import com.github.kwhat.jnativehook.keyboard.NativeKeyEvent;

import java.util.Objects;

/**
 * Combinaison de touches immuable : un masque de modificateurs (Ctrl / Alt /
 * Shift / Meta, normalise sans distinction gauche/droite) + un code de touche
 * "virtuel" JNativeHook ({@code NativeKeyEvent.VC_*}).
 *
 * Sert a la fois de cle de {@code Map} pour le declenchement et de valeur
 * serialisee dans la configuration.
 */
public final class KeyCombo {

    private final int modifiers;
    private final int keyCode;

    public KeyCombo(int modifiers, int keyCode) {
        this.modifiers = normalize(modifiers);
        this.keyCode = keyCode;
    }

    public int getModifiers() {
        return modifiers;
    }

    public int getKeyCode() {
        return keyCode;
    }

    /**
     * Reduit un masque JNativeHook (qui distingue gauche/droite) aux 4 bits
     * generiques SHIFT / CTRL / ALT / META, pour que Ctrl-gauche et Ctrl-droit
     * produisent le meme raccourci.
     */
    public static int normalize(int rawModifiers) {
        int r = 0;
        if ((rawModifiers & NativeInputEvent.SHIFT_MASK) != 0) r |= NativeInputEvent.SHIFT_MASK;
        if ((rawModifiers & NativeInputEvent.CTRL_MASK) != 0) r |= NativeInputEvent.CTRL_MASK;
        if ((rawModifiers & NativeInputEvent.ALT_MASK) != 0) r |= NativeInputEvent.ALT_MASK;
        if ((rawModifiers & NativeInputEvent.META_MASK) != 0) r |= NativeInputEvent.META_MASK;
        return r;
    }

    /** Vrai si le code correspond a une touche purement modificatrice. */
    public static boolean isModifierKey(int keyCode) {
        switch (keyCode) {
            case NativeKeyEvent.VC_SHIFT:
            case NativeKeyEvent.VC_CONTROL:
            case NativeKeyEvent.VC_ALT:
            case NativeKeyEvent.VC_META:
                return true;
            default:
                return false;
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof KeyCombo)) return false;
        KeyCombo other = (KeyCombo) o;
        return modifiers == other.modifiers && keyCode == other.keyCode;
    }

    @Override
    public int hashCode() {
        return Objects.hash(modifiers, keyCode);
    }

    /** Ex. : "Ctrl+Shift+F1", "Alt+1", "F13". */
    @Override
    public String toString() {
        String mods = NativeInputEvent.getModifiersText(modifiers);
        String key = NativeKeyEvent.getKeyText(keyCode);
        return (mods == null || mods.isEmpty()) ? key : mods + "+" + key;
    }
}
