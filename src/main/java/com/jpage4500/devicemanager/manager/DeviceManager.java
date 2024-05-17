package com.jpage4500.devicemanager.manager;

import com.jpage4500.devicemanager.data.Device;
import com.jpage4500.devicemanager.data.LogEntry;
import com.jpage4500.devicemanager.ui.ConnectScreen;
import com.jpage4500.devicemanager.utils.GsonHelper;
import com.jpage4500.devicemanager.utils.TextUtils;
import com.jpage4500.devicemanager.utils.Timer;
import se.vidstige.jadb.DeviceDetectionListener;
import se.vidstige.jadb.JadbConnection;
import se.vidstige.jadb.JadbDevice;
import se.vidstige.jadb.RemoteFile;
import se.vidstige.jadb.managers.PackageManager;
import se.vidstige.jadb.managers.PropertyManager;

import java.awt.image.BufferedImage;
import java.io.*;
import java.net.InetSocketAddress;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

public class DeviceManager {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(DeviceManager.class);

    public static final String COMMAND_SERVICE_PHONE1 = "service call iphonesubinfo 15 s16 com.android.shell";
    public static final String COMMAND_SERVICE_PHONE2 = "service call iphonesubinfo 12 s16 com.android.shell";
    public static final String COMMAND_SERVICE_IMEI = "service call iphonesubinfo 1 s16 com.android.shell";
    public static final String COMMAND_REBOOT = "reboot";
    public static final String COMMAND_DISK_SIZE = "df";
    public static final String COMMAND_LIST_PROCESSES = "ps -A -o PID,ARGS";

    public static final String FILE_CUSTOM_PROP = "/sdcard/android_device_manager.properties";

    private static final String SCRIPT_TERMINAL = "terminal.sh";
    private static final String SCRIPT_CUSTOM_COMMAND = "custom-command.sh";
    private static final String SCRIPT_COPY_FILE = "copy-file.sh";
    private static final String SCRIPT_SET_PROPERTY = "set-property.sh";
    private static final String SCRIPT_SCREENSHOT = "screenshot.sh";
    private static final String SCRIPT_MIRROR = "mirror.sh";
    private static final String SCRIPT_START_LOGGING = "start-logging.sh";

    private static volatile DeviceManager instance;

    private final List<Device> deviceList;
    private final String tempFolder;
    private final List<Process> processList;

    private final ExecutorService commandExecutorService;
    private final ScheduledExecutorService scheduledExecutorService;
    private final AtomicBoolean isLogging = new AtomicBoolean(false);

    private JadbConnection connection;

    public static DeviceManager getInstance() {
        if (instance == null) {
            synchronized (DeviceManager.class) {
                if (instance == null) {
                    instance = new DeviceManager();
                }
            }
        }
        return instance;
    }

    private DeviceManager() {
        deviceList = new ArrayList<>();
        processList = new ArrayList<>();

        commandExecutorService = Executors.newFixedThreadPool(10);
        scheduledExecutorService = Executors.newScheduledThreadPool(1);

        tempFolder = System.getProperty("java.io.tmpdir");
        copyResourcesToFiles();
    }

    public interface DeviceListener {
        // device list was refreshed
        void handleDevicesUpdated(List<Device> deviceList);

        // single device was updated
        void handleDeviceUpdated(Device device);

        // device was removed
        void handleDeviceRemoved(Device device);

        void handleException(Exception e);
    }

    public void connectAdbServer(DeviceManager.DeviceListener listener) {
        log.debug("connectAdbServer: ");
        connection = new JadbConnection();
        commandExecutorService.submit(() -> {
            try {
                String hostVersion = connection.getHostVersion();
                log.debug("connectAdbServer: v:{}", hostVersion);
                connection.createDeviceWatcher(new DeviceDetectionListener() {
                    @Override
                    public void onDetect(List<JadbDevice> devices) {
                        handleDeviceUpdate(devices, listener);
                    }

                    @Override
                    public void onException(Exception e) {
                        log.error("onException: {}", e.getMessage());
                        listener.handleException(e);
                    }
                }).run();
            } catch (Exception e) {
                log.error("Exception: {}", e.getMessage());
            }
        });
    }

