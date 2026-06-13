package app.termora.tunnel

import app.termora.DialogWrapper
import app.termora.Disposer
import app.termora.DynamicColor
import app.termora.I18n
import app.termora.Icons
import app.termora.TunnelingType
import com.formdev.flatlaf.FlatClientProperties
import com.formdev.flatlaf.extras.components.FlatToolBar
import org.apache.commons.lang3.StringUtils
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.Window
import javax.swing.AbstractAction
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JTable
import javax.swing.JTextField
import javax.swing.ListSelectionModel
import javax.swing.RowFilter
import javax.swing.SwingUtilities
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener
import javax.swing.table.AbstractTableModel
import javax.swing.table.TableRowSorter

class TunnelDialog(owner: Window) : DialogWrapper(owner) {
    private val manager = TunnelManager.getInstance()
    private val model = TunnelTableModel()
    private val sorter = TableRowSorter(model)
    private val table = JTable(model)
    private val searchTextField = JTextField()
    private val startButton = JButton(I18n.getString("termora.tunnel.start"), Icons.run)
    private val stopButton = JButton(I18n.getString("termora.tunnel.stop"), Icons.stop)
    private val refreshButton = JButton(Icons.refresh)
    private val autoReconnectCheckBox = JCheckBox(I18n.getString("termora.tunnel.auto-reconnect"))
    private val summaryLabel = JLabel()
    private val listener = manager.addListener { tunnels ->
        SwingUtilities.invokeLater {
            model.setTunnels(tunnels)
            updateActions()
        }
    }

    init {
        title = I18n.getString("termora.tunnel.manager")
        size = Dimension(980, 620)
        minimumSize = Dimension(760, 420)
        escapeDispose = true

        manager.setOwner(owner)
        init()
        setLocationRelativeTo(owner)
        reload()
        Disposer.register(disposable, listener)
    }

    override fun createCenterPanel(): JComponent {
        val panel = JPanel(BorderLayout())
        panel.add(createToolbar(), BorderLayout.NORTH)

        table.rowSorter = sorter
        table.selectionModel.selectionMode = ListSelectionModel.MULTIPLE_INTERVAL_SELECTION
        table.autoResizeMode = JTable.AUTO_RESIZE_SUBSEQUENT_COLUMNS
        table.fillsViewportHeight = true
        table.putClientProperty(
            FlatClientProperties.STYLE, mapOf(
                "showHorizontalLines" to true,
                "showVerticalLines" to true,
            )
        )
        table.columnModel.getColumn(TunnelTableModel.COL_AUTO_RECONNECT).maxWidth = 120
        table.columnModel.getColumn(TunnelTableModel.COL_STATE).preferredWidth = 120
        table.columnModel.getColumn(TunnelTableModel.COL_HOST).preferredWidth = 160
        table.columnModel.getColumn(TunnelTableModel.COL_TUNNEL).preferredWidth = 160
        table.columnModel.getColumn(TunnelTableModel.COL_TYPE).preferredWidth = 90
        table.columnModel.getColumn(TunnelTableModel.COL_SOURCE).preferredWidth = 150
        table.columnModel.getColumn(TunnelTableModel.COL_DESTINATION).preferredWidth = 180
        table.columnModel.getColumn(TunnelTableModel.COL_MESSAGE).preferredWidth = 220

        table.selectionModel.addListSelectionListener { updateActions() }

        val scrollPane = JScrollPane(table)
        scrollPane.border = BorderFactory.createMatteBorder(1, 0, 0, 0, DynamicColor.BorderColor)
        panel.add(scrollPane, BorderLayout.CENTER)
        return panel
    }

    override fun createSouthPanel(): JComponent? {
        val panel = JPanel(BorderLayout())
        panel.border = BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(1, 0, 0, 0, DynamicColor.BorderColor),
            BorderFactory.createEmptyBorder(8, 12, 8, 12)
        )
        panel.add(summaryLabel, BorderLayout.WEST)

