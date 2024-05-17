package com.jpage4500.devicemanager.table;

import com.jpage4500.devicemanager.data.Device;
import com.jpage4500.devicemanager.utils.FileUtils;
import com.jpage4500.devicemanager.utils.TextUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.table.AbstractTableModel;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class DeviceTableModel extends AbstractTableModel {
    private static final Logger log = LoggerFactory.getLogger(DeviceTableModel.class);

    private final List<Device> deviceList;
    private final List<String> appList;
    private Columns[] visibleColumns;

    public enum Columns {
        SERIAL,
        MODEL,
        PHONE,
        IMEI,
        FREE,
        CUSTOM1,
        CUSTOM2,
        STATUS,
    }

    public DeviceTableModel() {
        deviceList = new ArrayList<>();
        appList = new ArrayList<>();
        setHiddenColumns(null);
    }

    public void setDeviceList(List<Device> deviceList) {
        this.deviceList.clear();
        this.deviceList.addAll(deviceList);
        fireTableDataChanged();
    }

    public void setHiddenColumns(List<String> hiddenColumns) {
        Columns[] columns = Columns.values();
        int numColumns = columns.length;
        int numHiddenColumns = hiddenColumns == null ? 0 : hiddenColumns.size();
        if (numHiddenColumns > numColumns) numHiddenColumns = 0;
        int numVisible = numColumns - numHiddenColumns;
        visibleColumns = new Columns[numVisible];

        int index = 0;
        for (Columns column : columns) {
            if (hiddenColumns == null || !hiddenColumns.contains(column.name())) {
                visibleColumns[index] = column;
                index++;
            }
        }
        fireTableStructureChanged();
    }

    /**
     * get device for given row
     * NOTE: make sure you use table.convertRowIndexToModel() first
     */
    public Device getDeviceAtRow(int row) {
        if (deviceList.size() > row) {
            return deviceList.get(row);
        }
        return null;
    }

    public void updateRowForDevice(Device device) {
        if (device == null) return;
        for (int row = 0; row < deviceList.size(); row++) {
            Device d = deviceList.get(row);
            if (TextUtils.equals(d.serial, device.serial)) {
                fireTableRowsUpdated(row, row);
                break;
            }
        }
    }

    public void setAppList(List<String> appList) {
        if (this.appList.equals(appList)) return;
        this.appList.clear();
        this.appList.addAll(appList);
        for (Device device : deviceList) {
            device.hasFetchedDetails = false;
        }

        fireTableDataChanged();
    }

    public int getColumnCount() {
        return visibleColumns.length + appList.size();
    }

    @Override
    public Class<?> getColumnClass(int columnIndex) {
        return Device.class;
    }

    public String getColumnName(int i) {
        if (i < visibleColumns.length) {
            Columns colType = visibleColumns[i];
            return colType.name();
        } else {
            String appName = appList.get(i - visibleColumns.length);
            String[] split = appName.split("\\.");
            if (split.length >= 1) {
                return split[split.length - 1].toUpperCase(Locale.ROOT);
            } else {
                return "?";
            }
        }
    }

    public int getRowCount() {
        return deviceList.size();
    }

    public Object getValueAt(int row, int col) {
        if (row >= deviceList.size()) return null;
        else if (col >= getColumnCount()) return null;

        Device device = deviceList.get(row);
        if (col < visibleColumns.length) {
            return deviceValue(device, visibleColumns[col]);
        } else {
            // custom app version
            if (device.customAppVersionList != null) {
                String appName = appList.get(col - visibleColumns.length);
                return device.customAppVersionList.get(appName);
            } else {
                return null;
            }
        }
    }

    public static String deviceValue(Device device, Columns colType) {
        return switch (colType) {
            case SERIAL -> device.serial;
            case MODEL -> device.getProperty(Device.PROP_MODEL);
            case PHONE -> device.phone;
            case IMEI -> device.imei;
            case FREE -> FileUtils.bytesToDisplayString(device.freeSpace);
            case CUSTOM1 -> device.getCustomProperty(Device.CUST_PROP_1);
            case CUSTOM2 -> device.getCustomProperty(Device.CUST_PROP_2);
            case STATUS -> device.status;
        };
    }

}