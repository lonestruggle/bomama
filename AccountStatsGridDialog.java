package com.combatbot;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingConstants;
import javax.swing.SwingWorker;
import javax.swing.WindowConstants;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dialog;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridLayout;
import java.awt.Window;
import java.net.URL;
import java.util.Map;

/**
 * OSRS-stats in een grid (wiki-iconen), vergelijkbaar met de meegeleverde React-layout.
 */
public final class AccountStatsGridDialog {

    private static final Color BG = new Color(36, 36, 36);
    private static final Color BORDER = new Color(56, 56, 56);
    private static final Color TEXT_DIM = new Color(207, 207, 207);
    private static final Color TITLE = new Color(251, 146, 60);

    private static final String[][] SKILL_GRID = {
            {"Attack", "Hitpoints", "Mining"},
            {"Strength", "Agility", "Smithing"},
            {"Defence", "Herblore", "Fishing"},
            {"Ranged", "Thieving", "Cooking"},
            {"Prayer", "Crafting", "Firemaking"},
            {"Magic", "Fletching", "Woodcutting"},
            {"Runecraft", "Slayer", "Farming"},
            {"Construction", "Hunter", "Sailing"}
    };

    private AccountStatsGridDialog() {
    }

    private static String iconUrl(String skillName) {
        if (skillName == null) {
            return null;
        }
        if ("Overall".equals(skillName)) {
            return "https://oldschool.runescape.wiki/images/Stats_icon.png";
        }
        if ("Combat".equals(skillName)) {
            return "https://oldschool.runescape.wiki/images/Combat_icon.png";
        }
        return "https://oldschool.runescape.wiki/images/" + skillName + "_icon.png";
    }

    private static JPanel skillCell(Map<String, String> stats, String skill) {
        JPanel cell = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        cell.setOpaque(false);
        String lvl = stats != null ? stats.getOrDefault(skill, "—") : "—";
        JLabel img = new JLabel();
        try {
            URL u = new URL(iconUrl(skill));
            img.setIcon(new javax.swing.ImageIcon(u));
        } catch (Exception e) {
            img.setText("?");
            img.setForeground(TEXT_DIM);
        }
        cell.add(img);
        JLabel num = new JLabel(lvl);
        num.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 15));
        num.setForeground(TEXT_DIM);
        cell.add(num);
        return cell;
    }

    private static JPanel buildGridPanel(Map<String, String> stats) {
        JPanel grid = new JPanel(new GridLayout(SKILL_GRID.length, 3, 6, 8));
        grid.setOpaque(false);
        for (String[] row : SKILL_GRID) {
            for (String skill : row) {
                grid.add(skillCell(stats, skill));
            }
        }
        grid.setBorder(BorderFactory.createEmptyBorder(4, 8, 8, 8));
        return grid;
    }

    private static JPanel bottomBar(Map<String, String> stats) {
        JPanel bar = new JPanel(new FlowLayout(FlowLayout.CENTER, 24, 4));
        bar.setOpaque(false);
        String combat = OsrsHiscoreApi.computeCombatLevelReactDisplay(stats);
        bar.add(labeledIcon("Combat", combat));
        String overall = stats != null ? stats.getOrDefault("Overall", "—") : "—";
        bar.add(labeledIcon("Overall", overall));
        return bar;
    }

    private static JPanel labeledIcon(String kind, String value) {
        JPanel p = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        p.setOpaque(false);
        JLabel img = new JLabel();
        try {
            img.setIcon(new javax.swing.ImageIcon(new URL(iconUrl(kind))));
        } catch (Exception e) {
            img.setText(kind.substring(0, 1));
            img.setForeground(TEXT_DIM);
        }
        p.add(img);
        JLabel v = new JLabel(value);
        v.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 15));
        v.setForeground(TEXT_DIM);
        p.add(v);
        return p;
    }

    /**
     * Haalt live stats op via {@link OsrsHiscoreApi#fetchFullSkillLevels(String)} en toont een modal dialoog.
     */
    public static void show(Window parent, String displayName) {
        if (displayName == null || displayName.trim().isEmpty()) {
            return;
        }
        final String name = displayName.trim();

        SwingWorker<Map<String, String>, Void> worker = new SwingWorker<Map<String, String>, Void>() {
            @Override
            protected Map<String, String> doInBackground() {
                return OsrsHiscoreApi.fetchFullSkillLevels(name);
            }

            @Override
            protected void done() {
                try {
                    Map<String, String> stats = get();
                    JDialog dlg = new JDialog(parent, "Stats — " + name, Dialog.ModalityType.APPLICATION_MODAL);
                    dlg.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);

                    JPanel root = new JPanel(new BorderLayout());
                    root.setBackground(BG);
                    root.setBorder(BorderFactory.createCompoundBorder(
                            BorderFactory.createLineBorder(BORDER),
                            BorderFactory.createEmptyBorder(8, 10, 10, 10)));

                    if (stats == null || stats.isEmpty()) {
                        JLabel err = new JLabel("<html><div style='color:#f87171;width:260px'>"
                                + "Kon geen hiscore laden voor deze naam (offline of niet in de API).</div></html>");
                        root.add(err, BorderLayout.CENTER);
                        JButton close = new JButton("Sluiten");
                        close.addActionListener(e -> dlg.dispose());
                        JPanel south = new JPanel(new FlowLayout(FlowLayout.RIGHT));
                        south.setOpaque(false);
                        south.add(close);
                        root.add(south, BorderLayout.SOUTH);
                    } else {
                        JLabel title = new JLabel(name, SwingConstants.CENTER);
                        title.setFont(title.getFont().deriveFont(Font.BOLD, 13f));
                        title.setForeground(TITLE);
                        title.setBorder(BorderFactory.createEmptyBorder(0, 0, 6, 0));
                        root.add(title, BorderLayout.NORTH);

                        JPanel center = new JPanel(new BorderLayout());
                        center.setOpaque(false);
                        center.add(buildGridPanel(stats), BorderLayout.CENTER);
                        center.add(bottomBar(stats), BorderLayout.SOUTH);
                        root.add(center, BorderLayout.CENTER);

                        JButton close = new JButton("Sluiten");
                        close.addActionListener(e -> dlg.dispose());
                        JPanel south = new JPanel(new FlowLayout(FlowLayout.RIGHT));
                        south.setOpaque(false);
                        south.add(close);
                        root.add(south, BorderLayout.SOUTH);
                    }

                    dlg.setContentPane(root);
                    dlg.pack();
                    dlg.setMinimumSize(new Dimension(280, 200));
                    dlg.setLocationRelativeTo(parent);
                    dlg.setVisible(true);
                } catch (Exception ex) {
                    javax.swing.JOptionPane.showMessageDialog(parent,
                            "Fout: " + ex.getMessage(),
                            "Stats",
                            javax.swing.JOptionPane.ERROR_MESSAGE);
                }
            }
        };
        worker.execute();
    }
}
