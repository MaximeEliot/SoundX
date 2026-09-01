package com.soundx.ui;

import com.soundx.input.KeyCombo;
import com.soundx.input.KeyHookManager;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.KeyStroke;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.Window;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;

/**
 * Fenetre modale qui capture une combinaison de touches via le hook global
 * (donc exactement la meme combinaison qui declenchera le son ensuite).
 *
 * Echap (hook) ou le bouton "Annuler" (Swing, utile si le hook est indisponible)
 * ferment sans resultat.
 */
public final class KeyCaptureDialog extends JDialog {

    private volatile KeyCombo result;
    private final KeyHookManager hook;

    public KeyCaptureDialog(Window owner, KeyHookManager hook, String targetName) {
        super(owner, "Assigner un raccourci", ModalityType.APPLICATION_MODAL);
        this.hook = hook;

        JLabel label = new JLabel("<html><div style='text-align:center;width:320px;'>"
                + "Cible : <b>" + escape(targetName) + "</b><br><br>"
                + "Appuyez sur la combinaison de touches souhaitee.<br>"
                + "<font size='-2'>Conseil : incluez Ctrl / Alt / Shift &nbsp;&bull;&nbsp; "
                + "Echap pour annuler</font></div></html>", SwingConstants.CENTER);
        label.setBorder(BorderFactory.createEmptyBorder(24, 28, 14, 28));

        JButton cancel = new JButton("Annuler");
        cancel.addActionListener(e -> dispose());
        JPanel south = new JPanel();
        south.add(cancel);

        setLayout(new BorderLayout());
        add(label, BorderLayout.CENTER);
        add(south, BorderLayout.SOUTH);

        getRootPane().registerKeyboardAction(e -> dispose(),
                KeyStroke.getKeyStroke("ESCAPE"), JComponent.WHEN_IN_FOCUSED_WINDOW);

        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        pack();
        setResizable(false);
        setLocationRelativeTo(owner);

        hook.beginCapture(new KeyHookManager.HotkeyCaptureCallback() {
            @Override
            public void onCaptured(KeyCombo combo) {
                result = combo;
                SwingUtilities.invokeLater(KeyCaptureDialog.this::dispose);
            }

            @Override
            public void onCancelled() {
                SwingUtilities.invokeLater(KeyCaptureDialog.this::dispose);
            }
        });

        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosed(WindowEvent e) {
                KeyCaptureDialog.this.hook.cancelCapture();
            }
        });
    }

    /** Bloque jusqu'a la fermeture ; renvoie la combinaison ou {@code null}. */
    public KeyCombo showDialog() {
        setVisible(true);
        return result;
    }

    private static String escape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