    /**
     * called when a device is added/updated/removed
     * NOTE: run on background thread
     */
    private void handleDeviceUpdate(List<JadbDevice> devices, DeviceListener listener) {
        //log.debug("onDetect: GOT:{}, {}", devices.size(), GsonHelper.toJson(devices));
        List<Device> addedDeviceList = new ArrayList<>();
        // 1) look for devices that don't exist today
        for (JadbDevice jadbDevice : devices) {
            String serial = jadbDevice.getSerial();
            // -- does this device already exist? --
            Device device = getDevice(serial);
            if (device == null || !device.hasFetchedDetails) {
                // -- ADD DEVICE --
                if (device == null) {
                    device = new Device();
                    log.trace("handleDeviceUpdate: DEVICE_ADDED: {}", serial);
                    synchronized (deviceList) {
                        deviceList.add(device);
                    }
                } else {
                    if (log.isTraceEnabled()) log.trace("handleDeviceUpdate: DEVICE_UPDATED: {}", device.getDisplayName());
                }
                device.serial = serial;
                device.jadbDevice = jadbDevice;
                addedDeviceList.add(device);
            }
        }

        // 2) look for devices that have been removed
        int numRemoved = 0;
        for (Iterator<Device> iterator = deviceList.iterator(); iterator.hasNext(); ) {
            Device device = iterator.next();
            boolean isFound = false;
            for (JadbDevice jadbDevice : devices) {
                if (device.serial.equals(jadbDevice.getSerial())) {
                    isFound = true;
                    break;
                }
            }
            if (!isFound) {
                // -- DEVICE REMOVED --
                device.isOnline = false;
                device.lastUpdateMs = System.currentTimeMillis();
                if (log.isTraceEnabled()) log.trace("handleDeviceUpdate: DEVICE_REMOVED: {}", device.getDisplayName());
                //iterator.remove();
                listener.handleDeviceRemoved(device);
                numRemoved++;
            }
        }

        if (numRemoved > 0 || !addedDeviceList.isEmpty()) {
            // notify listener that device list changed
            listener.handleDevicesUpdated(deviceList);

            for (Device addedDevice : addedDeviceList) {
                // fetch more details for these devices
                try {
                    JadbDevice.State state = addedDevice.jadbDevice.getState();
                    log.trace("handleDeviceUpdate: STATE: {} -> {}", addedDevice.serial, state);
                    if (state == JadbDevice.State.Device) {
                        addedDevice.status = "fetching details..";
                        listener.handleDeviceUpdated(addedDevice);
                        // only do lookup once
                        addedDevice.hasFetchedDetails = true;
                        fetchDeviceDetails(addedDevice, listener);
                    } else {
                        addedDevice.status = state.name();
                        listener.handleDeviceUpdated(addedDevice);
                    }
                } catch (Exception e) {
                    log.trace("handleDeviceUpdate: Exception:{}", e.getMessage());
                    addedDevice.status = e.getMessage();
                    listener.handleDeviceUpdated(addedDevice);
                }
            }
        }
    }

    public void refreshDevices(DeviceListener listener) {
        synchronized (deviceList) {
            for (Device device : deviceList) {
                fetchDeviceDetails(device, listener);
            }
        }
    }

