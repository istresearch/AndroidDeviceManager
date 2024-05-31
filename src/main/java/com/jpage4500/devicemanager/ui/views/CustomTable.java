package com.jpage4500.devicemanager.ui.views;

import com.jpage4500.devicemanager.utils.GsonHelper;
import com.jpage4500.devicemanager.utils.UiUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.*;
import javax.swing.table.*;
import java.awt.*;
import java.awt.dnd.DropTarget;
import java.awt.dnd.DropTargetDragEvent;
import java.awt.dnd.DropTargetEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.Iterator;
import java.util.List;
import java.util.prefs.Preferences;

/**
 *
 */
public class CustomTable extends JTable {
    private static final Logger log = LoggerFactory.getLogger(CustomTable.class);

    private static final Color COLOR_HEADER = new Color(197, 197, 197);
    private static final Color COLOR_ALTERNATE_ROW = new Color(246, 246, 246);

    private String prefKey;
    private ClickListener listener;
    private TooltipListener tooltipListener;
    private ClickListener clickListener;
    private PopupMenuListener popupMenuListener;
    private JScrollPane scrollPane;

    private int selectedColumn = -1;

    public interface ClickListener {
        /**
         * @param row    converted to model row
         * @param column converted to model col
         */
        void handleDoubleClick(int row, int column, MouseEvent e);
    }

    public interface PopupMenuListener {
        /**
         * show popup menu on right-click
         *
         * @param row    table row (-1 for header)
         * @param column table column
         * @return popup menu to display or null for no action
         */
        JPopupMenu getPopupMenu(int row, int column);
    }

    public interface TooltipListener {
        /**
         * return tooltip text to display
         *
         * @param row table row, converted to model data (-1 for header)
         * @param col table column, converted to model data
         * @see #getTextIfTruncated to show a tooltip only if value doesn't fit
         */
        String getToolTipText(int row, int col);
    }

    @Override
    public int getSelectedColumn() {
        return selectedColumn;
    }

