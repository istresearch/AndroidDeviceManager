package com.jpage4500.devicemanager.data;

import com.jpage4500.devicemanager.utils.ExcludeFromSerialization;
import se.vidstige.jadb.JadbDevice;

import java.util.HashMap;
import java.util.Map;

public class Device {
    // common device properties
    public final static String PROP_SDK = "ro.build.version.sdk";
    public final static String PROP_MODEL = "ro.product.model";
    public final static String PROP_OS = "ro.build.version.release";
    public final static String PROP_CARRIER = "gsm.sim.operator.alpha";
    public final static String PROP_BRAND = "ro.product.brand";
    public final static String PROP_NAME = "ro.product.name";

    public final static String CUSTOM_PROP_X = "custom";
    public final static String CUST_PROP_1 = "custom1";
    public final static String CUST_PROP_2 = "custom2";

    public String serial;
    public String phone;
    public String imei;
    public Long freeSpace;

    // map of property name -> key
    @ExcludeFromSerialization
    public Map<String, String> propMap;

    // custom properties (saved on a file on device)
    @ExcludeFromSerialization
    public Map<String, String> customPropertyMap;

    // user-defined map of applications and versions
    @ExcludeFromSerialization
    public Map<String, String> customAppVersionList;

    // to show device status (viewing, copying, installing, etc)
    @ExcludeFromSerialization
    public String status;

    public boolean isOnline;

    // only fetch device detail (IMEI, phone, etc) once -- shouldn't change
    public boolean hasFetchedDetails;

    @ExcludeFromSerialization
    public JadbDevice jadbDevice;

    /**
     * @return best available device name
     */
    public String getDisplayName() {
        StringBuilder sb = new StringBuilder();
        String model = getProperty(PROP_MODEL);
        if (model != null) sb.append(model);
        if (phone != null) {
            if (sb.length() > 0) sb.append(" - ");
            sb.append(phone);
        } else if (serial != null) {
            if (sb.length() > 0) sb.append(" - ");
            sb.append(serial);
        }
        return sb.toString();
    }

    /**
     * if device is connected via adb wireless
     */
    public boolean isWireless() {
        return serial.indexOf(':') > 0;
    }

    public String getProperty(String key) {
        if (propMap == null) return null;
        else return propMap.get(key);
    }

    public String getCustomProperty(String key) {
        if (customPropertyMap == null) return null;
        else return customPropertyMap.get(key);
    }

    public void setCustomProperty(String key, String value) {
        if (customPropertyMap == null) customPropertyMap = new HashMap<>();
        customPropertyMap.put(key, value);
    }
}