    private void fetchDeviceDetails(Device device, DeviceListener listener) {
        commandExecutorService.submit(() -> {
            log.debug("fetchDeviceDetails: {}", device.serial);
            device.phone = runShellServiceCall(device, COMMAND_SERVICE_PHONE1);
            if (TextUtils.isEmpty(device.phone)) {
                device.phone = runShellServiceCall(device, COMMAND_SERVICE_PHONE2);
            }
            device.imei = runShellServiceCall(device, COMMAND_SERVICE_IMEI);

            List<String> sizeLines = runShell(device, COMMAND_DISK_SIZE);
            if (!sizeLines.isEmpty()) {
                // only interested in last line
                String last = sizeLines.get(sizeLines.size() - 1);
                // /dev/fuse         115249236 14681484 100436680  13% /storage/emulated
                //                                      ^^^^^^^^^
                String size = TextUtils.split(last, 3);
                try {
                    // size is in 1k blocks
                    device.freeSpace = Long.parseLong(size) * 1000L;
                } catch (Exception e) {
                    log.trace("fetchDeviceDetails: FREE_SPACE Exception:{}", e.getMessage());
                }
            }

            try {
                device.propMap = new PropertyManager(device.jadbDevice).getprop();
            } catch (Exception e) {
                log.error("fetchDeviceDetails: PROP Exception:{}", e.getMessage());
            }

            // fetch file app is using to persist custom properties
            OutputStream outputStream = new ByteArrayOutputStream();
            RemoteFile file = new RemoteFile(FILE_CUSTOM_PROP);
            try {
                device.jadbDevice.pull(file, outputStream);
                String customPropStr = outputStream.toString();
                String[] customPropArr = customPropStr.split("\\n+");
                for (String customProp : customPropArr) {
                    String[] propArr = customProp.split("=");
                    if (device.customPropertyMap == null) device.customPropertyMap = new HashMap<>();
                    device.customPropertyMap.put(propArr[0], propArr[1]);
                }
                //log.trace("fetchDeviceDetails: {}", customPropStr);
            } catch (Exception e) {
                //log.trace("fetchDeviceDetails: PULL Exception:{}", e.getMessage());
            }

            device.hasFetchedDetails = true;
            if (log.isTraceEnabled()) log.trace("fetchDeviceDetails: {}", GsonHelper.toJson(device));

            device.status = null;
            device.isOnline = true;
            listener.handleDeviceUpdated(device);

            // keep track of wireless devices
            ConnectScreen.addWirelessDevice(device);
        });
    }

    /**
     * run a 'shell service call ..." command and parse the results into a String
     */
    private String runShellServiceCall(Device device, String command) {
        List<String> resultList = runShell(device, command);
        // Result: Parcel(
        // 0x00000000: 00000000 0000000b 00350031 00300034 '........1.2.2.2.'
        // 0x00000010: 00310039 00390034 00310032 00000034 '3.3.3.4.4.4.4...')
        StringBuilder result = null;
        if (resultList.size() > 1) {
            for (int i = 1; i < resultList.size(); i++) {
                String line = resultList.get(i);
                int stPos = line.indexOf('\'');
                if (stPos >= 0) {
                    int endPos = line.indexOf('\'', stPos + 1);
                    if (endPos >= 0) {
                        line = line.substring(stPos + 1, endPos);
                        line = line.replaceAll("[^-?0-9]+", "");
                        if (result == null) result = new StringBuilder();
                        result.append(line);
                    }
                }
            }
        }
        //log.trace("runShellServiceCall: RESULTS: {}", result);
        return result != null ? result.toString() : null;
    }

    /**
     * run a shell command and return multi-line output
     */
    private List<String> runShell(Device device, String command) {
        List<String> resultList = new ArrayList<>();
        List<String> commandList = TextUtils.splitSafe(command, ' ');
        InputStream inputStream = null;
        try {
            String firstCommand = commandList.get(0);
            List<String> subList = commandList.subList(1, commandList.size());
            //log.trace("runShell: COMMAND:{}, ARGS:{}", firstCommand, GsonHelper.toJson(subList));
            inputStream = device.jadbDevice.executeShell(firstCommand, subList.toArray(new String[0]));
            BufferedReader input = new BufferedReader(new InputStreamReader(inputStream));
            String line;
            while ((line = input.readLine()) != null) {
                //log.trace("runShell: {}", line);
                resultList.add(line);
            }
        } catch (Exception e) {
            log.error("runShell: cmd:{}, Exception: {}", command, e.getMessage());
        } finally {
            if (inputStream != null) {
                try {
                    inputStream.close();
                } catch (IOException ignored) {
                }
            }
        }
        return resultList;
    }

