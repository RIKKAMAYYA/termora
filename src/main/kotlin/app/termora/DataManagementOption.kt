package app.termora

import app.termora.OptionsPane.PluginOption
import com.formdev.flatlaf.extras.components.FlatPasswordField
import org.slf4j.LoggerFactory
import java.awt.BorderLayout
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import java.io.File
import javax.swing.*
import javax.swing.filechooser.FileNameExtensionFilter

/**
 * Settings panel for exporting/importing all Termora data.
 */
class DataManagementOption : JPanel(BorderLayout()), PluginOption {

    companion object {
        private val log = LoggerFactory.getLogger(DataManagementOption::class.java)
    }

    private val owner get() = SwingUtilities.getWindowAncestor(this)

    init {
        add(createPanel(), BorderLayout.CENTER)
    }

    private fun createPanel(): JPanel {
        val exportBtn = JButton(I18n.getString("termora.settings.data-management.export"))
        exportBtn.addActionListener { doExport() }

        val importBtn = JButton(I18n.getString("termora.settings.data-management.import"))
        importBtn.addActionListener { doImport() }

        // Export section
        val exportInfo = JLabel("<html>${I18n.getString("termora.settings.data-management.export-description")}</html>")
        val exportWarning = JLabel("<html><i><font color='gray'>${I18n.getString("termora.settings.data-management.export-warning")}</font></i></html>")
        val exportBtnPanel = JPanel(BorderLayout())
        exportBtnPanel.add(exportBtn, BorderLayout.WEST)

        val exportPanel = JPanel(BorderLayout(0, 8))
        exportPanel.border = BorderFactory.createTitledBorder(I18n.getString("termora.settings.data-management.export"))
        exportPanel.add(exportInfo, BorderLayout.NORTH)
        exportPanel.add(exportWarning, BorderLayout.CENTER)
        exportPanel.add(exportBtnPanel, BorderLayout.SOUTH)

        // Import section
        val importInfo = JLabel("<html>${I18n.getString("termora.settings.data-management.import-description")}</html>")
        val importWarning = JLabel("<html><i><font color='gray'>${I18n.getString("termora.settings.data-management.import-warning")}</font></i></html>")
        val importBtnPanel = JPanel(BorderLayout())
        importBtnPanel.add(importBtn, BorderLayout.WEST)

        val importPanel = JPanel(BorderLayout(0, 8))
        importPanel.border = BorderFactory.createTitledBorder(I18n.getString("termora.settings.data-management.import"))
        importPanel.add(importInfo, BorderLayout.NORTH)
        importPanel.add(importWarning, BorderLayout.CENTER)
        importPanel.add(importBtnPanel, BorderLayout.SOUTH)

        val panel = JPanel(GridBagLayout())
        val gbc = GridBagConstraints()
        gbc.fill = GridBagConstraints.HORIZONTAL
        gbc.gridx = 0
        gbc.weightx = 1.0
        gbc.insets = Insets(4, 4, 4, 4)

        gbc.gridy = 0
        panel.add(exportPanel, gbc)
        gbc.gridy = 1
        panel.add(importPanel, gbc)

        // Spacer
        gbc.gridy = 2
        gbc.weighty = 1.0
        gbc.fill = GridBagConstraints.BOTH
        panel.add(JPanel(), gbc)

        return panel
    }

