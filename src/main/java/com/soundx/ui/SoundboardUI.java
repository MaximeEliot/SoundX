package com.soundx.ui;

import com.soundx.audio.AudioDevice;
import com.soundx.audio.AudioManager;
import com.soundx.config.AppConfig;
import com.soundx.config.ConfigManager;
import com.soundx.input.KeyCombo;
import com.soundx.input.KeyHookManager;
import com.soundx.model.SoundEntry;
import com.soundx.model.SoundFiles;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JMenu;
import javax.swing.JMenuBar;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JSlider;
import javax.swing.JTextField;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import java.awt.AWTException;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Frame;
import java.awt.Graphics2D;
import java.awt.GridLayout;
import java.awt.Image;
import java.awt.MenuItem;
import java.awt.PopupMenu;
import java.awt.RenderingHints;
import java.awt.SystemTray;
import java.awt.TrayIcon;
import java.awt.Desktop;
import java.awt.image.BufferedImage;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/**
 * Fenetre principale (theme noir / rouge).
 *
 *  - bloc "SORTIE" : peripherique de sortie + volume des sons ;
 *  - bloc "MICRO"  : entree micro renvoyee vers la sortie (facon Voicemod) ;
 *  - grille auto-generee (un bouton par fichier de {@code sounds/}) ;
 *  - bas : STOP d'urgence, raccourci STOP global, rescan, statut.
 *
 * Fermer la fenetre la reduit dans la zone de notification : les raccourcis
 * globaux et le renvoi micro continuent. "Quitter" ferme vraiment.
 */
public final class SoundboardUI extends JFrame {

    private static final int COLUMNS = 4;

    private File soundsDir;
    private final AudioManager audio;
    private final KeyHookManager hook;
    private final ConfigManager configManager;
    private final AppConfig config;

    private final JPanel grid = new JPanel(new GridLayout(0, COLUMNS, 10, 10));
    private final JLabel status = new JLabel(" ");

    private final JComboBox<AudioDevice> outputBox = new JComboBox<>();
    private final JSlider soundVolume = new JSlider(0, 100, 85);

    private final JCheckBox monitorToggle = new JCheckBox("Écouter aussi dans");
    private final JComboBox<AudioDevice> monitorBox = new JComboBox<>();
    private final JSlider monitorVolume = new JSlider(0, 100, 85);

    private final JComboBox<AudioDevice> inputBox = new JComboBox<>();
    private final JCheckBox micToggle = new JCheckBox("Renvoyer le micro vers la sortie");
    private final JSlider micVolume = new JSlider(0, 200, 100);
    private final JLabel micStatus = new JLabel("Renvoi micro inactif.");
    private final LevelMeter micMeter = new LevelMeter();

    private long lastMicSignalMs;
    private String micBaseStatus = "Renvoi micro inactif.";

    private final JTextField stopHotkeyField = new JTextField(13);

    private final List<SoundEntry> entries = new ArrayList<>();
    private KeyCombo stopHotkey;

    private boolean suppressOutputEvents;
    private boolean suppressMonitorEvents;
    private boolean suppressInputEvents;
    private TrayIcon trayIcon;
    private boolean trayHintShown;