    private Device getDevice(String serial) {
        synchronized (deviceList) {
            for (Device device : deviceList) {
                if (TextUtils.equals(device.serial, serial)) {
                    return device;
                }
            }
        }
        return null;
    }

    public void mirrorDevice(Device device, DeviceListener listener) {
        commandExecutorService.submit(() -> {
            device.status = "mirring...";
            listener.handleDeviceUpdated(device);
            String name = device.serial;
            if (device.phone != null) name += ": " + device.phone;
            ScriptResult result = runScript(SCRIPT_MIRROR, true, true, device.serial, name);
            if (result.isSuccess) device.status = null;
            else device.status = GsonHelper.toJson(result.stdErr);
            listener.handleDeviceUpdated(device);
        });
    }

    public void captureScreenshot(Device device, DeviceListener listener) {
        commandExecutorService.submit(() -> {
            device.status = "screenshot...";
            try {
                BufferedImage screencap = device.jadbDevice.screencap();
                // TODO: display image in frame
                log.debug("captureScreenshot: {}x{}", screencap.getWidth(), screencap.getHeight());
            } catch (Exception e) {
                log.error("captureScreenshot: {}", e.getMessage());
            }
            runScript(device, SCRIPT_SCREENSHOT, listener, false, device.serial);
        });
    }

    public void runUserScript(Device device, File file, DeviceListener listener) {
        commandExecutorService.submit(() -> {
            device.status = "runUserScript...";
            listener.handleDeviceUpdated(device);
            ScriptResult result = runScript(file, false, true, device.serial, tempFolder);
            if (result.isSuccess) device.status = null;
            else device.status = GsonHelper.toJson(result.stdErr);
            listener.handleDeviceUpdated(device);
            log.debug("runUserScript: DONE");
        });
    }

    public void setProperty(Device device, String key, String value) {
        if (value == null) value = "";
        String safeValue = value.replaceAll(" ", "~");
        commandExecutorService.submit(() -> {
            runScript(SCRIPT_SET_PROPERTY, device.serial, key, safeValue);
            log.debug("setProperty: {}, key:{}, value:{}, DONE", device.serial, key, safeValue);
        });
    }

    public void installApp(Device device, File file, DeviceListener listener) {
        commandExecutorService.submit(() -> {
            device.status = "installing...";
            listener.handleDeviceUpdated(device);
            try {
                PackageManager packageManager = new PackageManager(device.jadbDevice);
                packageManager.install(file);
                device.status = null;
                listener.handleDeviceUpdated(device);
            } catch (Exception e) {
                log.error("installApp: {}, {}", file.getAbsolutePath(), e.getMessage());
                device.status = "failed: " + e.getMessage();
                listener.handleDeviceUpdated(device);
            }
        });
    }

    public void copyFile(Device device, File file, String dest, DeviceListener listener) {
        commandExecutorService.submit(() -> {
            String path = file.getAbsolutePath();
            device.status = "copying...";
            runScript(device, SCRIPT_COPY_FILE, listener, true, path);
            log.debug("copyFile: {}, {}, DONE", device.serial, path);
        });
    }

    public void restartDevice(Device device, DeviceListener listener) {
        commandExecutorService.submit(() -> {
            runShell(device, COMMAND_REBOOT);
//            device.status = "restarting...";
//            runScript(device, SCRIPT_RESTART, listener, true, device.serial);
            listener.handleDeviceUpdated(device);
        });
    }

    public void runCustomCommand(Device device, String customCommand, DeviceListener listener) {
        commandExecutorService.submit(() -> {
            device.status = "running...";
            runScript(device, SCRIPT_CUSTOM_COMMAND, listener, true, device.serial);
        });
    }

