package io.github.bohdankordon.casinofingerprint.app;

import com.formdev.flatlaf.FlatDarkLaf;
import io.github.bohdankordon.casinofingerprint.capture.AwtMonitorEnumerator;
import io.github.bohdankordon.casinofingerprint.capture.CaptureException;
import io.github.bohdankordon.casinofingerprint.capture.MonitorEnumerator;
import io.github.bohdankordon.casinofingerprint.capture.MonitorInfo;
import io.github.bohdankordon.casinofingerprint.input.EmergencyAbortKey;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Desktop;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GraphicsEnvironment;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSeparator;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.JViewport;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;

/**
 * Stage 9B end-user operator window: the normal installed launcher for the
 * validated live solver.
 *
 * <p>A thin operator shell over the existing validated backend
 * (LiveSolverMain, LiveSolverOptions, LiveSolveOrchestrator, the Windows input
 * guards and the recognition pipeline). Startup is always DISARMED and sends
 * zero gameplay input: monitors are only enumerated, never captured for
 * solving, until the explicit two-step ARM flow completes.
 *
 * <p>Input-free packaging validation runs through --operator-smoke (see
 * OperatorSmoke), which never creates this window.
 */
public final class WindowsOperatorMain extends JFrame {
    /** Placeholder shown until the operator explicitly picks an abort key. */
    static final String ABORT_PLACEHOLDER = "Select emergency abort key...";

    private final MonitorEnumerator monitors;
    private final Path appRoot;

    private OperatorSessionController controller;
    private OperatorLogBridge logBridge;
    private BoundedLogModel logModel;
    private Path sessionLogFile;
    private boolean sessionEnded;
    private boolean closeRequestedWhileArmed;

    private JLabel statusBadge;
    private JLabel statusLine;
    private JComboBox<MonitorOption> monitorCombo;
    private JLabel monitorDetails;
    private JTextField targetField;
    private JComboBox<Object> abortCombo;
    private JLabel abortWarning;
    private JLabel validationLabel;
    private JLabel sessionSummary;
    private JLabel logPathLabel;
    private JButton armButton;
    private JButton stopButton;
    private JButton openLogsButton;
    private JTextArea logArea;
    private Timer logTimer;
    private int shownLogLines;

    /** Production entry point wiring the real desktop backends. */
    public static void main(String[] args) {
        for (String argument : args) {
            if (argument.equals("--help") || argument.equals("-h")) {
                System.out.print(usage());
                return;
            }
            if (argument.equals("--operator-smoke")) {
                int exit = OperatorSmoke.run(System.out, System.err,
                        ApplicationPaths.runtimeDataRoot());
                System.exit(exit);
                return;
            }
            System.err.println("error: unknown option " + argument);
            System.err.print(usage());
            System.exit(2);
            return;
        }
        try {
            if (!FlatDarkLaf.setup()) {
                throw new IllegalStateException("FlatDarkLaf.setup() refused to install");
            }
        } catch (Exception failed) {
            System.err.println("STARTUP FAILURE: the operator appearance failed to load: "
                    + failed);
            if (!GraphicsEnvironment.isHeadless()) {
                JOptionPane.showMessageDialog(null,
                        "The operator interface failed to start: " + failed,
                        "GTA Casino Fingerprint Solver - startup failure",
                        JOptionPane.ERROR_MESSAGE);
            }
            System.exit(3);
            return;
        }
        SwingUtilities.invokeLater(() -> {
            MonitorEnumerator enumerator;
            try {
                enumerator = AwtMonitorEnumerator.create();
            } catch (CaptureException failed) {
                JOptionPane.showMessageDialog(null,
                        "No interactive desktop is available: " + failed.getMessage(),
                        "GTA Casino Fingerprint Solver - startup failure",
                        JOptionPane.ERROR_MESSAGE);
                System.exit(3);
                return;
            }
            WindowsOperatorMain frame = new WindowsOperatorMain(enumerator,
                    ApplicationPaths.runtimeDataRoot());
            frame.setVisible(true);
        });
    }