    public SoundboardUI(File soundsDir, AudioManager audio, KeyHookManager hook,
                        ConfigManager configManager) {
        super("SoundX — Soundboard");
        this.soundsDir = soundsDir;
        this.audio = audio;
        this.hook = hook;
        this.configManager = configManager;
        this.config = configManager.get();

        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                handleWindowClose();
            }
        });
        setIconImages(List.of(makeIcon(16), makeIcon(24), makeIcon(32), makeIcon(48)));

        audio.setMicStatusListener((message, active) ->
                SwingUtilities.invokeLater(() -> updateMicStatus(message, active)));

        buildMenuBar();
        buildContent();
        reloadOutputDevices();
        reloadMonitorDevices();
        reloadInputDevices();
        applyConfigToUI();
        setupTray();
        rescan();

        setSize(840, 660);
        setMinimumSize(new Dimension(600, 460));
        setLocationRelativeTo(null);
    }

    // ==================================================================
    //  Construction de l'interface
    // ==================================================================

    private void buildMenuBar() {
        JMenuBar bar = new JMenuBar();

        JMenu menuFile = new JMenu("Fichier");
        JMenuItem chooseFolder = new JMenuItem("Choisir le dossier des sons…");
        chooseFolder.addActionListener(e -> chooseSoundsFolder());
        JMenuItem openFolder = new JMenuItem("Ouvrir le dossier des sons");
        openFolder.addActionListener(e -> openSoundsFolder());
        JMenuItem rescanItem = new JMenuItem("Rescanner les sons");
        rescanItem.addActionListener(e -> rescan());
        JMenuItem quitItem = new JMenuItem("Quitter");
        quitItem.addActionListener(e -> exitApplication());
        menuFile.add(chooseFolder);
        menuFile.add(openFolder);
        menuFile.add(rescanItem);
        menuFile.addSeparator();
        menuFile.add(quitItem);

        JMenu menuHelp = new JMenu("Aide");
        JMenuItem about = new JMenuItem("A propos");
        about.addActionListener(e -> showAbout());
        menuHelp.add(about);

        bar.add(menuFile);
        bar.add(menuHelp);
        setJMenuBar(bar);
    }

    private void buildContent() {
        setLayout(new BorderLayout());
        getContentPane().setBackground(Theme.BG);

        add(buildTopPanel(), BorderLayout.NORTH);

        // ---- grille centrale ----
        grid.setOpaque(false);
        JPanel gridWrap = new JPanel(new BorderLayout());
        gridWrap.setOpaque(false);
        gridWrap.setBorder(BorderFactory.createEmptyBorder(12, 14, 12, 14));
        gridWrap.add(grid, BorderLayout.NORTH);

        JScrollPane scroll = new JScrollPane(gridWrap);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        scroll.getViewport().setBackground(Theme.BG);
        scroll.getVerticalScrollBar().setUnitIncrement(18);
        add(scroll, BorderLayout.CENTER);

        add(buildBottomPanel(), BorderLayout.SOUTH);
    }

    private JPanel buildTopPanel() {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBackground(Theme.PANEL);
        panel.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, Theme.BORDER_SOFT),
                BorderFactory.createEmptyBorder(10, 14, 10, 14)));

        // --- ligne SORTIE ---
        JPanel outRow = row();
        outRow.add(sectionLabel("SORTIE"));
        outputBox.setPreferredSize(new Dimension(280, 28));
        outputBox.addActionListener(e -> onOutputSelected());
        outRow.add(outputBox);
        JButton refresh = new JButton("↻");
        refresh.setToolTipText("Rafraichir la liste des peripheriques");
        refresh.setFocusable(false);
        refresh.addActionListener(e -> {
            reloadOutputDevices();
            reloadMonitorDevices();
            reloadInputDevices();
        });
        outRow.add(refresh);
        outRow.add(Box.createHorizontalStrut(10));
        outRow.add(mutedLabel("Volume sons"));
        soundVolume.setOpaque(false);
        soundVolume.setPreferredSize(new Dimension(120, 26));
        soundVolume.addChangeListener(e -> {
            double v = soundVolume.getValue() / 100.0;
            audio.setMasterVolume(v);
            if (!soundVolume.getValueIsAdjusting()) {
                config.masterVolume = v;
                configManager.save();
            }
        });
        outRow.add(soundVolume);
        panel.add(outRow);

        panel.add(Box.createVerticalStrut(6));

        // --- ligne ECOUTE (monitoring casque) ---
        JPanel monRow = row();
        monRow.add(sectionLabel("ÉCOUTE"));
        monitorToggle.setOpaque(false);
        monitorToggle.setForeground(Theme.TEXT);
        monitorToggle.setFocusable(false);
        monitorToggle.setToolTipText(
                "Jouer aussi les sons sur ce périphérique (votre casque), en plus de la sortie");
        monitorToggle.addActionListener(e -> onMonitorToggle());
        monRow.add(monitorToggle);
        monitorBox.setPreferredSize(new Dimension(240, 28));
        monitorBox.addActionListener(e -> onMonitorSelected());
        monRow.add(monitorBox);
        monRow.add(Box.createHorizontalStrut(10));
        monRow.add(mutedLabel("Volume écoute"));
        monitorVolume.setOpaque(false);
        monitorVolume.setPreferredSize(new Dimension(120, 26));
        monitorVolume.addChangeListener(e -> {
            double v = monitorVolume.getValue() / 100.0;
            audio.setMonitorVolume(v);
            if (!monitorVolume.getValueIsAdjusting()) {
                config.monitorVolume = v;
                configManager.save();
            }
        });
        monRow.add(monitorVolume);
        panel.add(monRow);

        panel.add(Box.createVerticalStrut(6));

        // --- ligne MICRO ---
        JPanel micRow = row();
        micRow.add(sectionLabel("MICRO"));
        inputBox.setPreferredSize(new Dimension(240, 28));
        inputBox.addActionListener(e -> onInputSelected());
        micRow.add(inputBox);
        micToggle.setOpaque(false);
        micToggle.setForeground(Theme.TEXT);
        micToggle.setFocusable(false);
        micToggle.addActionListener(e -> onMicToggle());
        micRow.add(micToggle);
        micRow.add(Box.createHorizontalStrut(10));
        micRow.add(mutedLabel("Volume micro"));
        micVolume.setOpaque(false);
        micVolume.setPreferredSize(new Dimension(120, 26));
        micVolume.addChangeListener(e -> {
            double v = micVolume.getValue() / 100.0;
            audio.setMicVolume(v);
            if (!micVolume.getValueIsAdjusting()) {
                config.micVolume = v;
                configManager.save();
            }
        });
        micRow.add(micVolume);
        panel.add(micRow);

        panel.add(Box.createVerticalStrut(4));
        JPanel micStatusRow = row();
        micStatusRow.add(sectionLabel(""));            // aligne sous les combos
        micStatusRow.add(mutedLabel("Signal"));
        micStatusRow.add(micMeter);
        micStatusRow.add(Box.createHorizontalStrut(8));
        micStatus.setForeground(Theme.TEXT_MUTED);
        micStatus.setFont(micStatus.getFont().deriveFont(Font.PLAIN));
        micStatusRow.add(micStatus);
        panel.add(micStatusRow);

        // rafraichissement du vu-metre + detection "aucun signal"
        javax.swing.Timer meterTimer = new javax.swing.Timer(66, e -> tickMicMeter());
        meterTimer.start();

        return panel;
    }

    private void tickMicMeter() {
        double lvl = audio.getMicLevel();
        micMeter.setLevel(lvl);

        if (!micToggle.isSelected() || !audio.isMicRunning()) {
            return;
        }
        long now = System.currentTimeMillis();
        if (lvl > 0.01) {
            lastMicSignalMs = now;
            if (!micStatus.getText().equals(micBaseStatus)) {
                micStatus.setText(micBaseStatus);
                micStatus.setForeground(Theme.RED_BRIGHT);
            }
        } else if (now - lastMicSignalMs > 4500) {
            micStatus.setText("aucun signal capté — mauvais micro ? accès micro bloqué "
                    + "(Paramètres Windows ▸ Confidentialité ▸ Micro) ?");
            micStatus.setForeground(new Color(0xFF, 0x9B, 0x3A));
        }
    }

    private JPanel buildBottomPanel() {
        JPanel bottom = new JPanel();
        bottom.setLayout(new BoxLayout(bottom, BoxLayout.Y_AXIS));
        bottom.setBackground(Theme.PANEL);
        bottom.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 0, 0, Theme.BORDER_SOFT),
                BorderFactory.createEmptyBorder(10, 14, 8, 14)));

        JPanel controls = row();

        AccentButton stopBtn = new AccentButton("■  STOP — TOUT COUPER", Theme.RED);
        stopBtn.addActionListener(e -> {
            audio.stopAll();
            status.setText("Lecture stoppee.");
        });
        controls.add(stopBtn);

        controls.add(Box.createHorizontalStrut(14));
        controls.add(mutedLabel("Raccourci STOP"));
        stopHotkeyField.setEditable(false);
        stopHotkeyField.setHorizontalAlignment(SwingConstants.CENTER);
        controls.add(stopHotkeyField);
        JButton setStop = new JButton("Definir…");
        setStop.setFocusable(false);
        setStop.addActionListener(e -> captureStopHotkey());
        controls.add(setStop);
        JButton clearStop = new JButton("Effacer");
        clearStop.setFocusable(false);
        clearStop.addActionListener(e -> applyStopHotkey(null));
        controls.add(clearStop);

        controls.add(Box.createHorizontalStrut(14));
        JButton folderBtn = new JButton("Dossier des sons…");
        folderBtn.setFocusable(false);
        folderBtn.setToolTipText("Choisir où SoundX lit vos fichiers audio");
        folderBtn.addActionListener(e -> chooseSoundsFolder());
        controls.add(folderBtn);
        JButton rescanBtn = new JButton("Rescanner");
        rescanBtn.setFocusable(false);
        rescanBtn.addActionListener(e -> rescan());
        controls.add(rescanBtn);

        bottom.add(controls);
        bottom.add(Box.createVerticalStrut(4));

        JPanel statusRow = row();
        status.setForeground(Theme.TEXT_MUTED);
        status.setFont(status.getFont().deriveFont(Font.PLAIN));
        statusRow.add(status);
        bottom.add(statusRow);

        return bottom;
    }

    private static JPanel row() {
        JPanel p = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));
        p.setOpaque(false);
        p.setAlignmentX(LEFT_ALIGNMENT);
        return p;
    }

    private static JLabel sectionLabel(String text) {
        JLabel l = new JLabel(text);
        l.setForeground(Theme.RED);
        l.setFont(l.getFont().deriveFont(Font.BOLD, 11f));
        l.setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 6));
        l.setPreferredSize(new Dimension(54, 20));
        return l;
    }

    private static JLabel mutedLabel(String text) {
        JLabel l = new JLabel(text);
        l.setForeground(Theme.TEXT_MUTED);
        l.setFont(l.getFont().deriveFont(Font.PLAIN));
        return l;
    }

    // ==================================================================
    //  Peripheriques de sortie
    // ==================================================================

    private void reloadOutputDevices() {
        suppressOutputEvents = true;
        try {
            AudioDevice current = (AudioDevice) outputBox.getSelectedItem();
            String wanted = current != null && current.getInfo() != null
                    ? current.getInfo().getName()
                    : config.outputDeviceName;

            outputBox.removeAllItems();
            AudioDevice toSelect = null;
            for (AudioDevice d : audio.listOutputDevices()) {
                outputBox.addItem(d);
                if (wanted != null && d.getInfo() != null
                        && wanted.equals(d.getInfo().getName())) {
                    toSelect = d;
                }
            }
            if (toSelect == null && outputBox.getItemCount() > 0) {
                toSelect = outputBox.getItemAt(0);
            }
            outputBox.setSelectedItem(toSelect);
        } finally {
            suppressOutputEvents = false;
        }
        onOutputSelected();
    }

    private void onOutputSelected() {
        if (suppressOutputEvents) {
            return;
        }
        AudioDevice d = (AudioDevice) outputBox.getSelectedItem();
        if (d == null) {
            return;
        }
        audio.setOutputDevice(d.getInfo());
        config.outputDeviceName = d.isSystemDefault() ? null : d.getInfo().getName();
        configManager.save();
        status.setText("Sortie audio : " + d);
        refreshMonitorHint();
    }

    // ==================================================================
    //  Ecoute locale (monitoring casque)
    // ==================================================================

    private void reloadMonitorDevices() {
        suppressMonitorEvents = true;
        try {
            AudioDevice current = (AudioDevice) monitorBox.getSelectedItem();
            String wanted = current != null && current.getInfo() != null
                    ? current.getInfo().getName()
                    : config.monitorDeviceName;

            monitorBox.removeAllItems();
            AudioDevice toSelect = null;
            for (AudioDevice d : audio.listMonitorDevices()) {
                monitorBox.addItem(d);
                if (wanted != null && d.getInfo() != null
                        && wanted.equals(d.getInfo().getName())) {
                    toSelect = d;
                }
            }
            if (toSelect == null && monitorBox.getItemCount() > 0) {
                toSelect = monitorBox.getItemAt(0);
            }
            monitorBox.setSelectedItem(toSelect);
        } finally {
            suppressMonitorEvents = false;
        }
        onMonitorSelected();
    }

    private void onMonitorSelected() {
        if (suppressMonitorEvents) {
            return;
        }
        AudioDevice d = (AudioDevice) monitorBox.getSelectedItem();
        if (d == null) {
            return;
        }
        audio.setMonitorDevice(d.getInfo());
        config.monitorDeviceName = d.isSystemDefault() ? null : d.getInfo().getName();
        configManager.save();
        refreshMonitorHint();
    }

    private void onMonitorToggle() {
        boolean on = monitorToggle.isSelected();
        audio.setMonitorEnabled(on);
        config.monitorEnabled = on;
        configManager.save();
        refreshMonitorHint();
    }

    /** Prévient si l'écoute locale pointe sur le même périphérique que la sortie (redondant). */
    private void refreshMonitorHint() {
        AudioDevice out = (AudioDevice) outputBox.getSelectedItem();
        AudioDevice mon = (AudioDevice) monitorBox.getSelectedItem();
        boolean same = out != null && mon != null
                && java.util.Objects.equals(labelOf(out), labelOf(mon));
        if (monitorToggle.isSelected() && same) {
            status.setText("Écoute locale ignorée : même périphérique que la SORTIE.");
        }
        monitorToggle.setForeground(monitorToggle.isSelected() && !same
                ? Theme.RED_BRIGHT : Theme.TEXT);
    }

    private static String labelOf(AudioDevice d) {
        return d.isSystemDefault() ? " default" : d.getInfo().getName();
    }

    // ==================================================================
    //  Peripheriques d'entree (micro)
    // ==================================================================

    private void reloadInputDevices() {
        suppressInputEvents = true;
        try {
            AudioDevice current = (AudioDevice) inputBox.getSelectedItem();
            String wanted = current != null && current.getInfo() != null
                    ? current.getInfo().getName()
                    : config.inputDeviceName;
            // ancien reglage generique (suit le peripherique Windows par defaut,
            // souvent le cable) -> on force un vrai micro
            if (wanted != null && isGenericInputName(wanted)) {
                wanted = null;
            }

            inputBox.removeAllItems();
            AudioDevice toSelect = null;
            for (AudioDevice d : audio.listInputDevices()) {
                inputBox.addItem(d);
                if (wanted != null && d.getInfo() != null
                        && wanted.equals(d.getInfo().getName())) {
                    toSelect = d;
                }
            }
            boolean hasInputs = inputBox.getItemCount() > 0;
            if (toSelect == null && hasInputs) {
                toSelect = pickDefaultInput();
            }
            inputBox.setSelectedItem(toSelect);
            inputBox.setEnabled(hasInputs);
            micToggle.setEnabled(hasInputs);
            if (!hasInputs) {
                micStatus.setText("Aucun peripherique de capture detecte.");
            }
        } finally {
            suppressInputEvents = false;
        }
        onInputSelected();
    }

    /**
     * Choix par défaut de l'entrée micro : on évite le « Pilote de capture audio
     * principal » (il suit le périphérique d'enregistrement Windows par défaut,
     * souvent réglé sur le câble virtuel → aucun son) et « CABLE Output » (boucle).
     * On prend le premier vrai micro nommé.
     */
    private AudioDevice pickDefaultInput() {
        for (int i = 0; i < inputBox.getItemCount(); i++) {
            AudioDevice d = inputBox.getItemAt(i);
            if (d.getInfo() != null && !isGenericInputName(d.getInfo().getName())) {
                return d;
            }
        }
        return inputBox.getItemAt(0);
    }

    /** Entrées peu fiables pour un micro : pilote générique (suit le défaut Windows), câble, port. */
    private static boolean isGenericInputName(String name) {
        String n = name.toLowerCase();
        return n.contains("primary sound capture")
                || n.contains("pilote de capture audio principal")
                || n.contains("cable output")
                || n.startsWith("port ");
    }

    private void onInputSelected() {
        if (suppressInputEvents) {
            return;
        }
        AudioDevice d = (AudioDevice) inputBox.getSelectedItem();
        if (d == null) {
            return;
        }
        audio.setInputDevice(d.getInfo());
        config.inputDeviceName = d.isSystemDefault() ? null : d.getInfo().getName();
        configManager.save();
    }

    private void onMicToggle() {
        boolean on = micToggle.isSelected();
        audio.setMicPassthroughEnabled(on);
        config.micPassthrough = on;
        configManager.save();
    }

    private void updateMicStatus(String message, boolean active) {
        micBaseStatus = active ? message + "  —  parlez, le niveau doit bouger" : message;
        micStatus.setText(micBaseStatus);
        micStatus.setForeground(active ? Theme.RED_BRIGHT : Theme.TEXT_MUTED);
        lastMicSignalMs = System.currentTimeMillis(); // délai de grâce avant l'alerte "aucun signal"
        // resynchronise la case si le renvoi s'est arrete tout seul (erreur)
        if (!active && micToggle.isSelected() && !audio.isMicPassthroughEnabled()) {
            micToggle.setSelected(false);
        }
    }

    // ==================================================================
    //  Application de la configuration a l'UI
    // ==================================================================

    private void applyConfigToUI() {
        int sv = clampPercent((int) Math.round(config.masterVolume * 100), 100);
        soundVolume.setValue(sv);
        audio.setMasterVolume(sv / 100.0);

        // --- ecoute locale ---
        int monv = clampPercent((int) Math.round(config.monitorVolume * 100), 100);
        monitorVolume.setValue(monv);
        monitorToggle.setSelected(config.monitorEnabled);
        AudioDevice monSel = (AudioDevice) monitorBox.getSelectedItem();
        audio.configureMonitor(monSel == null ? null : monSel.getInfo(),
                config.monitorEnabled, monv / 100.0);
        refreshMonitorHint();

        int mv = clampPercent((int) Math.round(config.micVolume * 100), 200);
        micVolume.setValue(mv);

        AudioDevice inSel = (AudioDevice) inputBox.getSelectedItem();
        boolean micOn = config.micPassthrough && inputBox.isEnabled() && inSel != null;
        micToggle.setSelected(micOn);

        // une seule (re)configuration du renvoi micro
        audio.configureMic(inSel == null ? null : inSel.getInfo(), micOn, mv / 100.0);

        if (config.stopHotkey != null) {
            applyStopHotkey(new KeyCombo(config.stopHotkey.modifiers, config.stopHotkey.keyCode));
        } else {
            stopHotkeyField.setText("(aucun)");
        }
    }

    private static int clampPercent(int value, int max) {
        return Math.max(0, Math.min(max, value));
    }

    // ==================================================================
    //  Scan du dossier sounds/
    // ==================================================================

    private void rescan() {
        for (SoundEntry e : entries) {
            if (e.getHotkey() != null) {
                hook.unbind(e.getHotkey());
            }
        }
        entries.clear();
        grid.removeAll();

        File[] files = soundsDir.listFiles(f -> f.isFile() && SoundFiles.isSound(f.getName()));
        if (files == null) {
            files = new File[0];
        }
        Arrays.sort(files, Comparator.comparing(f -> f.getName().toLowerCase()));

        for (File f : files) {
            SoundEntry entry = new SoundEntry(f);
            AppConfig.HotkeyData hd = config.bindings.get(f.getName());
            if (hd != null) {
                entry.setHotkey(new KeyCombo(hd.modifiers, hd.keyCode));
                bindEntry(entry);
            }
            entries.add(entry);
            grid.add(buildSoundButton(entry));
        }

        grid.revalidate();
        grid.repaint();

        if (files.length == 0) {
            status.setText("Aucun son dans  " + soundsDir.getAbsolutePath()
                    + "   —   déposez-y des .wav / .mp3 (menu Fichier ▸ Ouvrir), puis Rescanner.");
        } else {
            status.setText(files.length + " son(s)   ▸   " + soundsDir.getAbsolutePath());
        }
    }

    private SoundButton buildSoundButton(SoundEntry entry) {
        SoundButton btn = new SoundButton(entry);
        btn.addActionListener(e -> {
            audio.play(entry.getFile());
            status.setText("▶ " + entry.getDisplayName());
        });

        JPopupMenu popup = new JPopupMenu();
        JMenuItem play = new JMenuItem("Jouer");
        play.addActionListener(e -> audio.play(entry.getFile()));
        JMenuItem assign = new JMenuItem("Assigner un raccourci global…");
        assign.addActionListener(e -> assignHotkey(entry, btn));
        JMenuItem clear = new JMenuItem("Effacer le raccourci");
        clear.addActionListener(e -> clearHotkey(entry, btn));
        popup.add(play);
        popup.addSeparator();
        popup.add(assign);
        popup.add(clear);
        btn.setComponentPopupMenu(popup);
        return btn;
    }

    // ==================================================================
    //  Raccourcis
    // ==================================================================

    private void bindEntry(SoundEntry entry) {
        if (entry.getHotkey() != null) {
            hook.bind(entry.getHotkey(), () -> audio.play(entry.getFile()));
        }
    }

    private void assignHotkey(SoundEntry entry, SoundButton btn) {
        KeyCombo combo = new KeyCaptureDialog(this, hook, entry.getDisplayName()).showDialog();
        if (combo == null) {
            status.setText("Assignation annulee.");
            return;
        }
        if (!confirmBareKey(combo)) {
            return;
        }
        if (combo.equals(stopHotkey)) {
            warn("Cette combinaison est deja utilisee par le raccourci STOP.");
            return;
        }
        for (SoundEntry other : entries) {
            if (other != entry && combo.equals(other.getHotkey())) {
                int r = JOptionPane.showConfirmDialog(this,
                        "« " + combo + " » est deja assignee a « "
                                + other.getDisplayName() + " ».\nLa reassigner a « "
                                + entry.getDisplayName() + " » ?",
                        "Conflit de raccourci", JOptionPane.YES_NO_OPTION);
                if (r != JOptionPane.YES_OPTION) {
                    return;
                }
                hook.unbind(other.getHotkey());
                other.setHotkey(null);
                config.bindings.remove(other.getFileName());
                refreshButtonFor(other);
            }
        }

        if (entry.getHotkey() != null) {
            hook.unbind(entry.getHotkey());
        }
        entry.setHotkey(combo);
        bindEntry(entry);
        config.bindings.put(entry.getFileName(),
                new AppConfig.HotkeyData(combo.getModifiers(), combo.getKeyCode()));
        configManager.save();
        btn.refresh();
        status.setText("Raccourci « " + combo + " »  →  " + entry.getDisplayName());
    }

    private void clearHotkey(SoundEntry entry, SoundButton btn) {
        if (entry.getHotkey() == null) {
            return;
        }
        hook.unbind(entry.getHotkey());
        entry.setHotkey(null);
        config.bindings.remove(entry.getFileName());
        configManager.save();
        btn.refresh();
        status.setText("Raccourci efface pour " + entry.getDisplayName());
    }

    private void captureStopHotkey() {
        KeyCombo combo = new KeyCaptureDialog(this, hook, "STOP (arret d'urgence)").showDialog();
        if (combo == null) {
            return;
        }
        if (!confirmBareKey(combo)) {
            return;
        }
        for (SoundEntry e : entries) {
            if (combo.equals(e.getHotkey())) {
                warn("Combinaison deja utilisee par « " + e.getDisplayName() + " ».");
                return;
            }
        }
        applyStopHotkey(combo);
        status.setText("Raccourci STOP : " + combo);
    }

    private void applyStopHotkey(KeyCombo combo) {
        if (stopHotkey != null) {
            hook.unbind(stopHotkey);
        }
        stopHotkey = combo;
        if (combo != null) {
            hook.bind(combo, () -> {
                audio.stopAll();
                SwingUtilities.invokeLater(() -> status.setText("STOP (raccourci global)"));
            });
            config.stopHotkey = new AppConfig.HotkeyData(combo.getModifiers(), combo.getKeyCode());
            stopHotkeyField.setText(combo.toString());
        } else {
            config.stopHotkey = null;
            stopHotkeyField.setText("(aucun)");
        }
        configManager.save();
    }

    private boolean confirmBareKey(KeyCombo c) {
        if (c.getModifiers() != 0) {
            return true;
        }
        int r = JOptionPane.showConfirmDialog(this,
                "Ce raccourci n'a aucun modificateur (Ctrl / Alt / Shift).\n"
                        + "Il se declenchera aussi pendant que vous tapez ou jouez.\nContinuer ?",
                "Raccourci sans modificateur", JOptionPane.YES_NO_OPTION,
                JOptionPane.WARNING_MESSAGE);
        return r == JOptionPane.YES_OPTION;
    }

    private void refreshButtonFor(SoundEntry entry) {
        for (Component c : grid.getComponents()) {
            if (c instanceof SoundButton && ((SoundButton) c).getEntry() == entry) {
                ((SoundButton) c).refresh();
            }
        }
    }

    // ==================================================================
    //  Zone de notification (system tray)
    // ==================================================================

    private void setupTray() {
        if (!SystemTray.isSupported()) {
            return;
        }
        try {
            PopupMenu popup = new PopupMenu();
            MenuItem show = new MenuItem("Afficher SoundX");
            show.addActionListener(e -> restoreFromTray());
            MenuItem stop = new MenuItem("Tout couper");
            stop.addActionListener(e -> audio.stopAll());
            MenuItem quit = new MenuItem("Quitter");
            quit.addActionListener(e -> exitApplication());
            popup.add(show);
            popup.add(stop);
            popup.addSeparator();
            popup.add(quit);

            trayIcon = new TrayIcon(makeIcon(16), "SoundX — Soundboard", popup);
            trayIcon.setImageAutoSize(true);
            trayIcon.addActionListener(e -> restoreFromTray());
            SystemTray.getSystemTray().add(trayIcon);
        } catch (AWTException ex) {
            trayIcon = null;
        }
    }

    private void restoreFromTray() {
        setVisible(true);
        setExtendedState(Frame.NORMAL);
        toFront();
        requestFocus();
    }

    private void handleWindowClose() {
        if (trayIcon != null) {
            setVisible(false);
            if (!trayHintShown) {
                trayIcon.displayMessage("SoundX",
                        "Toujours actif en arriere-plan : raccourcis globaux et renvoi micro "
                                + "restent fonctionnels. Cliquez l'icone pour rouvrir.",
                        TrayIcon.MessageType.INFO);
                trayHintShown = true;
            }
        } else {
            exitApplication();
        }
    }

    private void exitApplication() {
        configManager.save();
        if (trayIcon != null) {
            SystemTray.getSystemTray().remove(trayIcon);
        }
        dispose();
        System.exit(0);
    }

    // ==================================================================
    //  Divers
    // ==================================================================

    private void openSoundsFolder() {
        try {
            if (!soundsDir.exists()) {
                soundsDir.mkdirs();
            }
            if (Desktop.isDesktopSupported()) {
                Desktop.getDesktop().open(soundsDir);
            }
        } catch (Exception e) {
            status.setText("Impossible d'ouvrir le dossier : " + e.getMessage());
        }
    }

    private void chooseSoundsFolder() {
        JFileChooser chooser = new JFileChooser();
        chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        chooser.setDialogTitle("Choisir le dossier des sons");
        if (soundsDir != null && soundsDir.isDirectory()) {
            chooser.setCurrentDirectory(soundsDir.getParentFile());
            chooser.setSelectedFile(soundsDir);
        }
        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        File chosen = chooser.getSelectedFile();
        if (chosen == null) {
            return;
        }
        if (!chosen.exists() && !chosen.mkdirs()) {
            warn("Impossible de créer ce dossier :\n" + chosen.getAbsolutePath());
            return;
        }
        soundsDir = chosen;
        config.soundsDirectory = chosen.getAbsolutePath();
        configManager.save();
        rescan();
    }

    private void showAbout() {
        JOptionPane.showMessageDialog(this,
                "SoundX — Soundboard de bureau\n\n"
                        + "Java Swing • javax.sound.sampled • JNativeHook • JLayer/mp3spi • FlatLaf\n\n"
                        + "• Clic sur un bouton : jouer le son\n"
                        + "• Clic droit : assigner / effacer un raccourci clavier global\n"
                        + "• Les raccourcis se declenchent meme sans focus (jeux, etc.)\n"
                        + "• SORTIE : router les sons vers un cable audio virtuel\n"
                        + "• ÉCOUTE : jouer aussi les sons dans votre casque (monitoring)\n"
                        + "• MICRO : renvoyer le micro vers la meme sortie (micro + sons\n"
                        + "  arrivent ensemble dans le cable, comme Voicemod). Le vu-metre\n"
                        + "  \"Signal\" doit bouger quand vous parlez.\n"
                        + "• STOP : coupe instantanement toutes les lectures de sons",
                "A propos de SoundX", JOptionPane.INFORMATION_MESSAGE);
    }

    private void warn(String message) {
        JOptionPane.showMessageDialog(this, message, "SoundX", JOptionPane.WARNING_MESSAGE);
    }

    private static Image makeIcon(int s) {
        BufferedImage img = new BufferedImage(s, s, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(new Color(0x15, 0x15, 0x17));
        g.fillRoundRect(0, 0, s - 1, s - 1, Math.max(4, s / 3), Math.max(4, s / 3));
        g.setColor(Theme.RED);
        int[] xs = {(int) (s * 0.34), (int) (s * 0.34), (int) (s * 0.74)};
        int[] ys = {(int) (s * 0.24), (int) (s * 0.76), (int) (s * 0.50)};
        g.fillPolygon(xs, ys, 3);
        g.dispose();
        return img;
    }
}