    public void openTerminal(Device device, DeviceListener listener) {
        commandExecutorService.submit(() -> {
            device.status = "terminal...";
            runScript(device, SCRIPT_TERMINAL, listener, false, device.serial);
        });
    }

    public interface DeviceFileListener {
        void handleFiles(List<RemoteFile> fileList);
    }

    public void listFiles(Device device, String path, DeviceFileListener listener) {
        if (!TextUtils.endsWith(path, "/")) path += "/";
        String finalPath = path;
        commandExecutorService.submit(() -> {
            log.trace("listFiles: {}", finalPath);
            try {
                List<RemoteFile> remoteFileList = device.jadbDevice.list(finalPath);
                //log.trace("listFiles: results: {}", GsonHelper.toJson(remoteFileList));
                // for some reason the top level directory returns "." and ".."
                remoteFileList.removeIf(remoteFile -> TextUtils.equalsAny(remoteFile.getName(), false, "..", "."));
                listener.handleFiles(remoteFileList);
            } catch (Exception e) {
                log.error("listFiles: {}, Exception:{}", finalPath, e.getMessage());
                log.debug("listFiles: ", e);
                listener.handleFiles(null);
            }
        });
    }

    public interface TaskListener {
        void onTaskComplete(boolean isSuccess);
    }

    public void downloadFile(Device device, RemoteFile file, File saveFile, TaskListener listener) {
        commandExecutorService.submit(() -> {
            log.debug("downloadFile: {} -> {}", file, saveFile.getAbsoluteFile());
            downloadFileInternal(device, file, saveFile);
        });
    }

    /**
     * recursive method to download a file or folder
     */
    private void downloadFileInternal(Device device, RemoteFile file, File saveFile) {
        if (file.isDirectory() || file.isSymbolicLink()) {
            // create local folder
            boolean isOk = saveFile.mkdir();
            log.trace("downloadFile: DIR:{}, mkdir:{}", saveFile.getAbsoluteFile(), isOk);
            // get list of files in folder
            try {
                List<RemoteFile> fileList = device.jadbDevice.list(file.getPath());
                for (RemoteFile remoteFile : fileList) {
                    File subFile = new File(saveFile, remoteFile.getName());
                    downloadFileInternal(device, remoteFile, subFile);
                }
            } catch (Exception e) {
                log.error("downloadFile: {}, Exception:{}", file, e.getMessage());
            }
        } else {
            // pull file
            log.trace("downloadFile: {} -> {}", file, saveFile.getAbsoluteFile());
            try {
                device.jadbDevice.pull(file, saveFile);
            } catch (Exception e) {
                log.error("downloadFile: {}, Exception:{}", file, e.getMessage());
            }
        }
    }

    public void deleteFile(Device device, RemoteFile file, TaskListener listener) {
        commandExecutorService.submit(() -> {
            List<String> resultList = runShell(device, "rm -rf " + file.getPath());
            log.debug("deleteFile: {} -> {}", file, GsonHelper.toJson(resultList));
            // TODO: determine success/fail
            listener.onTaskComplete(true);
        });
    }

    public void connectDevice(String ip, int port, TaskListener listener) {
        commandExecutorService.submit(() -> {
            try {
                log.debug("connectDevice: {}:{}", ip, port);
                connection.connectToTcpDevice(new InetSocketAddress(ip, port));
                listener.onTaskComplete(true);
            } catch (Exception e) {
                log.error("connectDevice: {}:{}, Exception:{}", ip, port, e.getMessage());
                listener.onTaskComplete(false);
            }
        });
    }

    public void disconnectDevice(String serial, TaskListener listener) {
        commandExecutorService.submit(() -> {
            String[] deviceArr = TextUtils.split(serial, ":");
            if (deviceArr.length < 2) {
                log.error("disconnectDevice: bad device:{}", serial);
                return;
            }
            String ip = deviceArr[0];
            try {
                int port = Integer.parseInt(deviceArr[1]);
                log.debug("disconnectDevice: {}:{}", ip, port);
                connection.disconnectFromTcpDevice(new InetSocketAddress(ip, port));
                listener.onTaskComplete(true);
            } catch (Exception e) {
                log.error("connectDevice: {}, Exception:{}", serial, e.getMessage());
                listener.onTaskComplete(false);
            }
        });
    }