    public CustomTable(String prefKey) {
        this.prefKey = prefKey;

        createScrollPane();

        setTableHeader(new CustomTableHeader(this));

        addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                // single click
                Point point = e.getPoint();
                int row = rowAtPoint(point);
                int column = columnAtPoint(point);
                if (SwingUtilities.isRightMouseButton(e)) {
                    // right-click
                    if (getSelectedRowCount() <= 1) {
                        changeSelection(row, column, false, false);
                    }
                    selectedColumn = column;
                    if (popupMenuListener != null) {
                        // convert table row/col to model row/col
                        row = convertRowIndexToModel(row);
                        column = convertColumnIndexToModel(column);
                        JPopupMenu popupMenu = popupMenuListener.getPopupMenu(row, column);
                        if (popupMenu != null) {
                            popupMenu.show(e.getComponent(), e.getX(), e.getY());
                        }
                    }
                } else if (e.getClickCount() == 2) {
                    // double-click
                    // convert table row/col to model row/col
                    row = convertRowIndexToModel(row);
                    column = convertColumnIndexToModel(column);
                    if (listener != null) listener.handleDoubleClick(row, column, e);
                }
            }
        });
    }

    public void setClickListener(ClickListener listener) {
        this.listener = listener;
    }

    public void setTooltipListener(TooltipListener tooltipListener) {
        this.tooltipListener = tooltipListener;
    }

    public void setPopupMenuListener(PopupMenuListener popupMenuListener) {
        this.popupMenuListener = popupMenuListener;
    }

    private void createScrollPane() {
        scrollPane = new JScrollPane(this);
        scrollPane.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED);
        scrollPane.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (SwingUtilities.isLeftMouseButton(e)) {
                    // single click outside of table should de-select row
                    clearSelection();
                }
            }
        });
    }

    public JScrollPane getScrollPane() {
        return scrollPane;
    }

    public void setupDragAndDrop() {
        // support drag and drop of files
        //MyDragDropListener dragDropListener = new MyDragDropListener(this, false, this::handleFilesDropped);
        getScrollPane().setDropTarget(new DropTarget() {
            @Override
            public synchronized void dragOver(DropTargetDragEvent dtde) {
                super.dragOver(dtde);
            }

            @Override
            public synchronized void dragExit(DropTargetEvent dte) {
                super.dragExit(dte);
            }
        });
    }

    public void allowSorting(boolean allowSorting) {
        if (!allowSorting) return;
        setAutoCreateRowSorter(allowSorting);
    }

    @Override
    public void scrollRectToVisible(Rectangle aRect) {
        aRect.x = getVisibleRect().x;
        super.scrollRectToVisible(aRect);
    }

    @Override
    public void setModel(TableModel dataModel) {
        super.setModel(dataModel);
        //restore();
    }

    @Override
    public Component prepareRenderer(TableCellRenderer renderer, int row, int column) {
        Component c = super.prepareRenderer(renderer, row, column);
        if (c != null && !c.getBackground().equals(getSelectionBackground())) {
            Color color = (row % 2 == 0 ? Color.WHITE : COLOR_ALTERNATE_ROW);
            c.setBackground(color);
        }
        return c;
    }

    @Override
    public String getToolTipText(MouseEvent e) {
        if (tooltipListener == null) return null;
        Point p = e.getPoint();
        int col = columnAtPoint(p);
        int row = rowAtPoint(p);
        return tooltipListener.getToolTipText(row, col);
    }

    /**
     * get text for value at row/col *ONLY* if it doesn't fit
     */
    public String getTextIfTruncated(int row, int col) {
        if (row == -1) {
            // header
            JTableHeader header = getTableHeader();
            TableColumn column = header.getColumnModel().getColumn(col);
            Object value = column.getHeaderValue();
            int width = column.getWidth();
            Component c = header.getDefaultRenderer().getTableCellRendererComponent(this, value, false, false, row, col);
            if (c != null && c.getPreferredSize().width > width) {
                if (c instanceof JLabel label) {
                    return label.getText();
                }
            }
        } else {
            Rectangle bounds = getCellRect(row, col, false);
            Component c = prepareRenderer(getCellRenderer(row, col), row, col);
            if (c != null && c.getPreferredSize().width > bounds.width) {
                if (c instanceof JLabel label) {
                    return label.getText();
                } else if (c instanceof JTextField textField) {
                    return textField.getText();
                } else {
                    Object value = getValueAt(row, col);
                    return value.toString();
                }
            }
        }
        return null;
    }

    public void scrollToBottom() {
        scrollRectToVisible(getCellRect(getRowCount() - 1, 0, true));
    }

    public void scrollToTop() {
        scrollRectToVisible(getCellRect(0, 0, true));
    }

    public void pageUp() {
        scrollPage(true);
    }

    public void pageDown() {
        scrollPage(false);
    }

    private void scrollPage(boolean isUp) {
        Rectangle visibleRect = getVisibleRect();
        int firstRow = rowAtPoint(visibleRect.getLocation());
        visibleRect.translate(0, visibleRect.height);
        int lastRow = rowAtPoint(visibleRect.getLocation());
        int numRows = lastRow - firstRow;
        int scrollToRow;
        if (isUp) {
            scrollToRow = Math.max(firstRow - numRows, 0);
        } else {
            scrollToRow = Math.min(lastRow + numRows, getRowCount() - 1);
        }
        scrollRectToVisible(getCellRect(scrollToRow, 0, true));
    }

    public static class ColumnDetails {
        Object header;
        int width;
        int userPos;
        int modelPos;
    }

    public void restore() {
        if (prefKey == null) return;
        Preferences prefs = Preferences.userRoot();
        String detailsStr = prefs.get(prefKey + "-details", null);
        if (detailsStr == null) return;
        List<ColumnDetails> detailsList = GsonHelper.stringToList(detailsStr, ColumnDetails.class);
        //log.debug("restore: {}", GsonHelper.toJson(detailsList));

        TableColumnModel columnModel = getColumnModel();
        if (detailsList.size() != columnModel.getColumnCount()) {
            log.debug("restore: wrong number of columns! {} vs {}", detailsList.size(), columnModel.getColumnCount());
            return;
        }

        for (int i = 0; i < detailsList.size(); i++) {
            ColumnDetails details = detailsList.get(i);
            //log.trace("restore: col:{}, w:{}", i, details.width);
            columnModel.getColumn(i).setPreferredWidth(details.width);
        }

        for (ColumnDetails details : detailsList) {
            if (details.modelPos != details.userPos) {
                //log.trace("restore: move:{} to:{}", details.modelPos, details.userPos);
                columnModel.moveColumn(details.modelPos, details.userPos);
            }
        }
    }

    public void persist() {
        if (prefKey == null) return;

        Enumeration<TableColumn> columns = getColumnModel().getColumns();
        Iterator<TableColumn> iter = columns.asIterator();
        List<ColumnDetails> detailList = new ArrayList<>();
        for (int i = 0; iter.hasNext(); i++) {
            TableColumn column = iter.next();
            ColumnDetails details = new ColumnDetails();
            details.header = column.getHeaderValue();
            details.userPos = i;
            details.modelPos = column.getModelIndex();
            details.width = column.getWidth();
            detailList.add(details);
        }

        Preferences prefs = Preferences.userRoot();
        prefs.put(prefKey + "-details", GsonHelper.toJson(detailList));
        //log.trace("persist: {}: {}", prefKey, GsonHelper.toJson(detailList));
    }

    /**
     * default table header PLUS:
     * - more visible sort icons
     * - tooltips when header text is truncated
     */
    private class CustomTableHeader extends JTableHeader {
        private final Icon arrowUpIcon;
        private final Icon arrowDownIcon;

        public CustomTableHeader(JTable t) {
            super(t.getColumnModel());

            arrowUpIcon = UiUtils.getImageIcon("arrow_down.png", 15);
            arrowDownIcon = UiUtils.getImageIcon("arrow_up.png", 15);

            setBackground(COLOR_HEADER);

            addMouseListener(new MouseAdapter() {
                @Override
                public void mouseClicked(MouseEvent e) {
                    if (SwingUtilities.isRightMouseButton(e)) {
                        if (popupMenuListener != null) {
                            Point point = e.getPoint();
                            int row = rowAtPoint(point);
                            int column = columnAtPoint(point);
                            // convert table row/col to model row/col
                            column = convertColumnIndexToModel(column);
                            JPopupMenu popupMenu = popupMenuListener.getPopupMenu(-1, column);
                            if (popupMenu != null) popupMenu.show(e.getComponent(), e.getX(), e.getY());
                        }
                    }
                }
            });

            // get original renderer and just modify label icons (up/down arrows)
            final TableCellRenderer defaultRenderer = t.getTableHeader().getDefaultRenderer();
            setDefaultRenderer((table, value, isSelected, hasFocus, row, column) -> {
                Component comp = defaultRenderer.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
                if (comp instanceof JLabel label) {
                    label.setIcon(getSortIcon(column));
                }
                return comp;
            });
        }

        private Icon getSortIcon(int column) {
            Icon sortIcon = null;
            if (getRowSorter() != null) {
                List<? extends RowSorter.SortKey> sortKeys = getRowSorter().getSortKeys();
                if (!sortKeys.isEmpty()) {
                    RowSorter.SortKey key = sortKeys.get(0);
                    if (key.getColumn() == convertColumnIndexToModel(column)) {
                        sortIcon = key.getSortOrder() == SortOrder.ASCENDING ? arrowDownIcon : arrowUpIcon;
                    }
                }
            }
            return sortIcon;
        }

        @Override
        public String getToolTipText(MouseEvent e) {
            if (tooltipListener == null) return null;
            Point p = e.getPoint();
            int col = columnAtPoint(p);
            return tooltipListener.getToolTipText(-1, col);
        }
    }

}
