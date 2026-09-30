package org.hibernate.migration.recipes.xml;

import org.openrewrite.xml.tree.Xml;
import org.xml.sax.InputSource;
import org.xml.sax.helpers.DefaultHandler;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.StringReader;

/// Decodes XML values without external entities and escapes generated text exactly once.
/// @author Steve Ebersole
public final class XmlText {
    private XmlText() {}

    public static String attribute(String raw) {
        return parse("<v a=\"" + raw.replace("\"", "&quot;") + "\"/>", true);
    }

    public static String text(Xml.Tag tag) {
        StringBuilder raw = new StringBuilder();
        if (tag.getContent() != null) {
            for (var content : tag.getContent()) {
                if (content instanceof Xml.CharData data) {
                    raw.append(data.getPrefix());
                    raw.append(data.isCdata() ? escape(data.getText()) : data.getText());
                    raw.append(data.getAfterText());
                }
                else if (content instanceof Xml.Comment || content instanceof Xml.ProcessingInstruction) raw.append(content.getPrefix());
                else return null;
            }
        }
        if (tag.getClosing() != null) raw.append(tag.getClosing().getPrefix());
        return parse("<v>" + raw + "</v>", false);
    }

    public static String characters(String raw) {
        return parse("<v>" + raw + "</v>", false);
    }

    private static String parse(String xml, boolean attribute) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            var builder = factory.newDocumentBuilder();
            builder.setErrorHandler(new DefaultHandler());
            var root = builder.parse(new InputSource(new StringReader(xml))).getDocumentElement();
            return attribute ? root.getAttribute("a") : root.getTextContent();
        }
        catch (Exception e) { return null; }
    }

    public static String escape(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\r", "&#13;");
    }
}