    public void sendInputText(Device device, String text, TaskListener listener) {
        commandExecutorService.submit(() -> {
            log.debug("sendInputText: {}", text);
            try {
                device.jadbDevice.inputText(text);
                if (listener != null) listener.onTaskComplete(true);
            } catch (Exception e) {
                log.error("sendInputText: {}, Exception:{}", text, e.getMessage());
                if (listener != null) listener.onTaskComplete(false);
            }
        });
    }

    public void sendInputKeyCode(Device device, int keyEvent, TaskListener listener) {
        commandExecutorService.submit(() -> {
            log.debug("sendInputKeyCode: {}", keyEvent);
            try {
                device.jadbDevice.inputKeyEvent(keyEvent);
                if (listener != null) listener.onTaskComplete(true);
            } catch (Exception e) {
                log.error("sendInputKeyCode: {}, Exception:{}", keyEvent, e.getMessage());
                if (listener != null) listener.onTaskComplete(false);
            }
        });
    }

    public interface DeviceLogListener {
        void handleLogEntries(List<LogEntry> logEntryList);

        void handleProcessMap(Map<String, String> processMap);
    }

    public void startLogging(Device device, Long startTime, DeviceLogListener listener) {
        stopLogging(device);
        commandExecutorService.submit(() -> {
            log.debug("startLogging: {}", startTime);
            isLogging.set(true);
            InputStream inputStream = null;
            try {
                String[] args = new String[]{"-v", "threadtime"};
                inputStream = device.jadbDevice.executeShell("logcat", args);
                BufferedReader input = new BufferedReader(new InputStreamReader(inputStream));

                long lastUpdateMs = System.currentTimeMillis();
                List<LogEntry> logList = new ArrayList<>();
                // 10-16 11:34:17.824
                SimpleDateFormat inputFormat = new SimpleDateFormat("MM-dd HH:mm:ss");
                // display format
                SimpleDateFormat outputFormat = new SimpleDateFormat("MM-dd HH:mm:ss");

                String line;
                while ((line = input.readLine()) != null) {
                    LogEntry logEntry = new LogEntry(line, inputFormat, outputFormat);
                    if (logEntry.date == null) continue;
                    else if (startTime != null && logEntry.timestamp < startTime) continue;

                    logList.add(logEntry);

                    // only update every X ms
                    if (System.currentTimeMillis() - lastUpdateMs >= 100 && !logList.isEmpty()) {
                        // update
                        listener.handleLogEntries(logList);
                        logList.clear();
                        lastUpdateMs = System.currentTimeMillis();

                        // check if logging is still running
                        if (!isLogging.get()) break;
                    }
                }
            } catch (Exception e) {
                log.error("startLogging: {}", e.getMessage());
            } finally {
                if (inputStream != null) {
                    try {
                        inputStream.close();
                    } catch (IOException ignored) {
                    }
                }
            }
            log.debug("startLogging: DONE");
        });

        // run a periodic task to fetch running apps from device so logs can replace PID with app name
        commandExecutorService.submit(() -> {
            Map<String, String> pidMap = getProcessMap(device);
            if (!pidMap.isEmpty()) {
                listener.handleProcessMap(pidMap);
            }
            // check if logging is still running and schedule next lookup
            if (isLogging.get()) {
                scheduleNextProcessCheck(device, listener);
            }
        });
    }

    private void scheduleNextProcessCheck(Device device, DeviceLogListener listener) {
        // make sure logging is still running
        if (!isLogging.get()) return;

        // run in 30 seconds
        scheduledExecutorService.schedule(() -> {
            // make sure logging is still running
            if (!isLogging.get()) return;

            Map<String, String> pidMap = getProcessMap(device);
            listener.handleProcessMap(pidMap);
            scheduleNextProcessCheck(device, listener);
        }, 30, TimeUnit.SECONDS);
    }

