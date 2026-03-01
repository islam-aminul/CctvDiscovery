package com.cctv.discovery.service;

import com.cctv.discovery.model.Device;
import com.cctv.discovery.util.AuthUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.soap.*;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;

/**
 * ONVIF service for WS-Discovery, device communication, and authentication.
 * Strict implementation using JDK 8 javax.xml.soap and org.w3c.dom - NO REGEX.
 */
public class OnvifService {
    private static final Logger logger = LoggerFactory.getLogger(OnvifService.class);

    private static final String WS_DISCOVERY_ADDRESS = "239.255.255.250";
    private static final int WS_DISCOVERY_PORT = 3702;
    private static final int DISCOVERY_TIMEOUT_MS = 5000;

    // Static block to disable SSL certificate validation for self-signed camera
    // certificates
    static {
        try {
            // Create a trust manager that trusts all certificates
            TrustManager[] trustAllCerts = new TrustManager[] {
                    new X509TrustManager() {
                        public X509Certificate[] getAcceptedIssuers() {
                            return null;
                        }

                        public void checkClientTrusted(X509Certificate[] certs, String authType) {
                            // Trust all client certificates
                        }

                        public void checkServerTrusted(X509Certificate[] certs, String authType) {
                            // Trust all server certificates (cameras with self-signed certs)
                        }
                    }
            };

            // Install the all-trusting trust manager
            SSLContext sc = SSLContext.getInstance("TLS");
            sc.init(null, trustAllCerts, new java.security.SecureRandom());
            HttpsURLConnection.setDefaultSSLSocketFactory(sc.getSocketFactory());

            // Disable hostname verification (cameras often use IP addresses)
            HttpsURLConnection.setDefaultHostnameVerifier((hostname, session) -> true);

            logger.info("SSL certificate validation disabled for ONVIF HTTPS connections");
        } catch (Exception e) {
            logger.error("Failed to disable SSL certificate validation", e);
        }
    }

    /**
     * Send WS-Discovery probe and collect ONVIF device responses.
     */
    public List<Device> discoverDevices() {
        List<Device> devices = new ArrayList<>();
        DatagramSocket socket = null;

        try {
            socket = new DatagramSocket();
            socket.setSoTimeout(DISCOVERY_TIMEOUT_MS);

            String probeMessage = buildWsDiscoveryProbe();
            byte[] sendData = probeMessage.getBytes("UTF-8");

            InetAddress group = InetAddress.getByName(WS_DISCOVERY_ADDRESS);
            DatagramPacket sendPacket = new DatagramPacket(sendData, sendData.length, group, WS_DISCOVERY_PORT);

            socket.send(sendPacket);
            logger.info("WS-Discovery probe sent to {}:{}", WS_DISCOVERY_ADDRESS, WS_DISCOVERY_PORT);

            byte[] receiveData = new byte[8192];
            long startTime = System.currentTimeMillis();

            while (System.currentTimeMillis() - startTime < DISCOVERY_TIMEOUT_MS) {
                try {
                    DatagramPacket receivePacket = new DatagramPacket(receiveData, receiveData.length);
                    socket.receive(receivePacket);

                    String response = new String(receivePacket.getData(), 0, receivePacket.getLength(), "UTF-8");
                    Device device = parseProbeMatch(response);

                    if (device != null) {
                        devices.add(device);
                        logger.info("Discovered ONVIF device: {}", device.getIpAddress());
                    }
                } catch (Exception e) {
                    // Timeout or parsing error - continue
                }
            }

        } catch (Exception e) {
            logger.error("Error during WS-Discovery", e);
        } finally {
            if (socket != null && !socket.isClosed()) {
                socket.close();
            }
        }

        logger.info("WS-Discovery completed. Found {} devices", devices.size());
        return devices;
    }

