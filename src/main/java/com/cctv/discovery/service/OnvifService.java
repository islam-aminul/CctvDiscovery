package com.cctv.discovery.service;

import com.cctv.discovery.model.Device;
import com.cctv.discovery.model.RTSPStream;
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
import java.net.InetSocketAddress;
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

    // Static block to disable SSL certificate validation for self-signed camera certificates
    static {
        try {
            // Create a trust manager that trusts all certificates
            TrustManager[] trustAllCerts = new TrustManager[]{
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
            NodeList endpointList = doc.getElementsByTagNameNS("*", "EndpointReference");
            if (endpointList.getLength() > 0) {
                Element endpoint = (Element) endpointList.item(0);
                NodeList addressList = endpoint.getElementsByTagNameNS("*", "Address");
                if (addressList.getLength() > 0) {
                    String uuid = addressList.item(0).getTextContent().trim();
                    // UUID often contains MAC in format: uuid:xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx
                    // We'll extract MAC later if pattern matches
                }
            }

            return device;

        } catch (Exception e) {
            logger.debug("Error parsing ProbeMatch", e);
            return null;
        }
    }

    /**
     * Extract IP address from ONVIF service URL.
     */
    private String extractIpFromUrl(String url) {
        try {
            URL urlObj = new URL(url);
            String host = urlObj.getHost();
            // Check if host is IP address (not hostname)
            if (host.matches("\\d+\\.\\d+\\.\\d+\\.\\d+")) {
                return host;
            }
        } catch (Exception e) {
            logger.debug("Error extracting IP from URL: {}", url);
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
     * Get device information using ONVIF GetDeviceInformation with explicit service URL.
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
     * Used when WS-Discovery fails (IGMP blocked) but device has HTTP/HTTPS ports open.
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

        logger.debug("ONVIF failed on {}", serviceUrl);
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

            connection.getOutputStream().write(soapRequest.getBytes("UTF-8"));

            int responseCode = connection.getResponseCode();
            if (responseCode == 200) {
                InputStream is = connection.getInputStream();
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                byte[] buffer = new byte[1024];
                int len;
                while ((len = is.read(buffer)) != -1) {
                    baos.write(buffer, 0, len);
                }
                return baos.toString("UTF-8");
            } else {
                logger.warn("ONVIF request failed with code: {}", responseCode);
                return null;
            }

        } catch (Exception e) {
            logger.error("Error sending ONVIF request to {}", serviceUrl, e);
            return null;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
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
