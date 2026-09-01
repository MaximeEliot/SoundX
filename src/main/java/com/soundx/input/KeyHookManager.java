package com.soundx.input;

import com.github.kwhat.jnativehook.GlobalScreen;
import com.github.kwhat.jnativehook.NativeHookException;
import com.github.kwhat.jnativehook.keyboard.NativeKeyEvent;
import com.github.kwhat.jnativehook.keyboard.NativeKeyListener;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Encapsule JNativeHook.
 *
 *  - {@link #start()} installe le hook natif et enregistre ce listener ;
 *  - {@link #bind(KeyCombo, Runnable)} associe une action a un raccourci ;
 *  - {@link #beginCapture(HotkeyCaptureCallback)} passe en "mode capture" : la
 *    prochaine combinaison frappee est renvoyee au lieu de declencher une
 *    action (utilise par la fenetre d'assignation).
 *
 * Les evenements JNativeHook arrivent sur son propre thread ; les actions sont
 * relayees sur un mono-thread dedie pour ne jamais bloquer le hook.
 */
public final class KeyHookManager implements NativeKeyListener {

    /** Callback de la fenetre de capture de raccourci. */
    public interface HotkeyCaptureCallback {
        void onCaptured(KeyCombo combo);

        default void onCancelled() { }
    }

    private static final String JNH_LOGGER = "com.github.kwhat.jnativehook";

    private final Map<KeyCombo, Runnable> actions = new ConcurrentHashMap<>();
    private final Set<Integer> pressed = ConcurrentHashMap.newKeySet();

    private final ExecutorService actionPool = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "soundx-hotkey");
        t.setDaemon(true);
        return t;
    });

    private volatile HotkeyCaptureCallback captureCallback;
    private volatile boolean running;

    /** Coupe les logs INFO/WARNING tres verbeux de JNativeHook. */
    public static void silenceLogging() {
        Logger logger = Logger.getLogger(JNH_LOGGER);
        logger.setLevel(Level.OFF);
        logger.setUseParentHandlers(false);
    }

    public void start() throws NativeHookException {
        silenceLogging();
        if (!GlobalScreen.isNativeHookRegistered()) {
            GlobalScreen.registerNativeHook();
        }
        GlobalScreen.addNativeKeyListener(this);
        running = true;
    }

    public void stop() {
        try {
            GlobalScreen.removeNativeKeyListener(this);
            if (GlobalScreen.isNativeHookRegistered()) {
                GlobalScreen.unregisterNativeHook();
            }
        } catch (NativeHookException e) {
            System.err.println("Retrait du hook clavier : " + e.getMessage());
        } finally {
            running = false;
            actionPool.shutdownNow();
        }
    }

    public boolean isRunning() {
        return running;
    }

    // ------------------------------------------------------------------
    //  Enregistrement des raccourcis
    // ------------------------------------------------------------------

    public void bind(KeyCombo combo, Runnable action) {
        if (combo != null && action != null) {
            actions.put(combo, action);
        }
    }

    public void unbind(KeyCombo combo) {
        if (combo != null) {
            actions.remove(combo);
        }
    }

    public boolean isBound(KeyCombo combo) {
        return combo != null && actions.containsKey(combo);
    }

    // ------------------------------------------------------------------
    //  Mode capture (assignation d'un nouveau raccourci)
    // ------------------------------------------------------------------

    public void beginCapture(HotkeyCaptureCallback cb) {
        this.captureCallback = cb;
    }

    public void cancelCapture() {
        HotkeyCaptureCallback cb = this.captureCallback;
        this.captureCallback = null;
        if (cb != null) {
            cb.onCancelled();
        }
    }

    // ------------------------------------------------------------------
    //  NativeKeyListener
    // ------------------------------------------------------------------

    @Override
    public void nativeKeyPressed(NativeKeyEvent e) {
        int keyCode = e.getKeyCode();

        // --- mode capture ---
        HotkeyCaptureCallback cb = this.captureCallback;
        if (cb != null) {
            if (keyCode == NativeKeyEvent.VC_ESCAPE) {
                this.captureCallback = null;
                cb.onCancelled();
                return;
            }
            if (KeyCombo.isModifierKey(keyCode)) {
                return; // on attend la touche "principale"
            }
            this.captureCallback = null;
            cb.onCaptured(new KeyCombo(e.getModifiers(), keyCode));
            return;
        }

        // --- declenchement normal ---
        if (KeyCombo.isModifierKey(keyCode)) {
            return;
        }
        if (!pressed.add(keyCode)) {
            return; // touche maintenue : pas de re-declenchement
        }

        Runnable action = actions.get(new KeyCombo(e.getModifiers(), keyCode));
        if (action != null) {
            actionPool.submit(() -> {
                try {
                    action.run();
                } catch (Exception ex) {
                    System.err.println("Action raccourci : " + ex.getMessage());
                }
            });
        }
    }

    @Override
    public void nativeKeyReleased(NativeKeyEvent e) {
        pressed.remove(e.getKeyCode());
    }

    @Override
    public void nativeKeyTyped(NativeKeyEvent e) {
        // non utilise
    }
}