    /**
     * Build WS-Discovery Probe message using SOAP.
     */
    private String buildWsDiscoveryProbe() throws Exception {
        MessageFactory messageFactory = MessageFactory.newInstance();
        SOAPMessage soapMessage = messageFactory.createMessage();
        SOAPPart soapPart = soapMessage.getSOAPPart();
        SOAPEnvelope envelope = soapPart.getEnvelope();

        envelope.addNamespaceDeclaration("wsa", "http://schemas.xmlsoap.org/ws/2004/08/addressing");
        envelope.addNamespaceDeclaration("wsd", "http://schemas.xmlsoap.org/ws/2005/04/discovery");
        envelope.addNamespaceDeclaration("wsdp", "http://schemas.xmlsoap.org/ws/2006/02/devprof");

        SOAPHeader header = envelope.getHeader();
        SOAPBody body = envelope.getBody();

        // Header elements
        addHeaderElement(header, "wsa:Action", "http://schemas.xmlsoap.org/ws/2005/04/discovery/Probe");
        addHeaderElement(header, "wsa:MessageID", AuthUtils.generateUUID());
        addHeaderElement(header, "wsa:To", "urn:schemas-xmlsoap-org:ws:2005:04:discovery");

        // Body - Probe
        SOAPElement probe = body.addChildElement("Probe", "wsd");
        SOAPElement types = probe.addChildElement("Types", "wsd");
        types.setTextContent("wsdp:Device");

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        soapMessage.writeTo(out);
        return out.toString("UTF-8");
    }

    private void addHeaderElement(SOAPHeader header, String qName, String value) throws Exception {
        String[] parts = qName.split(":");
        SOAPElement element = header.addChildElement(parts[1], parts[0]);
        element.setTextContent(value);
    }

    /**
     * Parse ProbeMatch response using DOM parser (NO REGEX).
     */
    private Device parseProbeMatch(String xml) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            DocumentBuilder builder = factory.newDocumentBuilder();
            Document doc = builder.parse(new ByteArrayInputStream(xml.getBytes("UTF-8")));

            // Extract XAddrs (service URLs)
            NodeList xAddrsList = doc.getElementsByTagNameNS("*", "XAddrs");
            if (xAddrsList.getLength() == 0) {
                return null;
            }

            String xAddrs = xAddrsList.item(0).getTextContent().trim();
            if (xAddrs.isEmpty()) {
                return null;
            }

            // Extract first URL and parse IP
            String[] urls = xAddrs.split("\\s+");
            String serviceUrl = urls[0];
            String ipAddress = extractIpFromUrl(serviceUrl);

            if (ipAddress == null) {
                return null;
            }

            Device device = new Device(ipAddress);
            device.setOnvifServiceUrl(serviceUrl);

            // Extract UUID/EndpointReference for MAC resolution
            // UUID often contains MAC in last 12 hex digits: uuid:xxxxxxxx-xxxx-xxxx-xxxx-AABBCCDDEEFF
            NodeList endpointList = doc.getElementsByTagNameNS("*", "EndpointReference");
            if (endpointList.getLength() > 0) {
                Element endpoint = (Element) endpointList.item(0);
                NodeList addressList = endpoint.getElementsByTagNameNS("*", "Address");
                if (addressList.getLength() > 0) {
                    String uuid = addressList.item(0).getTextContent().trim();
                    if (device.getMacAddress() == null || device.getMacAddress().isEmpty()) {
                        String mac = extractMacFromUuid(uuid);
                        if (mac != null) {
                            device.setMacAddress(mac);
                            logger.info("Extracted MAC address from UUID for {}: {}", ipAddress, mac);
                        }
                    }
                }
            }

