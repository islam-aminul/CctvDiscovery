package com.cctv.discovery.discovery;

import com.cctv.discovery.model.Device;
import com.cctv.discovery.service.MacLookupService;
import com.cctv.discovery.service.OnvifService;
import com.cctv.discovery.util.NetworkUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;

/**
 * Network scanner for CCTV device discovery.
 * Implements WS-Discovery, port scanning, and MAC resolution.
 */
public class NetworkScanner {
    private static final Logger logger = LoggerFactory.getLogger(NetworkScanner.class);

    private static final int[] TARGET_PORTS = {80, 8080, 554, 8554, 443, 8443, 8000, 8888, 37777, 34567};
    private static final int PORT_SCAN_TIMEOUT_MS = 2000;

    private final OnvifService onvifService;
    private final MacLookupService macLookupService;
    private ExecutorService executorService;

    public NetworkScanner() {
        this.onvifService = new OnvifService();
        this.macLookupService = MacLookupService.getInstance();

        // Thread pool: 8x CPU cores, capped at 64
        int cpuCores = Runtime.getRuntime().availableProcessors();
        int threadPoolSize = Math.min(cpuCores * 8, 64);
        this.executorService = Executors.newFixedThreadPool(threadPoolSize);

        logger.info("NetworkScanner initialized with {} threads", threadPoolSize);
    }

    /**
     * Perform WS-Discovery to find ONVIF devices.
     */
    public List<Device> performWsDiscovery() {
        logger.info("Starting WS-Discovery...");
        List<Device> devices = onvifService.discoverDevices();
        logger.info("WS-Discovery found {} devices", devices.size());
        return devices;
    }

    /**
     * Perform port scan on given IP addresses.
     */
    public List<Device> performPortScan(List<String> ipAddresses, ProgressCallback callback) {
        logger.info("Starting port scan on {} IP addresses", ipAddresses.size());

        List<Device> devices = new CopyOnWriteArrayList<>();
        List<Future<?>> futures = new ArrayList<>();
        int totalIps = ipAddresses.size();
        int[] processedCount = {0};

        for (String ip : ipAddresses) {
            Future<?> future = executorService.submit(() -> {
                Device device = scanDevice(ip);
                if (device != null) {
                    devices.add(device);
                }

                synchronized (processedCount) {
                    processedCount[0]++;
                    if (callback != null) {
                        callback.onProgress(processedCount[0], totalIps);
                    }
                }
            });
            futures.add(future);
        }

        // Wait for all scans to complete
        for (Future<?> future : futures) {
            try {
                future.get();
            } catch (Exception e) {
                logger.error("Error during port scan", e);
            }
        }

        logger.info("Port scan completed. Found {} devices", devices.size());
        return new ArrayList<>(devices);
    }

    /**
     * Scan a single device for open ports.
     */
    private Device scanDevice(String ip) {
        List<Integer> openPorts = new ArrayList<>();

        for (int port : TARGET_PORTS) {
            if (NetworkUtils.isPortOpen(ip, port, PORT_SCAN_TIMEOUT_MS)) {
                openPorts.add(port);
            }
        }

        if (openPorts.isEmpty()) {
            return null;
        }

        Device device = new Device(ip);

        // Categorize ports
        for (int port : openPorts) {
            if (port == 80 || port == 8080 || port == 443 || port == 8443) {
                device.getOpenOnvifPorts().add(port);
            } else if (port == 554 || port == 8554 || port == 8888) {
                device.getOpenRtspPorts().add(port);
            } else {
                device.getOpenSpecialPorts().add(port);
            }
        }

        // Check if likely NVR/DVR
        if (openPorts.contains(8000) || openPorts.contains(37777)) {
            device.setNvrDvr(true);
        }

        // Resolve MAC address
        String mac = NetworkUtils.resolveMacAddress(ip);
        if (mac != null) {
            device.setMacAddress(mac);
            String manufacturer = macLookupService.lookupManufacturer(mac);
            device.setManufacturer(manufacturer);
        }

        logger.info("Scanned device: {} - Open ports: {}", ip, openPorts);
        return device;
    }

    /**
     * Merge WS-Discovery and port scan results.
     */
    public List<Device> mergeDeviceLists(List<Device> wsDevices, List<Device> portScanDevices) {
        List<Device> merged = new ArrayList<>();
        List<String> wsIps = new ArrayList<>();

        // Add WS-Discovery devices first
        for (Device device : wsDevices) {
            merged.add(device);
            wsIps.add(device.getIpAddress());

            // Resolve MAC if not already set
            if (device.getMacAddress() == null) {
                String mac = NetworkUtils.resolveMacAddress(device.getIpAddress());
                if (mac != null) {
                    device.setMacAddress(mac);
                    if (device.getManufacturer() == null) {
                        String manufacturer = macLookupService.lookupManufacturer(mac);
                        device.setManufacturer(manufacturer);
                    }
                }
            }
        }

        // Add port scan devices that weren't found via WS-Discovery
        for (Device device : portScanDevices) {
            if (!wsIps.contains(device.getIpAddress())) {
                merged.add(device);
            } else {
                // Merge port information
                Device existing = findDeviceByIp(merged, device.getIpAddress());
                if (existing != null) {
                    mergePortInfo(existing, device);
                }
            }
        }

        logger.info("Merged device lists: {} total devices", merged.size());
        return merged;
    }

    /**
     * Merge port information from scanned device into existing device.
     */
    private void mergePortInfo(Device existing, Device scanned) {
        for (int port : scanned.getOpenOnvifPorts()) {
            if (!existing.getOpenOnvifPorts().contains(port)) {
                existing.getOpenOnvifPorts().add(port);
            }
        }
        for (int port : scanned.getOpenRtspPorts()) {
            if (!existing.getOpenRtspPorts().contains(port)) {
                existing.getOpenRtspPorts().add(port);
            }
        }
        for (int port : scanned.getOpenSpecialPorts()) {
            if (!existing.getOpenSpecialPorts().contains(port)) {
                existing.getOpenSpecialPorts().add(port);
            }
        }
    }

    private Device findDeviceByIp(List<Device> devices, String ip) {
        for (Device device : devices) {
            if (device.getIpAddress().equals(ip)) {
                return device;
            }
        }
        return null;
    }

    /**
     * Shutdown the executor service.
     */
    public void shutdown() {
        if (executorService != null && !executorService.isShutdown()) {
            executorService.shutdown();
            try {
                if (!executorService.awaitTermination(10, TimeUnit.SECONDS)) {
                    executorService.shutdownNow();
                }
            } catch (InterruptedException e) {
                executorService.shutdownNow();
            }
        }
    }

    /**
     * Progress callback interface.
     */
    public interface ProgressCallback {
        void onProgress(int current, int total);
    }
}