    private Map<String, String> getProcessMap(Device device) {
        List<String> pidList = runShell(device, COMMAND_LIST_PROCESSES);
        // 7617 com.android.traceur
        // 7677 [csf_sync_update]
        Map<String, String> pidMap = new HashMap<>();
        for (String line : pidList) {
            String[] lineArr = line.trim().split(" ", 2);
            if (lineArr.length < 2) continue;
            String pid = lineArr[0];
            String app = lineArr[1];
            pidMap.put(pid, app);
        }
        return pidMap;
    }

    public void stopLogging(Device device) {
        if (isLogging.get()) {
            log.debug("stopLogging: ");
            isLogging.set(false);
        }
    }

    public boolean isLogging(Device device) {
        return isLogging.get();
    }

    public void handleExit() {
        List<Process> processCopyList;
        synchronized (processList) {
            processCopyList = new ArrayList<>(processList);
        }

        for (Process process : processCopyList) {
            if (process.isAlive()) {
                log.debug("handleExit: killing: {}", process);
                process.destroy();
            }
        }
    }

    /**
     * 1-time copy of all resources/scripts/*.sh files to temp folder and make them executable
     */
    private void copyResourcesToFiles() {
        try {
            File file = new File(getClass().getProtectionDomain().getCodeSource().getLocation().toURI());
            if (file.getName().endsWith(".jar")) {
                // running via JAR file
                int numScripts = 0;
                JarFile jarFile = new JarFile(file);
                Enumeration<JarEntry> entries = jarFile.entries();
                while (entries.hasMoreElements()) {
                    JarEntry jarEntry = entries.nextElement();
                    String name = jarEntry.getName();
                    if (name.endsWith(".sh")) {
                        int pos = name.indexOf("scripts/");
                        if (pos >= 0) {
                            name = name.substring(pos + "scripts/".length());
                            copyResourceToFile(name, jarFile.getInputStream(jarEntry));
                            numScripts++;
                        }
                    }
                }
                log.trace("copyResourcesToFiles: {}, JAR: {}, tmp:{}", numScripts, file, tempFolder);
            } else {
                log.trace("copyResourcesToFiles: IDE: {}, tmp:{}", file, tempFolder);
                // running via IntelliJ IDE
                URL url = getClass().getResource("/scripts");
                Path path = Paths.get(url.toURI());
                Files.walk(path, 1).forEach(p -> {
                    String filename = p.toString();
                    if (filename.endsWith(".sh")) {
                        try {
                            String name = p.toFile().getName();
                            InputStream inputStream = Files.newInputStream(p);
                            copyResourceToFile(name, inputStream);
                        } catch (IOException e) {
                            log.debug("copyResourcesToFiles: IOException:{}", e.getMessage());
                        }
                    }
                });
            }
        } catch (Exception e) {
            log.error("copyResourcesToFiles: Exception:", e);
        }
    }

    private void copyResourceToFile(String name, InputStream is) {
        File tempFile = new File(tempFolder, name);
        //log.trace("copyResource: {} to {}", name, tempFile.getAbsolutePath());
        try {
            BufferedReader r = new BufferedReader(new InputStreamReader(is));
            StringBuilder sb = new StringBuilder();
            for (String line; (line = r.readLine()) != null; ) {
                sb.append(line).append('\n');
            }
            r.close();
            is.close();

            Files.write(tempFile.toPath(), sb.toString().getBytes(), StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.CREATE, StandardOpenOption.WRITE);

            tempFile.setExecutable(true);
        } catch (Exception e) {
            log.error("copyResource: Exception:", e);
        }
    }