    private fun doExport() {
        // Prompt for password
        val passwordPanel = JPanel(BorderLayout(0, 4))
        val passwordField = FlatPasswordField()
        val confirmField = FlatPasswordField()

        passwordPanel.add(JLabel(I18n.getString("termora.settings.data-management.export-password-prompt")), BorderLayout.NORTH)
        passwordPanel.add(passwordField, BorderLayout.CENTER)
        passwordPanel.add(JLabel(I18n.getString("termora.settings.data-management.export-confirm-password")), BorderLayout.SOUTH)

        // Show password prompt with both fields
        val p = JPanel(BorderLayout(0, 8))
        p.add(JLabel(I18n.getString("termora.settings.data-management.export-password-prompt")), BorderLayout.NORTH)
        val fields = JPanel(BorderLayout(0, 4))
        fields.add(passwordField, BorderLayout.NORTH)
        fields.add(confirmField, BorderLayout.SOUTH)
        p.add(fields, BorderLayout.CENTER)

        val result = JOptionPane.showConfirmDialog(
            owner, p,
            I18n.getString("termora.settings.data-management.export"),
            JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE
        )
        if (result != JOptionPane.OK_OPTION) return

        val password = passwordField.password
        val confirm = confirmField.password

        if (password.isEmpty() || confirm.isEmpty()) {
            OptionPane.showMessageDialog(owner, I18n.getString("termora.settings.data-management.export-password-mismatch"))
            return
        }
        if (!password.contentEquals(confirm)) {
            OptionPane.showMessageDialog(owner, I18n.getString("termora.settings.data-management.export-password-mismatch"))
            return
        }

        // Save file dialog
        val fileChooser = JFileChooser()
        fileChooser.dialogTitle = I18n.getString("termora.settings.data-management.export")
        fileChooser.fileFilter = FileNameExtensionFilter("Termora Backup (*.termora-backup)", "termora-backup")
        fileChooser.selectedFile = File(I18n.getString("termora.settings.data-management.default-filename") + ".termora-backup")

        if (fileChooser.showSaveDialog(owner) != JFileChooser.APPROVE_OPTION) return

        val file = fileChooser.selectedFile

        // Do export in background
        SwingUtilities.invokeLater {
            try {
                val data = DataBackupManager.exportAllData(password)
                file.writeBytes(data)
                val openFolder = OptionPane.showConfirmDialog(
                    owner,
                    I18n.getString("termora.settings.data-management.export-success-open-folder"),
                    messageType = JOptionPane.QUESTION_MESSAGE,
                    optionType = JOptionPane.YES_NO_OPTION
                )
                if (openFolder == JOptionPane.YES_OPTION) {
                    OptionPane.openFileInFolder(owner, file, "", "")
                }
            } catch (e: Exception) {
                log.error("Export failed", e)
                OptionPane.showMessageDialog(owner, e.message ?: "Export failed", messageType = JOptionPane.ERROR_MESSAGE)
            }
        }
    }

    private fun doImport() {
        // File open dialog
        val fileChooser = JFileChooser()
        fileChooser.dialogTitle = I18n.getString("termora.settings.data-management.import-choose-file")
        fileChooser.fileFilter = FileNameExtensionFilter("Termora Backup (*.termora-backup)", "termora-backup")

        if (fileChooser.showOpenDialog(owner) != JFileChooser.APPROVE_OPTION) return
        val file = fileChooser.selectedFile

        // Password prompt
        val passwordField = FlatPasswordField()
        val panel = JPanel(BorderLayout(0, 4))
        panel.add(JLabel(I18n.getString("termora.settings.data-management.import-password-prompt")), BorderLayout.NORTH)
        panel.add(passwordField, BorderLayout.CENTER)

        val result = JOptionPane.showConfirmDialog(
            owner, panel,
            I18n.getString("termora.settings.data-management.import"),
            JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE
        )
        if (result != JOptionPane.OK_OPTION) return

        // Do import in background
        SwingUtilities.invokeLater {
            try {
                val encryptedBytes = file.readBytes()
                val importResult = DataBackupManager.importAllData(passwordField.password, encryptedBytes)

                if (importResult.errors.isNotEmpty()) {
                    OptionPane.showMessageDialog(
                        owner,
                        importResult.errors.joinToString("\n"),
                        I18n.getString("termora.welcome.contextmenu.import.error"),
                        JOptionPane.ERROR_MESSAGE
                    )
                    return@invokeLater
                }

                val message = I18n.getString(
                    "termora.settings.data-management.import-success",
                    importResult.totalCreated,
                    importResult.totalUpdated,
                    importResult.totalSkipped
                )

                // Build detailed stats
                val details = StringBuilder(message)
                for ((typeName, stats) in importResult.statsByType) {
                    if (stats.created > 0 || stats.updated > 0 || stats.skipped > 0) {
                        details.appendLine()
                        details.append("  $typeName: +${stats.created} ~${stats.updated} -${stats.skipped}")
                    }
                }

                OptionPane.showMessageDialog(
                    owner,
                    details.toString(),
                    I18n.getString("termora.settings.data-management.import"),
                    JOptionPane.INFORMATION_MESSAGE
                )
            } catch (e: Exception) {
                log.error("Import failed", e)
                OptionPane.showMessageDialog(owner, e.message ?: "Import failed", messageType = JOptionPane.ERROR_MESSAGE)
            }
        }
    }

    override fun getIcon(isSelected: Boolean): Icon {
        return Icons.import
    }

    override fun getTitle(): String {
        return I18n.getString("termora.settings.data-management")
    }

    override fun getJComponent(): JComponent {
        return this
    }

    override fun getAnchor(): OptionsPane.Anchor {
        return OptionsPane.Anchor.After("SFTP")
    }
}
