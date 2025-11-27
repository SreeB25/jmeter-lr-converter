package com.sree.jmeter.lrconverter;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.WindowConstants;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Frame;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.event.ActionEvent;
import java.io.File;

/**
 * Simple UI window for JMX -> LoadRunner converter.
 * Uses ConverterCore.convert(...) in the background and shows basic log + progress bar.
 */
public class LrConverterWindow extends JDialog {

    private JTextField jmxField;
    private JTextField outputField;
    private JButton browseJmxButton;
    private JButton browseOutButton;
    private JButton convertButton;
    private JButton closeButton;
    private JProgressBar progressBar;
    private JTextArea logArea;
    private JLabel statusLabel;

    public LrConverterWindow(Frame owner) {
        super(owner, "JMX → LoadRunner Converter", true);
        initComponents();
        buildLayout();
        attachListeners();
        setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        setSize(600, 400);
        setLocationRelativeTo(owner);
    }

    private void initComponents() {
        jmxField = new JTextField();
        outputField = new JTextField();

        browseJmxButton = new JButton("Browse...");
        browseOutButton = new JButton("Browse...");
        convertButton = new JButton("Convert");
        closeButton = new JButton("Close");

        progressBar = new JProgressBar();
        progressBar.setStringPainted(true);
        progressBar.setMinimum(0);
        progressBar.setMaximum(100);
        progressBar.setValue(0);

        logArea = new JTextArea();
        logArea.setEditable(false);
        logArea.setLineWrap(true);
        logArea.setWrapStyleWord(true);

        statusLabel = new JLabel("Idle");
    }

    private void buildLayout() {
        JPanel content = new JPanel(new BorderLayout(8, 8));
        content.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        // Top panel with fields
        JPanel topPanel = new JPanel(new GridBagLayout());
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(4, 4, 4, 4);
        gbc.fill = GridBagConstraints.HORIZONTAL;

        // JMX row
        gbc.gridx = 0; gbc.gridy = 0; gbc.weightx = 0;
        topPanel.add(new JLabel("JMX file:"), gbc);
        gbc.gridx = 1; gbc.gridy = 0; gbc.weightx = 1.0;
        topPanel.add(jmxField, gbc);
        gbc.gridx = 2; gbc.gridy = 0; gbc.weightx = 0;
        topPanel.add(browseJmxButton, gbc);

        // Output row
        gbc.gridx = 0; gbc.gridy = 1; gbc.weightx = 0;
        topPanel.add(new JLabel("Output folder:"), gbc);
        gbc.gridx = 1; gbc.gridy = 1; gbc.weightx = 1.0;
        topPanel.add(outputField, gbc);
        gbc.gridx = 2; gbc.gridy = 1; gbc.weightx = 0;
        topPanel.add(browseOutButton, gbc);

        // Log area
        JScrollPane logScroll = new JScrollPane(logArea,
                JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
                JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        logScroll.setPreferredSize(new Dimension(400, 200));

        // Progress + status
        JPanel progressPanel = new JPanel(new BorderLayout(4, 4));
        progressPanel.add(progressBar, BorderLayout.CENTER);
        progressPanel.add(statusLabel, BorderLayout.SOUTH);

        // Buttons + signature
        JPanel buttonsPanel = new JPanel(new BorderLayout());
        JPanel leftButtons = new JPanel(new FlowLayout(FlowLayout.LEFT));
        JPanel rightButtons = new JPanel(new FlowLayout(FlowLayout.RIGHT));

        leftButtons.add(convertButton);
        rightButtons.add(closeButton);

        buttonsPanel.add(leftButtons, BorderLayout.WEST);
        buttonsPanel.add(rightButtons, BorderLayout.EAST);

        JLabel signature = new JLabel("Plugin by SreeBommakanti");
        signature.setHorizontalAlignment(SwingConstants.CENTER);

        JPanel bottomPanel = new JPanel(new BorderLayout(4, 4));
        bottomPanel.add(buttonsPanel, BorderLayout.NORTH);
        bottomPanel.add(signature, BorderLayout.SOUTH);

        content.add(topPanel, BorderLayout.NORTH);
        content.add(logScroll, BorderLayout.CENTER);
        content.add(progressPanel, BorderLayout.SOUTH);
        content.add(bottomPanel, BorderLayout.PAGE_END);

        setContentPane(content);
    }

    private void attachListeners() {
        browseJmxButton.addActionListener(this::onBrowseJmx);
        browseOutButton.addActionListener(this::onBrowseOut);
        convertButton.addActionListener(this::onConvert);
        closeButton.addActionListener(e -> dispose());
    }

    private void onBrowseJmx(ActionEvent e) {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Select JMeter JMX file");
        chooser.setFileSelectionMode(JFileChooser.FILES_ONLY);
        int res = chooser.showOpenDialog(this);
        if (res == JFileChooser.APPROVE_OPTION) {
            File f = chooser.getSelectedFile();
            jmxField.setText(f.getAbsolutePath());
        }
    }

    private void onBrowseOut(ActionEvent e) {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Select output folder for LoadRunner scripts");
        chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        int res = chooser.showOpenDialog(this);
        if (res == JFileChooser.APPROVE_OPTION) {
            File f = chooser.getSelectedFile();
            outputField.setText(f.getAbsolutePath());
        }
    }

    private void onConvert(ActionEvent e) {
        String jmxPath = jmxField.getText().trim();
        String outPath = outputField.getText().trim();

        if (jmxPath.isEmpty()) {
            appendLog("Please select a JMX file.");
            return;
        }
        if (outPath.isEmpty()) {
            appendLog("Please select an output folder.");
            return;
        }

        File jmxFile = new File(jmxPath);
        if (!jmxFile.exists()) {
            appendLog("JMX file does not exist: " + jmxFile.getAbsolutePath());
            return;
        }

        File outDir = new File(outPath);
        if (!outDir.exists() && !outDir.mkdirs()) {
            appendLog("Cannot create output folder: " + outDir.getAbsolutePath());
            return;
        }

        runConversion(jmxFile, outDir);
    }

    private void runConversion(File jmxFile, File outDir) {
        convertButton.setEnabled(false);
        browseJmxButton.setEnabled(false);
        browseOutButton.setEnabled(false);
        progressBar.setIndeterminate(true);
        progressBar.setString("Converting...");
        statusLabel.setText("Converting " + jmxFile.getName() + "...");
        logArea.setText("");
        appendLog("Starting conversion...");
        appendLog("JMX: " + jmxFile.getAbsolutePath());
        appendLog("Output: " + outDir.getAbsolutePath());

        // Simple background thread (no SwingWorker to avoid extra imports)
        Thread t = new Thread(() -> {
            String error = null;
            try {
                ConverterCore.convert(jmxFile, outDir);
            } catch (Exception ex) {
                error = ex.getMessage();
                ex.printStackTrace();
            }

            final String errFinal = error;
            SwingUtilities.invokeLater(() -> {
                progressBar.setIndeterminate(false);
                progressBar.setValue(100);
                convertButton.setEnabled(true);
                browseJmxButton.setEnabled(true);
                browseOutButton.setEnabled(true);

                if (errFinal == null) {
                    progressBar.setString("Completed");
                    statusLabel.setText("Conversion completed successfully.");
                    appendLog("Conversion completed successfully.");
                } else {
                    progressBar.setString("Failed");
                    statusLabel.setText("Conversion failed.");
                    appendLog("ERROR: " + errFinal);
                }
            });
        });

        t.start();
    }

    private void appendLog(String text) {
        logArea.append(text + "\n");
        logArea.setCaretPosition(logArea.getDocument().getLength());
    }
}