            return device;

        } catch (Exception e) {
            logger.info("Error parsing ProbeMatch", e);
            return null;
        }
    }

    /**
     * Extract IP address from ONVIF service URL.
     * Uses java.net.URI instead of deprecated java.net.URL constructor.
     */
    private String extractIpFromUrl(String url) {
        try {
            java.net.URI uri = new java.net.URI(url);
            String host = uri.getHost();
            // Check if host is IP address (not hostname)
            if (host != null && host.matches("\\d+\\.\\d+\\.\\d+\\.\\d+")) {
                return host;
            }
        } catch (Exception e) {
            logger.info("Error extracting IP from URL: {}", url);
        }
        return null;
    }

    /**
     * Get device information using ONVIF GetDeviceInformation.
     */
    public boolean getDeviceInformation(Device device, String username, String password) {
        if (device.getOnvifServiceUrl() == null) {
            logger.warn("No ONVIF service URL set for device {}", device.getIpAddress());
            return false;
        }
        return getDeviceInformation(device, device.getOnvifServiceUrl(), username, password);
    }

    /**
     * Get device information using ONVIF GetDeviceInformation with explicit service
     * URL.
     */
    public boolean getDeviceInformation(Device device, String serviceUrl, String username, String password) {
        logger.info("ONVIF GetDeviceInformation request to {} with user {}", serviceUrl, username);

        try {
            String soapRequest = buildGetDeviceInformationRequest(username, password);
            String response = sendOnvifRequest(serviceUrl, soapRequest, username, password);

            if (response == null) {
                logger.warn("ONVIF GetDeviceInformation FAILED for {} - no response", serviceUrl);
                return false;
            }

            parseDeviceInformation(device, response);
            device.setUsername(username);
            device.setPassword(password);
            device.setOnvifAuthMethod(Device.OnvifAuthMethod.WS_SECURITY);

            logger.info("ONVIF GetDeviceInformation SUCCESS - Model: {}, Manufacturer: {}",
                    device.getModel(), device.getManufacturer());

            return true;

        } catch (Exception e) {
            logger.error("ONVIF GetDeviceInformation FAILED for {}: {}", serviceUrl, e.getMessage());
            return false;
        }
    }

    /**
     * Try ONVIF device discovery using constructed URLs from detected ports.
     * Used when WS-Discovery fails (IGMP blocked) but device has HTTP/HTTPS ports
     * open.
     */
    public boolean discoverDeviceByPort(Device device, int port, String username, String password) {
        String protocol = (port == 443 || port == 8443) ? "https" : "http";
        String serviceUrl = protocol + "://" + device.getIpAddress() + ":" + port + "/onvif/device_service";

        logger.info("Attempting ONVIF on constructed URL: {}", serviceUrl);

        // Try to get device information
        boolean success = getDeviceInformation(device, serviceUrl, username, password);

        if (success) {
            device.setOnvifServiceUrl(serviceUrl);
            logger.info("ONVIF successful on {}, service URL set", serviceUrl);
            return true;
        }

        logger.info("ONVIF failed on {}", serviceUrl);
        return false;
    }

    /**
     * Build GetDeviceInformation SOAP request with WS-Security.
     */
    private String buildGetDeviceInformationRequest(String username, String password) throws Exception {
        MessageFactory messageFactory = MessageFactory.newInstance();
        SOAPMessage soapMessage = messageFactory.createMessage();
        SOAPPart soapPart = soapMessage.getSOAPPart();
        SOAPEnvelope envelope = soapPart.getEnvelope();

        envelope.addNamespaceDeclaration("tds", "http://www.onvif.org/ver10/device/wsdl");

        SOAPHeader header = envelope.getHeader();
        SOAPBody body = envelope.getBody();

        // Add WS-Security header
        String securityHeader = AuthUtils.generateWsSecurityHeader(username, password);
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        Document secDoc = factory.newDocumentBuilder().parse(
                new ByteArrayInputStream(securityHeader.getBytes("UTF-8")));
        header.appendChild(header.getOwnerDocument().importNode(secDoc.getDocumentElement(), true));

        // Add body
        body.addChildElement("GetDeviceInformation", "tds");

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        soapMessage.writeTo(out);
        return out.toString("UTF-8");
    }

    /**
     * Parse GetDeviceInformation response.
     */
    private void parseDeviceInformation(Device device, String xml) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        DocumentBuilder builder = factory.newDocumentBuilder();
        Document doc = builder.parse(new ByteArrayInputStream(xml.getBytes("UTF-8")));

        String manufacturer = getElementText(doc, "Manufacturer");
        String model = getElementText(doc, "Model");
        String serialNumber = getElementText(doc, "SerialNumber");

        device.setManufacturer(manufacturer != null ? manufacturer : "Unknown");
        device.setModel(model);
        device.setSerialNumber(serialNumber);
    }

    /**
     * Get video sources (channels) from ONVIF device.
     */
    public List<String> getVideoSources(Device device) {
        List<String> sources = new ArrayList<>();
        logger.info("Retrieving video sources for device: {}", device.getIpAddress());

        try {
            String soapRequest = buildGetVideoSourcesRequest(device.getUsername(), device.getPassword());
            String response = sendOnvifRequest(device.getOnvifServiceUrl(), soapRequest,
                    device.getUsername(), device.getPassword());

            if (response != null) {
                sources = parseVideoSources(response);
                logger.info("Retrieved {} video sources from ONVIF for {}", sources.size(), device.getIpAddress());
            } else {
                logger.warn("No response from GetVideoSources for {}", device.getIpAddress());
            }

        } catch (Exception e) {
            logger.error("Error getting video sources for {}: {}", device.getIpAddress(), e.getMessage());
        }
        return sources;
    }

    /**
     * Build GetVideoSources SOAP request.
     */
    private String buildGetVideoSourcesRequest(String username, String password) throws Exception {
        MessageFactory messageFactory = MessageFactory.newInstance();
        SOAPMessage soapMessage = messageFactory.createMessage();
        SOAPPart soapPart = soapMessage.getSOAPPart();
        SOAPEnvelope envelope = soapPart.getEnvelope();

        envelope.addNamespaceDeclaration("trt", "http://www.onvif.org/ver10/media/wsdl");

        SOAPHeader header = envelope.getHeader();
        SOAPBody body = envelope.getBody();

        // Add WS-Security header
        String securityHeader = AuthUtils.generateWsSecurityHeader(username, password);
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        Document secDoc = factory.newDocumentBuilder().parse(
                new ByteArrayInputStream(securityHeader.getBytes("UTF-8")));
        header.appendChild(header.getOwnerDocument().importNode(secDoc.getDocumentElement(), true));

        // Add body
        body.addChildElement("GetVideoSources", "trt");

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        soapMessage.writeTo(out);
        return out.toString("UTF-8");
    }

    /**
     * Parse GetVideoSources response.
     */
    private List<String> parseVideoSources(String xml) throws Exception {
        List<String> sources = new ArrayList<>();
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        DocumentBuilder builder = factory.newDocumentBuilder();
        Document doc = builder.parse(new ByteArrayInputStream(xml.getBytes("UTF-8")));

        NodeList videoSourcesList = doc.getElementsByTagNameNS("*", "VideoSources");
        for (int i = 0; i < videoSourcesList.getLength(); i++) {
            Element videoSource = (Element) videoSourcesList.item(i);
            String token = videoSource.getAttribute("token");
            if (token != null && !token.isEmpty()) {
                sources.add(token);
            }
        }

        return sources;
    }

    /**
     * Get device hostname using ONVIF GetHostname.
     * Sets device name from the hostname if available.
     */
    public void getHostname(Device device) {
        if (device.getOnvifServiceUrl() == null || device.getUsername() == null) {
            return;
        }
        logger.info("Retrieving hostname for device: {}", device.getIpAddress());

        try {
            String soapRequest = buildGetHostnameRequest(device.getUsername(), device.getPassword());
            String response = sendOnvifRequest(device.getOnvifServiceUrl(), soapRequest,
                    device.getUsername(), device.getPassword());

            if (response != null) {
                parseHostname(device, response);
            } else {
                logger.info("No response from GetHostname for {}", device.getIpAddress());
            }
        } catch (Exception e) {
            logger.info("Failed to get hostname for {}: {}", device.getIpAddress(), e.getMessage());
        }
    }

    /**
     * Build GetHostname SOAP request with WS-Security.
     */
    private String buildGetHostnameRequest(String username, String password) throws Exception {
        MessageFactory messageFactory = MessageFactory.newInstance();
        SOAPMessage soapMessage = messageFactory.createMessage();
        SOAPPart soapPart = soapMessage.getSOAPPart();
        SOAPEnvelope envelope = soapPart.getEnvelope();

        envelope.addNamespaceDeclaration("tds", "http://www.onvif.org/ver10/device/wsdl");

        SOAPHeader header = envelope.getHeader();
        SOAPBody body = envelope.getBody();

        // Add WS-Security header
        String securityHeader = AuthUtils.generateWsSecurityHeader(username, password);
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        Document secDoc = factory.newDocumentBuilder().parse(
                new ByteArrayInputStream(securityHeader.getBytes("UTF-8")));
        header.appendChild(header.getOwnerDocument().importNode(secDoc.getDocumentElement(), true));

        // Add body
        body.addChildElement("GetHostname", "tds");

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        soapMessage.writeTo(out);
        return out.toString("UTF-8");
    }

    /**
     * Parse GetHostname response.
     * Response structure:
     * <HostnameInformation><Name>hostname</Name></HostnameInformation>
     */
    private void parseHostname(Device device, String xml) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        DocumentBuilder builder = factory.newDocumentBuilder();
        Document doc = builder.parse(new ByteArrayInputStream(xml.getBytes("UTF-8")));

        String name = getElementText(doc, "Name");
        if (name != null && !name.trim().isEmpty()) {
            device.setDeviceName(name.trim());
            logger.info("Retrieved hostname from ONVIF for {}: {}", device.getIpAddress(), name.trim());
        } else {
            logger.info("GetHostname returned empty name for {}", device.getIpAddress());
        }
    }

    /**
     * Get network interfaces using ONVIF GetNetworkInterfaces.
     * Retrieves MAC address (HwAddress) from the device directly.
     * Used as fallback when ARP-based MAC resolution fails.
     */
    public void getNetworkInterfaces(Device device) {
        if (device.getOnvifServiceUrl() == null || device.getUsername() == null) {
            return;
        }
        logger.info("Retrieving network interfaces for device: {}", device.getIpAddress());

        try {
            String soapRequest = buildGetNetworkInterfacesRequest(device.getUsername(), device.getPassword());
            String response = sendOnvifRequest(device.getOnvifServiceUrl(), soapRequest,
                    device.getUsername(), device.getPassword());

            if (response != null) {
                parseNetworkInterfaces(device, response);
            } else {
                logger.info("No response from GetNetworkInterfaces for {}", device.getIpAddress());
            }
        } catch (Exception e) {
            logger.info("Failed to get network interfaces for {}: {}", device.getIpAddress(), e.getMessage());
        }
    }

    /**
     * Get network interfaces using ONVIF GetNetworkInterfaces WITHOUT authentication.
     * Many cameras allow unauthenticated access to this API.
     * Used as fallback for cross-subnet devices where ARP cannot resolve MAC.
     *
     * @param device     The device to update with MAC address
     * @param serviceUrl The ONVIF service URL to probe
     */
    public void getNetworkInterfacesUnauthenticated(Device device, String serviceUrl) {
        logger.info("Trying unauthenticated GetNetworkInterfaces for {} at {}", device.getIpAddress(), serviceUrl);
        try {
            String soapRequest = buildGetNetworkInterfacesRequestNoAuth();
            String response = sendOnvifRequest(serviceUrl, soapRequest, null, null);

            if (response != null) {
                parseNetworkInterfaces(device, response);
            }
        } catch (Exception e) {
            logger.info("Unauthenticated GetNetworkInterfaces failed for {}: {}", device.getIpAddress(), e.getMessage());
        }
    }

    /**
     * Build GetNetworkInterfaces SOAP request WITHOUT WS-Security (unauthenticated).
     */
    private String buildGetNetworkInterfacesRequestNoAuth() throws Exception {
        MessageFactory messageFactory = MessageFactory.newInstance();
        SOAPMessage soapMessage = messageFactory.createMessage();
        SOAPPart soapPart = soapMessage.getSOAPPart();
        SOAPEnvelope envelope = soapPart.getEnvelope();

        envelope.addNamespaceDeclaration("tds", "http://www.onvif.org/ver10/device/wsdl");

        // No WS-Security header - unauthenticated request
        SOAPBody body = envelope.getBody();
        body.addChildElement("GetNetworkInterfaces", "tds");

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        soapMessage.writeTo(out);
        return out.toString("UTF-8");
    }

    /**
     * Build GetNetworkInterfaces SOAP request with WS-Security.
     */
    private String buildGetNetworkInterfacesRequest(String username, String password) throws Exception {
        MessageFactory messageFactory = MessageFactory.newInstance();
        SOAPMessage soapMessage = messageFactory.createMessage();
        SOAPPart soapPart = soapMessage.getSOAPPart();
        SOAPEnvelope envelope = soapPart.getEnvelope();

        envelope.addNamespaceDeclaration("tds", "http://www.onvif.org/ver10/device/wsdl");

        SOAPHeader header = envelope.getHeader();
        SOAPBody body = envelope.getBody();

        // Add WS-Security header
        String securityHeader = AuthUtils.generateWsSecurityHeader(username, password);
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        Document secDoc = factory.newDocumentBuilder().parse(
                new ByteArrayInputStream(securityHeader.getBytes("UTF-8")));
        header.appendChild(header.getOwnerDocument().importNode(secDoc.getDocumentElement(), true));

        // Add body
        body.addChildElement("GetNetworkInterfaces", "tds");

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        soapMessage.writeTo(out);
        return out.toString("UTF-8");
    }

    /**
     * Parse GetNetworkInterfaces response.
     * Response structure:
     * <NetworkInterfaces><Info><HwAddress>xx:xx:xx:xx:xx:xx</HwAddress></Info></NetworkInterfaces>
     */
    private void parseNetworkInterfaces(Device device, String xml) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        DocumentBuilder builder = factory.newDocumentBuilder();
        Document doc = builder.parse(new ByteArrayInputStream(xml.getBytes("UTF-8")));

        String hwAddress = getElementText(doc, "HwAddress");
        if (hwAddress != null && !hwAddress.trim().isEmpty()) {
            String mac = hwAddress.trim().toUpperCase();
            // Normalize to XX:XX:XX:XX:XX:XX format if needed
            if (mac.contains("-")) {
                mac = mac.replace("-", ":");
            }
            device.setMacAddress(mac);
            logger.info("Retrieved MAC address from ONVIF for {}: {}", device.getIpAddress(), mac);
        } else {
            logger.info("GetNetworkInterfaces returned no HwAddress for {}", device.getIpAddress());
        }
    }

    /**
     * Send ONVIF SOAP request and get response.
     */
    private String sendOnvifRequest(String serviceUrl, String soapRequest, String username, String password) {
        HttpURLConnection connection = null;
        try {
            URL url = new URL(serviceUrl);
            connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("POST");
            connection.setRequestProperty("Content-Type", "application/soap+xml; charset=utf-8");
            connection.setDoOutput(true);
            connection.setConnectTimeout(5000);
            connection.setReadTimeout(10000);

            logger.info("ONVIF SOAP REQUEST");
            logger.info("URL: {}", serviceUrl);
            logger.info("Method: POST");
            logger.info("Content-Type: application/soap+xml; charset=utf-8");
            logger.info("SOAP Request Body:\n{}", soapRequest);

            connection.getOutputStream().write(soapRequest.getBytes("UTF-8"));

            int responseCode = connection.getResponseCode();
            String responseMessage = connection.getResponseMessage();

            logger.info("ONVIF SOAP RESPONSE");
            logger.info("Response Code: {} {}", responseCode, responseMessage);

            // Log response headers
            logger.info("Response Headers:");
            connection.getHeaderFields().forEach((key, values) -> {
                if (key != null) {
                    logger.info("  {}: {}", key, String.join(", ", values));
                }
            });

            if (responseCode == 200) {
                InputStream is = connection.getInputStream();
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                byte[] buffer = new byte[1024];
                int len;
                while ((len = is.read(buffer)) != -1) {
                    baos.write(buffer, 0, len);
                }
                String response = baos.toString("UTF-8");
                logger.info("Response Body:\n{}", response);
                return response;
            } else {
                // Try to read error response body
                InputStream errorStream = connection.getErrorStream();
                if (errorStream != null) {
                    ByteArrayOutputStream errorBaos = new ByteArrayOutputStream();
                    byte[] buffer = new byte[1024];
                    int len;
                    while ((len = errorStream.read(buffer)) != -1) {
                        errorBaos.write(buffer, 0, len);
                    }
                    String errorBody = errorBaos.toString("UTF-8");
                    logger.warn("ONVIF request failed with code: {}. Error body:\n{}", responseCode, errorBody);
                } else {
                    logger.warn("ONVIF request failed with code: {}", responseCode);
                }
                return null;
            }

        } catch (javax.net.ssl.SSLException e) {
            // SSL errors are common for non-ONVIF devices or incompatible SSL configs
            logger.info("SSL error for {}: {}", serviceUrl, e.getMessage());
            return null;
        } catch (java.net.SocketException e) {
            // Connection refused/reset - device doesn't support ONVIF on this port
            logger.info("Connection error for {}: {}", serviceUrl, e.getMessage());
            return null;
        } catch (java.net.SocketTimeoutException e) {
            // Timeout - device not responding
            logger.info("Timeout for {}: {}", serviceUrl, e.getMessage());
            return null;
        } catch (Exception e) {
            // Other unexpected errors - log with stack trace
            logger.error("Error sending ONVIF request to {}", serviceUrl, e);
            return null;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    /**
     * Extract MAC address from ONVIF UUID endpoint reference.
     * UUID format: uuid:xxxxxxxx-xxxx-xxxx-xxxx-AABBCCDDEEFF
     * The last 12 hex digits often represent the device MAC address.
     *
     * @param uuid The UUID string from EndpointReference/Address
     * @return Formatted MAC address (XX:XX:XX:XX:XX:XX) or null if not extractable
     */
    private String extractMacFromUuid(String uuid) {
        if (uuid == null || uuid.isEmpty()) {
            return null;
        }

        // Remove "uuid:" or "urn:uuid:" prefix if present
        String cleanUuid = uuid;
        if (cleanUuid.startsWith("urn:uuid:")) {
            cleanUuid = cleanUuid.substring(9);
        } else if (cleanUuid.startsWith("uuid:")) {
            cleanUuid = cleanUuid.substring(5);
        }

        // Remove hyphens to get continuous hex string
        String hex = cleanUuid.replace("-", "");

        // UUID should be 32 hex characters; last 12 represent MAC
        if (hex.length() < 12) {
            return null;
        }

        // Validate that all characters are hex
        String lastTwelve = hex.substring(hex.length() - 12).toUpperCase();
        for (char c : lastTwelve.toCharArray()) {
            if (!((c >= '0' && c <= '9') || (c >= 'A' && c <= 'F'))) {
                return null;
            }
        }

        // Skip if all zeros (not a real MAC)
        if ("000000000000".equals(lastTwelve)) {
            return null;
        }

        // Format as XX:XX:XX:XX:XX:XX
        StringBuilder mac = new StringBuilder();
        for (int i = 0; i < 12; i += 2) {
            if (mac.length() > 0) {
                mac.append(':');
            }
            mac.append(lastTwelve, i, i + 2);
        }
        return mac.toString();
    }

    /**
     * Extract element text content by tag name.
     */
    private String getElementText(Document doc, String tagName) {
        NodeList nodeList = doc.getElementsByTagNameNS("*", tagName);
        if (nodeList.getLength() > 0) {
            String text = nodeList.item(0).getTextContent();
            return text != null ? text.trim() : null;
        }
        return null;
    }
}