    public File getScriptFile(String scriptName) {
        File tempFile = new File(tempFolder, scriptName);
        if (!tempFile.exists()) {
            log.error("runScript: script doesn't exist! {}", tempFile.getAbsoluteFile());
            // try re-creating the files again
            copyResourcesToFiles();
            if (!tempFile.exists()) {
                log.error("runScript: script STILL doesn't exist! {}", tempFile.getAbsoluteFile());
                return null;
            }
        }
        return tempFile;
    }

    private void runScript(Device device, String script, DeviceListener listener, boolean showSuccess, String arg) {
        if (listener != null) listener.handleDeviceUpdated(device);
        ScriptResult result = runScript(script, device.serial, arg);
        if (result.isSuccess) device.status = showSuccess ? GsonHelper.toJson(result.stdOut) : null;
        else device.status = GsonHelper.toJson(result.stdErr);
        if (listener != null) listener.handleDeviceUpdated(device);
    }

    public static class ScriptResult {
        boolean isSuccess;
        List<String> stdOut;
        List<String> stdErr;
    }

    public ScriptResult runScript(String scriptName, String... args) {
        return runScript(scriptName, false, true, args);
    }

    public ScriptResult runScript(String scriptName, boolean isLongRunning, boolean logResults, String... args) {
        //if (logResults) log.trace("runScript: {}, args:{}", scriptName, GsonHelper.toJson(args));
        File tempFile = getScriptFile(scriptName);
        return runScript(tempFile, isLongRunning, logResults, args);
    }

    public ScriptResult runScript(File script, boolean isLongRunning, boolean logResults, String... args) {
        ScriptResult result = new ScriptResult();
        Timer timer = new Timer();
        String name = script.getName();
        try {
            List<String> commandList = new ArrayList<>();
            commandList.add(script.getAbsolutePath());
            if (args != null) {
                commandList.addAll(Arrays.asList(args));
            }

            ProcessBuilder processBuilder = new ProcessBuilder();
            processBuilder.command(commandList);
            Process process = processBuilder.start();
            synchronized (processList) {
                processList.add(process);
            }

            int exitValue = 0;
            if (isLongRunning) {
                // wait until process exits
                exitValue = process.waitFor();
            } else {
                // only allow up to X seconds for process to finish
                boolean isExited = process.waitFor(30, TimeUnit.SECONDS);
                if (isExited) {
                    exitValue = process.exitValue();
                } else {
                    log.error("runScript: {}: NOT FINISHED: {}, args:{}", timer, name, GsonHelper.toJson(args));
                }
            }
            result.isSuccess = exitValue == 0;
            if (!result.isSuccess) {
                log.error("runScript: {}: ERROR: {}, rc:{}, args:{}", timer, name, exitValue, GsonHelper.toJson(args));
            }

            result.stdOut = readInputStream(process.getInputStream());
            if (!result.stdOut.isEmpty() && log.isTraceEnabled() && logResults) {
                log.trace("runScript: {}: {}, RESULTS: {}", timer, name, GsonHelper.toJson(result.stdOut));
            }
            result.stdErr = readInputStream(process.getErrorStream());
            synchronized (processList) {
                processList.remove(process);
            }
            if (!result.stdErr.isEmpty()) {
                log.error("runScript: {}: ERROR: {}, {}", timer, name, GsonHelper.toJson(result.stdErr));
            }
            return result;
        } catch (Exception e) {
            result.isSuccess = false;
            result.stdErr = List.of("Exception: " + e.getMessage());
            log.error("runScript: {}: Exception: {}, {}, {}", timer, name, e.getClass().getSimpleName(), e.getMessage());
        }
        return result;
    }

    private List<String> readInputStream(InputStream inputStream) {
        BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream));
        String line;
        List<String> resultList = new ArrayList<>();
        try {
            while (true) {
                line = reader.readLine();
                if (line == null) break;
                else if (line.isEmpty()) continue;
                //log.debug("runScript: {}", line);
                resultList.add(line);
            }
            reader.close();
        } catch (IOException e) {
            log.error("readInputStream: Exception: {}", e.getMessage());
        }
        return resultList;
    }
}