        val box = Box.createHorizontalBox()
        box.add(autoReconnectCheckBox)
        box.add(Box.createHorizontalStrut(8))
        box.add(startButton)
        box.add(Box.createHorizontalStrut(8))
        box.add(stopButton)
        panel.add(box, BorderLayout.EAST)
        return panel
    }

    override fun createActions(): List<AbstractAction> {
        return emptyList()
    }

    private fun createToolbar(): JComponent {
        val toolbar = FlatToolBar()
        toolbar.isFloatable = false
        toolbar.border = BorderFactory.createEmptyBorder(8, 8, 8, 8)

        searchTextField.putClientProperty(
            FlatClientProperties.PLACEHOLDER_TEXT,
            I18n.getString("termora.tunnel.search-placeholder")
        )
        searchTextField.preferredSize = Dimension(260, searchTextField.preferredSize.height)
        searchTextField.maximumSize = Dimension(320, searchTextField.preferredSize.height)

        refreshButton.toolTipText = I18n.getString("termora.tunnel.refresh")
        refreshButton.addActionListener { reload() }

        toolbar.add(JLabel(I18n.getString("termora.search") + ":"))
        toolbar.add(Box.createHorizontalStrut(8))
        toolbar.add(searchTextField)
        toolbar.add(Box.createHorizontalStrut(8))
        toolbar.add(refreshButton)
        toolbar.add(Box.createHorizontalGlue())

        startButton.addActionListener { startSelected() }
        stopButton.addActionListener { stopSelected() }
        autoReconnectCheckBox.addActionListener { setAutoReconnectSelected(autoReconnectCheckBox.isSelected) }

        searchTextField.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent) = applyFilter()
            override fun removeUpdate(e: DocumentEvent) = applyFilter()
            override fun changedUpdate(e: DocumentEvent) = applyFilter()
        })

        return toolbar
    }

    private fun reload() {
        manager.refreshHosts()
        model.setTunnels(manager.getTunnels())
        updateActions()
    }

    private fun applyFilter() {
        val text = searchTextField.text
        sorter.rowFilter = if (text.isBlank()) {
            null
        } else {
            object : RowFilter<TunnelTableModel, Int>() {
                override fun include(entry: Entry<out TunnelTableModel, out Int>): Boolean {
                    return model.getTunnel(entry.identifier)?.matches(text) == true
                }
            }
        }
        updateActions()
    }

    private fun selectedTunnelIds(): List<TunnelId> {
        return table.selectedRows
            .map { table.convertRowIndexToModel(it) }
            .mapNotNull { model.getTunnel(it)?.id }
    }

    private fun selectedTunnels(): List<TunnelView> {
        return table.selectedRows
            .map { table.convertRowIndexToModel(it) }
            .mapNotNull { model.getTunnel(it) }
    }

    private fun startSelected() {
        val ids = selectedTunnelIds()
        if (ids.isEmpty()) return
        manager.start(ids)
    }

    private fun stopSelected() {
        val ids = selectedTunnelIds()
        if (ids.isEmpty()) return
        manager.stop(ids)
    }

    private fun setAutoReconnectSelected(autoReconnect: Boolean) {
        val ids = selectedTunnelIds()
        if (ids.isEmpty()) return
        for (id in ids) {
            manager.setAutoReconnect(id, autoReconnect)
        }
    }

    private fun updateActions() {
        val selected = selectedTunnels()
        startButton.isEnabled = selected.isNotEmpty() && selected.any {
            it.state == TunnelState.Stopped || it.state == TunnelState.Failed
        }
        stopButton.isEnabled = selected.isNotEmpty() && selected.any {
            it.state == TunnelState.Connecting ||
                    it.state == TunnelState.Connected ||
                    it.state == TunnelState.Reconnecting ||
                    it.state == TunnelState.Failed
        }
        autoReconnectCheckBox.isEnabled = selected.isNotEmpty()
        autoReconnectCheckBox.isSelected = selected.isNotEmpty() && selected.all { it.autoReconnect }

        val visible = table.rowCount
        val total = model.rowCount
        val running = model.tunnels.count { it.state == TunnelState.Connected }
        val failed = model.tunnels.count { it.state == TunnelState.Failed }
        summaryLabel.text = I18n.getString("termora.tunnel.summary", visible, total, running, failed)
    }

    private class TunnelTableModel : AbstractTableModel() {
        companion object {
            const val COL_STATE = 0
            const val COL_AUTO_RECONNECT = 1
            const val COL_HOST = 2
            const val COL_TUNNEL = 3
            const val COL_TYPE = 4
            const val COL_SOURCE = 5
            const val COL_DESTINATION = 6
            const val COL_MESSAGE = 7
        }

        var tunnels: List<TunnelView> = emptyList()
            private set

        private val columns = listOf(
            I18n.getString("termora.tunnel.state"),
            I18n.getString("termora.tunnel.auto-reconnect.short"),
            I18n.getString("termora.tunnel.host"),
            I18n.getString("termora.tunnel.name"),
            I18n.getString("termora.new-host.tunneling.table.type"),
            I18n.getString("termora.new-host.tunneling.table.source"),
            I18n.getString("termora.new-host.tunneling.table.destination"),
            I18n.getString("termora.tunnel.message"),
        )

        fun setTunnels(tunnels: List<TunnelView>) {
            this.tunnels = tunnels.sortedWith(
                compareBy<TunnelView> { it.host.name.lowercase() }
                    .thenBy { it.tunneling.name.lowercase() }
                    .thenBy { it.sourceText }
            )
            fireTableDataChanged()
        }

        fun getTunnel(row: Int): TunnelView? {
            return tunnels.getOrNull(row)
        }

        override fun getRowCount(): Int {
            return tunnels.size
        }

        override fun getColumnCount(): Int {
        return columns.size
    }

        override fun isCellEditable(rowIndex: Int, columnIndex: Int): Boolean {
            return false
        }

        override fun getColumnName(column: Int): String {
            return columns[column]
        }

        override fun getColumnClass(columnIndex: Int): Class<*> {
            return if (columnIndex == COL_AUTO_RECONNECT) Boolean::class.java else String::class.java
        }

        override fun getValueAt(rowIndex: Int, columnIndex: Int): Any {
            val tunnel = tunnels[rowIndex]
            return when (columnIndex) {
                COL_STATE -> stateText(tunnel.state)
                COL_AUTO_RECONNECT -> tunnel.autoReconnect
                COL_HOST -> "${tunnel.host.name} (${tunnel.host.username}@${tunnel.host.host})"
                COL_TUNNEL -> StringUtils.defaultIfBlank(tunnel.tunneling.name, "-")
                COL_TYPE -> typeText(tunnel.tunneling.type)
                COL_SOURCE -> tunnel.sourceText
                COL_DESTINATION -> tunnel.destinationText
                COL_MESSAGE -> tunnel.error.ifBlank { tunnel.boundAddress }
                else -> StringUtils.EMPTY
            }
        }

        private fun stateText(state: TunnelState): String {
            return when (state) {
                TunnelState.Stopped -> I18n.getString("termora.tunnel.state.stopped")
                TunnelState.Connecting -> I18n.getString("termora.tunnel.state.connecting")
                TunnelState.Connected -> I18n.getString("termora.tunnel.state.connected")
                TunnelState.Reconnecting -> I18n.getString("termora.tunnel.state.reconnecting")
                TunnelState.Failed -> I18n.getString("termora.tunnel.state.failed")
            }
        }

        private fun typeText(type: TunnelingType): String {
            return when (type) {
                TunnelingType.Local -> I18n.getString("termora.tunnel.type.local")
                TunnelingType.Remote -> I18n.getString("termora.tunnel.type.remote")
                TunnelingType.Dynamic -> I18n.getString("termora.tunnel.type.dynamic")
            }
        }
    }
}
