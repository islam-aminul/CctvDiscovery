package com.cctv.discovery.util;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Hardened XML parsing for responses received from devices on the network.
 * <p>
 * Every XML document this tool parses comes from an untrusted peer (including
 * unsolicited WS-Discovery replies), so DOCTYPE declarations and all external
 * entity resolution are disabled.
 */
public final class XmlUtils {

    private static final DocumentBuilderFactory FACTORY = createFactory();

    private XmlUtils() {
    }

    private static DocumentBuilderFactory createFactory() {
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        f.setNamespaceAware(true);
        f.setXIncludeAware(false);
        f.setExpandEntityReferences(false);
        try {
            f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            f.setFeature("http://xml.org/sax/features/external-general-entities", false);
            f.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            f.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        } catch (ParserConfigurationException e) {
            throw new IllegalStateException("XML parser does not support secure processing", e);
        }
        f.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        f.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        return f;
    }

    /**
     * Parse an XML string with DTDs and external entities disabled.
     *
     * @throws Exception if the document is malformed or declares a DOCTYPE
     */
    public static Document parse(String xml) throws Exception {
        DocumentBuilder builder;
        synchronized (FACTORY) {
            builder = FACTORY.newDocumentBuilder();
        }
        builder.setEntityResolver((publicId, systemId) -> {
            throw new SecurityException("External entity blocked: " + systemId);
        });
        builder.setErrorHandler(null);
        return builder.parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
    }

    /** Text of the first element with the given local name anywhere in the node, trimmed; null if absent. */
    public static String text(Node node, String localName) {
        NodeList list = elements(node, localName);
        if (list.getLength() == 0) {
            return null;
        }
        String value = list.item(0).getTextContent();
        return value == null ? null : value.trim();
    }

    /** Elements with the given local name in any namespace. */
    public static NodeList elements(Node node, String localName) {
        if (node instanceof Document doc) {
            return doc.getElementsByTagNameNS("*", localName);
        }
        return ((Element) node).getElementsByTagNameNS("*", localName);
    }

    /** Elements with the given local name as a list. */
    public static List<Element> elementList(Node node, String localName) {
        NodeList list = elements(node, localName);
        List<Element> result = new ArrayList<>(list.getLength());
        for (int i = 0; i < list.getLength(); i++) {
            result.add((Element) list.item(i));
        }
        return result;
    }

    /** Escape text for inclusion in XML element content or attribute values. */
    public static String escape(String text) {
        if (text == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(text.length() + 16);
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '&' -> sb.append("&amp;");
                case '<' -> sb.append("&lt;");
                case '>' -> sb.append("&gt;");
                case '"' -> sb.append("&quot;");
                case '\'' -> sb.append("&apos;");
                default -> sb.append(c);
            }
        }
        return sb.toString();
    }
}