    /** Command-line help, also printed for usage errors. */
    public static String usage() {
        String separator = System.lineSeparator();
        return "GTA Casino Fingerprint Solver - operator launcher (Windows only)." + separator
                + separator
                + "Usage: (no arguments)        open the operator window" + separator
                + "       --operator-smoke     input-free startup check, exits DISARMED"
                + separator
                + "       --help               print this help" + separator + separator
                + "The window starts DISARMED and sends no input until the explicit"
                + separator
                + "ARM flow completes: select a supported 2560x1440 display, the target"
                + separator
                + "executable and an emergency abort key first." + separator;
    }

    /**
     * @param monitors desktop monitor enumeration backend
     * @param appRoot packaged runtime-data root resolving dataset and fixtures
     */
    public WindowsOperatorMain(MonitorEnumerator monitors, Path appRoot) {
        super("GTA Casino Fingerprint Solver");
        if (monitors == null || appRoot == null) {
            throw new IllegalArgumentException("monitors and appRoot are required");
        }
        this.monitors = monitors;
        this.appRoot = appRoot;
        buildInterface();
        refreshMonitors();
        revalidateArm();
        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent event) {
                onCloseRequested();
            }
        });
        setMinimumSize(new Dimension(620, 780));
        setSize(680, 840);
        setLocationRelativeTo(null);
    }

    private void buildInterface() {
        JPanel content = new JPanel();
        content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
        content.setBorder(BorderFactory.createEmptyBorder(16, 20, 16, 20));

        content.add(headerPanel());
        content.add(Box.createVerticalStrut(8));
        content.add(new JSeparator());
        content.add(systemSection());
        content.add(Box.createVerticalStrut(4));
        content.add(new JSeparator());
        content.add(gameSection());
        content.add(Box.createVerticalStrut(4));
        content.add(new JSeparator());
        content.add(safetySection());
        content.add(Box.createVerticalStrut(4));
        content.add(new JSeparator());
        content.add(sessionSection());
        content.add(Box.createVerticalStrut(12));
        content.add(actionRow());
        content.add(Box.createVerticalStrut(8));
        content.add(logPanel());

        JScrollPane scroll =
                new JScrollPane(content, JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
                        JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.getViewport().setScrollMode(JViewport.SIMPLE_SCROLL_MODE);
        getContentPane().add(scroll, BorderLayout.CENTER);
    }

    private JComponent headerPanel() {
        JPanel header = new JPanel(new BorderLayout(12, 0));
        header.setAlignmentX(Component.LEFT_ALIGNMENT);
        JLabel title = new JLabel("GTA Casino Fingerprint Solver");
        title.setFont(title.getFont().deriveFont(Font.BOLD, 17f));
        header.add(title, BorderLayout.WEST);
        statusBadge = new JLabel(OperatorSessionState.DISARMED.badge(),
                javax.swing.SwingConstants.CENTER);
        statusBadge.setOpaque(true);
        statusBadge.setBorder(BorderFactory.createEmptyBorder(6, 14, 6, 14));
        paintBadge(OperatorSessionState.DISARMED);
        header.add(statusBadge, BorderLayout.EAST);
        statusLine = new JLabel(OperatorSessionState.DISARMED.description());
        statusLine.setEnabled(false);
        JPanel stacked = new JPanel();
        stacked.setLayout(new BoxLayout(stacked, BoxLayout.Y_AXIS));
        stacked.setAlignmentX(Component.LEFT_ALIGNMENT);
        stacked.add(header);
        stacked.add(Box.createVerticalStrut(2));
        statusLine.setAlignmentX(Component.LEFT_ALIGNMENT);
        stacked.add(statusLine);
        return stacked;
    }

    private JComponent sectionTitle(String text) {
        JLabel title = new JLabel(text);
        title.setFont(title.getFont().deriveFont(Font.BOLD, 13f));
        title.setAlignmentX(Component.LEFT_ALIGNMENT);
        return title;
    }

    private JComponent systemSection() {
        JPanel section = new JPanel();
        section.setLayout(new BoxLayout(section, BoxLayout.Y_AXIS));
        section.setAlignmentX(Component.LEFT_ALIGNMENT);
        section.setBorder(BorderFactory.createEmptyBorder(12, 0, 8, 0));
        section.add(sectionTitle("System"));
        section.add(Box.createVerticalStrut(6));
        JLabel display = new JLabel("Display");
        display.setAlignmentX(Component.LEFT_ALIGNMENT);
        section.add(display);
        section.add(Box.createVerticalStrut(4));
        monitorCombo = new JComboBox<>();
        monitorCombo.setAlignmentX(Component.LEFT_ALIGNMENT);
        monitorCombo.setMaximumSize(
                new Dimension(Integer.MAX_VALUE, monitorCombo.getPreferredSize().height));
        monitorCombo.addActionListener(event -> {
            updateMonitorDetails();
            revalidateArm();
        });
        section.add(monitorCombo);
        section.add(Box.createVerticalStrut(4));
        monitorDetails = new JLabel(" ");
        monitorDetails.setEnabled(false);
        monitorDetails.setAlignmentX(Component.LEFT_ALIGNMENT);
        section.add(monitorDetails);
        section.add(Box.createVerticalStrut(6));
        JButton refresh = new JButton("Refresh Monitors");
        refresh.setAlignmentX(Component.LEFT_ALIGNMENT);
        refresh.addActionListener(event -> {
            refreshMonitors();
            revalidateArm();
        });
        section.add(refresh);
        return section;
    }

    private JComponent gameSection() {
        JPanel section = new JPanel();
        section.setLayout(new BoxLayout(section, BoxLayout.Y_AXIS));
        section.setAlignmentX(Component.LEFT_ALIGNMENT);
        section.setBorder(BorderFactory.createEmptyBorder(12, 0, 8, 0));
        section.add(sectionTitle("Game"));
        section.add(Box.createVerticalStrut(6));
        JLabel target = new JLabel("Target executable");
        target.setAlignmentX(Component.LEFT_ALIGNMENT);
        section.add(target);
        section.add(Box.createVerticalStrut(4));
        targetField = new JTextField(OperatorArmPolicy.DEFAULT_TARGET_EXECUTABLE);
        targetField.setAlignmentX(Component.LEFT_ALIGNMENT);
        targetField.setMaximumSize(
                new Dimension(Integer.MAX_VALUE, targetField.getPreferredSize().height));
        targetField.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent event) {
                revalidateArm();
            }

            @Override
            public void removeUpdate(DocumentEvent event) {
                revalidateArm();
            }

            @Override
            public void changedUpdate(DocumentEvent event) {
                revalidateArm();
            }
        });
        section.add(targetField);
        section.add(Box.createVerticalStrut(4));
        JLabel hint = new JLabel(
                "The exact foreground executable the input guard will require.");
        hint.setEnabled(false);
        hint.setAlignmentX(Component.LEFT_ALIGNMENT);
        section.add(hint);
        return section;
    }

    private JComponent safetySection() {
        JPanel section = new JPanel();
        section.setLayout(new BoxLayout(section, BoxLayout.Y_AXIS));
        section.setAlignmentX(Component.LEFT_ALIGNMENT);
        section.setBorder(BorderFactory.createEmptyBorder(12, 0, 8, 0));
        section.add(sectionTitle("Safety"));
        section.add(Box.createVerticalStrut(6));
        JLabel abort = new JLabel("Emergency abort key");
        abort.setAlignmentX(Component.LEFT_ALIGNMENT);
        section.add(abort);
        section.add(Box.createVerticalStrut(4));
        abortCombo = new JComboBox<>();
        abortCombo.setAlignmentX(Component.LEFT_ALIGNMENT);
        abortCombo.setMaximumSize(
                new Dimension(Integer.MAX_VALUE, abortCombo.getPreferredSize().height));
        abortCombo.addItem(ABORT_PLACEHOLDER);
        for (EmergencyAbortKey key : EmergencyAbortKey.values()) {
            abortCombo.addItem(key);
        }
        abortCombo.addActionListener(event -> {
            updateAbortWarning();
            revalidateArm();
        });
        section.add(abortCombo);
        section.add(Box.createVerticalStrut(4));
        abortWarning = new JLabel(" ");
        abortWarning.setAlignmentX(Component.LEFT_ALIGNMENT);
        section.add(abortWarning);
        return section;
    }

    private JComponent sessionSection() {
        JPanel section = new JPanel();
        section.setLayout(new BoxLayout(section, BoxLayout.Y_AXIS));
        section.setAlignmentX(Component.LEFT_ALIGNMENT);
        section.setBorder(BorderFactory.createEmptyBorder(12, 0, 8, 0));
        section.add(sectionTitle("Session"));
        section.add(Box.createVerticalStrut(6));
        JLabel log = new JLabel("Log");
        log.setAlignmentX(Component.LEFT_ALIGNMENT);
        section.add(log);
        section.add(Box.createVerticalStrut(4));
        logPathLabel = new JLabel("Created automatically when a session is armed.");
        logPathLabel.setEnabled(false);
        logPathLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        section.add(logPathLabel);
        section.add(Box.createVerticalStrut(6));
        openLogsButton = new JButton("Open Logs Folder");
        openLogsButton.setAlignmentX(Component.LEFT_ALIGNMENT);
        openLogsButton.addActionListener(event -> openLogsFolder());
        section.add(openLogsButton);
        section.add(Box.createVerticalStrut(6));
        sessionSummary = new JLabel(" ");
        sessionSummary.setAlignmentX(Component.LEFT_ALIGNMENT);
        section.add(sessionSummary);
        return section;
    }

    private JComponent actionRow() {
        JPanel row = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        row.setAlignmentX(Component.LEFT_ALIGNMENT);
        validationLabel = new JLabel(" ");
        stopButton = new JButton("STOP SESSION");
        stopButton.setEnabled(false);
        stopButton.addActionListener(event -> onStopRequested());
        stopButton.setToolTipText("Requests orderly solver shutdown."
                + " The physical abort key stays the immediate input stop.");
        armButton = new JButton("ARM & START LIVE INPUT");
        armButton.setEnabled(false);
        armButton.addActionListener(event -> onArmPressed());
        row.add(validationLabel);
        row.add(stopButton);
        row.add(armButton);
        return row;
    }

    private JComponent logPanel() {
        logArea = new JTextArea(12, 60);
        logArea.setEditable(false);
        logArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        logArea.setAlignmentX(Component.LEFT_ALIGNMENT);
        JScrollPane scroll = new JScrollPane(logArea);
        scroll.setAlignmentX(Component.LEFT_ALIGNMENT);
        return scroll;
    }

    private void refreshMonitors() {
        List<MonitorInfo> detected;
        try {
            detected = new ArrayList<>(monitors.enumerate());
        } catch (CaptureException failed) {
            monitorCombo.removeAllItems();
            monitorDetails.setText("Monitor enumeration failed: " + failed.getMessage());
            return;
        }
        monitorCombo.removeAllItems();
        for (MonitorInfo info : detected) {
            monitorCombo.addItem(new MonitorOption(info));
        }
        Optional<MonitorInfo> preselected = OperatorArmPolicy.preselectCandidate(detected);
        if (preselected.isPresent()) {
            for (int index = 0; index < monitorCombo.getItemCount(); index++) {
                if (monitorCombo.getItemAt(index).info.equals(preselected.get())) {
                    monitorCombo.setSelectedIndex(index);
                    break;
                }
            }
        }
        updateMonitorDetails();
    }

    private void updateMonitorDetails() {
        MonitorOption selected = (MonitorOption) monitorCombo.getSelectedItem();
        if (selected == null) {
            monitorDetails.setText("No display selected.");
            return;
        }
        MonitorInfo info = selected.info;
        String support = info.supportsPhysicalResolution(
                OperatorArmPolicy.REQUIRED_WIDTH, OperatorArmPolicy.REQUIRED_HEIGHT)
                ? "SUPPORTED" : "UNSUPPORTED (production needs physical 2560x1440)";
        monitorDetails.setText("logical " + info.logicalBounds().describe()
                + " - physical " + info.displayMode().describe() + " - " + support);
    }

    private EmergencyAbortKey selectedAbortKey() {
        Object selected = abortCombo.getSelectedItem();
        return selected instanceof EmergencyAbortKey ? (EmergencyAbortKey) selected : null;
    }

    private MonitorInfo selectedMonitor() {
        MonitorOption selected = (MonitorOption) monitorCombo.getSelectedItem();
        return selected == null ? null : selected.info;
    }

    private void updateAbortWarning() {
        Optional<String> warning =
                OperatorArmPolicy.abortConflictText(selectedAbortKey());
        abortWarning.setText(warning.orElse(" "));
        abortWarning.setForeground(warning.isPresent() ? new Color(0xE0A800) : null);
    }

    private void revalidateArm() {
        if (armButton == null) {
            return;
        }
        OperatorArmPolicy.Eligibility eligibility = OperatorArmPolicy.check(
                selectedMonitor(), targetField.getText(), selectedAbortKey(),
                sessionEnded);
        boolean mayArm = eligibility.allowed() && controller == null;
        armButton.setEnabled(mayArm);
        validationLabel.setText(mayArm ? " " : eligibility.explain());
    }

    private void onArmPressed() {
        MonitorInfo monitor = selectedMonitor();
        String target = targetField.getText() == null ? "" : targetField.getText().strip();
        EmergencyAbortKey abortKey = selectedAbortKey();
        OperatorArmPolicy.Eligibility eligibility =
                OperatorArmPolicy.check(monitor, target, abortKey, sessionEnded);
        if (!eligibility.allowed() || controller != null) {
            validationLabel.setText(eligibility.explain());
            return;
        }
        String separator = System.lineSeparator();
        String conflict = OperatorArmPolicy.abortConflictText(abortKey)
                .map(text -> separator + text).orElse("");
        String message = "Monitor:" + separator + "  " + monitor.describe()
                + separator + separator
                + "Physical resolution: 2560x1440" + separator
                + "Target executable: " + target + separator
                + "Emergency abort: " + abortKey.symbolicName() + conflict
                + separator
                + "Input delivery: SCANCODE_BATCH" + separator
                + "Production profile: 2560x1440" + separator + separator
                + "The selected emergency abort key stops further gameplay input immediately.";
        Object[] options = {"Cancel", "ARM LIVE INPUT"};
        int choice = JOptionPane.showOptionDialog(this, message,
                "Confirm live input - GTA Casino Fingerprint Solver",
                JOptionPane.DEFAULT_OPTION, JOptionPane.WARNING_MESSAGE, null, options,
                options[0]);
        if (choice != 1) {
            return;
        }
        armNow(monitor, target, abortKey);
    }

    private void armNow(MonitorInfo monitor, String target, EmergencyAbortKey abortKey) {
        Path logDir = SessionLogPolicy.productLogDirectory();
        Path logFile;
        try {
            logFile = SessionLogPolicy.createNewLogFile(logDir, Instant.now(),
                    ProcessHandle.current().pid());
        } catch (IOException failed) {
            JOptionPane.showMessageDialog(this,
                    "Could not create the session log: " + failed.getMessage(),
                    "GTA Casino Fingerprint Solver - cannot arm",
                    JOptionPane.ERROR_MESSAGE);
            return;
        }
        BoundedLogModel model = new BoundedLogModel();
        OperatorLogBridge bridge;
        try {
            bridge = new OperatorLogBridge(logFile, model);
        } catch (IOException failed) {
            JOptionPane.showMessageDialog(this,
                    "Could not open the session log: " + failed.getMessage(),
                    "GTA Casino Fingerprint Solver - cannot arm",
                    JOptionPane.ERROR_MESSAGE);
            return;
        }
        ArmedSessionConfig config;
        try {
            config = new ArmedSessionConfig(monitor.index(), target, abortKey);
        } catch (IllegalArgumentException failed) {
            try {
                bridge.close();
            } catch (IOException ignored) {
                // Closing a freshly opened log cannot fail the ARM refusal path.
            }
            validationLabel.setText(failed.getMessage());
            return;
        }
        this.logModel = model;
        this.logBridge = bridge;
        this.sessionLogFile = logFile;
        this.shownLogLines = 0;
        logPathLabel.setText(logFile.toAbsolutePath().toString());
        writeSessionHeader(bridge.printStream(), monitor, target, abortKey);
        OperatorSessionController started = new OperatorSessionController(
                new LiveSolverSessionRunner(), SwingUtilities::invokeLater,
                this::onSessionState);
        this.controller = started;
        logTimer = new Timer(400, event -> refreshLogArea());
        logTimer.start();
        started.startSession(config, appRoot, bridge.printStream(), bridge.printStream());
        revalidateArm();
    }

    private void writeSessionHeader(PrintStream out, MonitorInfo monitor, String target,
            EmergencyAbortKey abortKey) {
        String timestamp = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
                .withZone(ZoneId.systemDefault()).format(Instant.now());
        out.println("OPERATOR SESSION START: " + timestamp);
        String version = WindowsOperatorMain.class.getPackage().getImplementationVersion();
        out.println("OPERATOR SESSION: application version: "
                + (version == null ? "development build" : version));
        out.println("OPERATOR SESSION: java.version: "
                + System.getProperty("java.version", ""));
        out.println("OPERATOR SESSION: java.home: "
                + System.getProperty("java.home", ""));
        out.println("OPERATOR SESSION: application root: " + appRoot.toAbsolutePath());
        out.println("OPERATOR SESSION: os: " + System.getProperty("os.name", "") + " "
                + System.getProperty("os.version", "") + " "
                + System.getProperty("os.arch", ""));
        out.println("OPERATOR SESSION: monitor: " + monitor.describe());
        out.println("OPERATOR SESSION: physical display mode: "
                + monitor.displayMode().describe());
        out.println("OPERATOR SESSION: target executable: " + target);
        out.println("OPERATOR SESSION: emergency abort: " + abortKey.symbolicName()
                + " (hold to stop input immediately)");
        OperatorArmPolicy.abortConflictText(abortKey).ifPresent(out::println);
        out.println("OPERATOR SESSION: input delivery: SCANCODE_BATCH");
        out.println("OPERATOR SESSION: production profile: 2560x1440");
    }

    private void onStopRequested() {
        if (controller != null) {
            controller.requestStop();
        }
    }

    private void onCloseRequested() {
        if (controller != null && controller.state().isArmed()) {
            EmergencyAbortKey abortKey = selectedAbortKey();
            String immediate = abortKey == null ? "the configured emergency abort key"
                    : abortKey.symbolicName();
            JOptionPane.showMessageDialog(this,
                    "Closing the window requests orderly solver shutdown and does not"
                            + " leave background input running." + System.lineSeparator()
                            + "Hold " + immediate + " for the immediate input stop.",
                    "GTA Casino Fingerprint Solver - stopping",
                    JOptionPane.INFORMATION_MESSAGE);
            closeRequestedWhileArmed = true;
            boolean mayDispose = controller.onWindowClosing();
            if (mayDispose) {
                dispose();
            }
            return;
        }
        dispose();
    }

    private void onSessionState(OperatorSessionState state) {
        paintBadge(state);
        statusLine.setText(state.description());
        if (state == OperatorSessionState.ARMED_WATCHING) {
            EmergencyAbortKey abortKey = selectedAbortKey();
            String key = abortKey == null ? "abort key" : abortKey.symbolicName();
            sessionSummary.setText("Hold " + key
                    + " to stop gameplay input immediately. STOP SESSION requests"
                    + " orderly shutdown and is not the emergency stop.");
            stopButton.setEnabled(true);
        } else {
            stopButton.setEnabled(false);
        }
        if (state.isTerminal()) {
            sessionEnded = true;
            refreshLogArea();
            if (logTimer != null) {
                logTimer.stop();
            }
            if (logBridge != null) {
                try {
                    logBridge.close();
                } catch (IOException failed) {
                    logArea.append(System.lineSeparator()
                            + "Could not close the session log: " + failed.getMessage());
                }
                logBridge = null;
            }
            sessionSummary.setText("Session " + state.badge()
                    + ". Restart the application to arm a new live session.");
            if (closeRequestedWhileArmed) {
                dispose();
            }
        }
        revalidateArm();
    }

    private void refreshLogArea() {
        if (logModel == null || logArea == null) {
            return;
        }
        List<String> lines = logModel.snapshot();
        StringBuilder pending = new StringBuilder();
        for (int index = shownLogLines; index < lines.size(); index++) {
            pending.append(lines.get(index)).append(System.lineSeparator());
        }
        shownLogLines = lines.size();
        if (pending.length() > 0) {
            logArea.append(pending.toString());
            int total = logArea.getLineCount();
            if (total > BoundedLogModel.MAX_LINES + 200) {
                try {
                    int start = logArea.getLineStartOffset(total - BoundedLogModel.MAX_LINES);
                    logArea.replaceRange("", 0, start);
                } catch (javax.swing.text.BadLocationException ignored) {
                    // Trimming is best effort; the model stays the bounded source.
                }
            }
            logArea.setCaretPosition(logArea.getDocument().getLength());
        }
    }

    private void openLogsFolder() {
        Path folder = sessionLogFile == null ? SessionLogPolicy.productLogDirectory()
                : sessionLogFile.toAbsolutePath().getParent();
        try {
            Files.createDirectories(folder);
            if (!Desktop.isDesktopSupported()
                    || !Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) {
                throw new IOException("Opening folders is not supported on this desktop");
            }
            Desktop.getDesktop().open(folder.toFile());
        } catch (Exception failed) {
            JOptionPane.showMessageDialog(this,
                    "Could not open the logs folder: " + folder.toAbsolutePath()
                            + System.lineSeparator() + failed.getMessage(),
                    "GTA Casino Fingerprint Solver - logs",
                    JOptionPane.ERROR_MESSAGE);
        }
    }

    private void paintBadge(OperatorSessionState state) {
        Color background;
        Color foreground;
        switch (state) {
            case ARMED_WATCHING:
                background = new Color(0x8A5A00);
                foreground = Color.WHITE;
                break;
            case STOPPING:
                background = new Color(0x1F5A8A);
                foreground = Color.WHITE;
                break;
            case FAULTED:
            case ABORTED:
                background = new Color(0x8A1F1F);
                foreground = Color.WHITE;
                break;
            default:
                background = new Color(0x2B2F33);
                foreground = new Color(0xBBBBBB);
                break;
        }
        statusBadge.setText(state.badge());
        statusBadge.setBackground(background);
        statusBadge.setForeground(foreground);
    }

    /** Combo-box view of one attached monitor; never a bare numeric index. */
    private static final class MonitorOption {
        final MonitorInfo info;

        MonitorOption(MonitorInfo info) {
            this.info = info;
        }

        @Override
        public String toString() {
            StringBuilder text = new StringBuilder();
            text.append(info.deviceId());
            if (info.primary()) {
                text.append(" - Primary");
            }
            text.append(" - physical ").append(info.displayMode().describe());
            boolean supported = info.supportsPhysicalResolution(
                    OperatorArmPolicy.REQUIRED_WIDTH, OperatorArmPolicy.REQUIRED_HEIGHT);
            text.append(supported ? " - SUPPORTED" : " - UNSUPPORTED");
            return text.toString();
        }
    }
}
