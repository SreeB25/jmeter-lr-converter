package com.sree.jmeter.lrconverter;

import org.apache.jmeter.gui.plugin.MenuCreator;

import javax.swing.*;
import javax.swing.MenuElement;
import java.awt.event.ActionEvent;
import java.io.File;

/**
 * JMeter Tools menu integration for JMX -> LoadRunner converter.
 * Shows a success dialog with signature "Plugin by SreeBommakanti" at the bottom.
 */
public class LrConverterMenuCreator implements MenuCreator {

    @Override
    public JMenuItem[] getMenuItemsAtLocation(MENU_LOCATION location) {
        if (location == MENU_LOCATION.TOOLS) {
            JMenuItem item = new JMenuItem("Convert JMX to LoadRunner...");
            item.addActionListener(this::onMenuClicked);
            return new JMenuItem[]{item};
        }
        return new JMenuItem[0];
    }

    @Override
    public JMenu[] getTopLevelMenus() {
        // We don't add a full top-level menu, just a Tools item.
        return new JMenu[0];
    }

    @Override
    public void localeChanged() {
        // No localization yet
    }

    @Override
    public boolean localeChanged(MenuElement menu) {
        return false;
    }

    private void onMenuClicked(ActionEvent e) {
        SwingUtilities.invokeLater(() -> {
            try {
                // Choose JMX file
                JFileChooser jmxChooser = new JFileChooser();
                jmxChooser.setDialogTitle("Select JMeter .jmx script");
                jmxChooser.setFileSelectionMode(JFileChooser.FILES_ONLY);

                int res = jmxChooser.showOpenDialog(null);
                if (res != JFileChooser.APPROVE_OPTION) {
                    return;
                }

                File jmxFile = jmxChooser.getSelectedFile();

                // Choose output directory
                JFileChooser outChooser = new JFileChooser();
                outChooser.setDialogTitle("Select output directory for LoadRunner scripts");
                outChooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);

                int resOut = outChooser.showSaveDialog(null);
                if (resOut != JFileChooser.APPROVE_OPTION) {
                    return;
                }

                File outDir = outChooser.getSelectedFile();

                // Run conversion
                ConverterCore.convert(jmxFile, outDir);

                // Success dialog with signature at bottom
                String msg =
                        "Conversion complete.\n" +
                        "Output folder:\n" + outDir.getAbsolutePath() + "\n\n" +
                        "----------------------------------------\n" +
                        "Plugin by SreeBommakanti";
                JOptionPane.showMessageDialog(
                        null,
                        msg,
                        "JMX → LoadRunner",
                        JOptionPane.INFORMATION_MESSAGE
                );
            } catch (Exception ex) {
                ex.printStackTrace();
                String msg =
                        "Conversion failed: " + ex.getMessage() + "\n\n" +
                        "----------------------------------------\n" +
                        "Plugin by SreeBommakanti";
                JOptionPane.showMessageDialog(
                        null,
                        msg,
                        "JMX → LoadRunner",
                        JOptionPane.ERROR_MESSAGE
                );
            }
        });
    }
}
