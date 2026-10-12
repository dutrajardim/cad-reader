package br.gov.mg.sids.reader;

import java.io.*;
import java.util.*;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.stream.*;
import javax.xml.transform.*;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import org.w3c.dom.*;

/** Utilitários XML: preservam namespaces, escapam valores e consultam campos CAD. */
final class CadXml {
    static final String CAD = "http://sids.mg.gov.br/cad";
    private CadXml() {
    }

    /** Constrói uma árvore apenas para o elemento atual, não para a sessão inteira. */
    static Element readElement(XMLStreamReader parser) throws Exception {
        Document document = DocumentBuilderFactory.newInstance().newDocumentBuilder().newDocument();
        Element element = readElement(parser, document, 0);
        document.appendChild(element);
        return element;
    }

    /** Lê recursivamente até a tag de fechamento e limita a profundidade da árvore. */
    private static Element readElement(XMLStreamReader parser, Document document, int depth)
        throws Exception {
        if (depth > 128)
            throw new IOException("XML excedeu profundidade máxima de 128 elementos");
        Element element = document.createElementNS(
            parser.getNamespaceURI(), qualified(parser.getPrefix(), parser.getLocalName()));
        for (int i = 0; i < parser.getAttributeCount(); i++)
            element.setAttributeNS(parser.getAttributeNamespace(i),
                qualified(parser.getAttributePrefix(i), parser.getAttributeLocalName(i)),
                parser.getAttributeValue(i));
        while (parser.hasNext()) {
            int event = parser.next();
            if (event == XMLStreamConstants.START_ELEMENT)
                element.appendChild(readElement(parser, document, depth + 1));
            else if (event == XMLStreamConstants.END_ELEMENT)
                return element;
            else if (event == XMLStreamConstants.CHARACTERS || event == XMLStreamConstants.CDATA)
                element.appendChild(document.createTextNode(parser.getText()));
            else if (event == XMLStreamConstants.DTD)
                throw new IOException("DTD não permitido");
        }
        throw new IOException("XML incompleto");
    }

    /** Reconstitui prefixo:nome, inclusive quando a tag não tem prefixo. */
    private static String qualified(String prefix, String local) {
        return prefix == null || prefix.isEmpty() ? local : prefix + ":" + local;
    }

    /** Serializa um elemento para diagnóstico; não inclui a declaração de documento. */
    static String serialize(Element element) throws TransformerException {
        Transformer transformer = TransformerFactory.newInstance().newTransformer();
        transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes");
        StringWriter result = new StringWriter();
        transformer.transform(new DOMSource(element), new StreamResult(result));
        return result.toString();
    }

    /** Busca descendente por namespace e nome; prefixos como c: não afetam a busca. */
    static Element first(Element parent, String namespace, String tag) {
        NodeList nodes = parent.getElementsByTagNameNS(namespace, tag);
        return nodes.getLength() == 0 ? null : (Element) nodes.item(0);
    }

    /** Verifica se uma feature ou um campo está presente. */
    static boolean has(Element parent, String namespace, String tag) {
        return first(parent, namespace, tag) != null;
    }

    /** Lista os textos de elementos, usada para mecanismos SASL e métodos zlib. */
    static List<String> values(Element parent, String namespace, String tag) {
        List<String> result = new ArrayList<>();
        NodeList nodes = parent.getElementsByTagNameNS(namespace, tag);
        for (int i = 0; i < nodes.getLength(); i++)
            result.add(nodes.item(i).getTextContent().trim());
        return result;
    }

    /** Lista descendentes CAD com um nome específico, por exemplo resumoRecurso. */
    static List<Element> elements(Element parent, String tag) {
        NodeList nodes = parent.getElementsByTagNameNS(CAD, tag);
        List<Element> result = new ArrayList<>();
        for (int i = 0; i < nodes.getLength(); i++)
            result.add((Element) nodes.item(i));
        return result;
    }

    /**
     * Lê os filhos diretos de uma coleção CAD. O marshaller original usa a tag
     * genérica {@code i} para cada item; não percorremos itens de listas aninhadas.
     * Também aceita a tag da entidade para compatibilidade com XML de diagnóstico.
     */
    static List<Element> collectionItems(Element parent, String collection, String entityTag) {
        List<Element> result = new ArrayList<>();
        for (Node node = parent.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (!(node instanceof Element) || !CAD.equals(node.getNamespaceURI())
                || !collection.equals(node.getLocalName()))
                continue;
            for (Node item = node.getFirstChild(); item != null; item = item.getNextSibling()) {
                if (item instanceof Element && CAD.equals(item.getNamespaceURI())
                    && ("i".equals(item.getLocalName()) || entityTag.equals(item.getLocalName())))
                    result.add((Element) item);
            }
        }
        return result;
    }

    /** Retorna somente o texto de um filho direto CAD, sem confundir entidades aninhadas. */
    static String text(Element parent, String tag) {
        for (Node child = parent.getFirstChild(); child != null; child = child.getNextSibling())
            if (child instanceof Element && CAD.equals(child.getNamespaceURI())
                && tag.equals(child.getLocalName()))
                return child.getTextContent().trim();
        return "";
    }

    /** Consulta um campo dentro de uma entidade, por exemplo unidadeServico/descricao. */
    static String nestedText(Element parent, String entity, String tag) {
        Element nested = first(parent, CAD, entity);
        return nested == null ? "" : text(nested, tag);
    }

    /** Escapa valores, que nunca devem ser concatenados como marcação XML. */
    static String escape(String text) {
        return text.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;");
    }

    /** Monta um campo simples. Nomes de tags são constantes internas do programa. */
    static String field(String tag, String value) {
        return "<" + tag + ">" + escape(value) + "</" + tag + ">";
    }
}
