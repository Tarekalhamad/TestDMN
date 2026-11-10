package com.example.discount.rules;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * The five business-editable decision tables, read back out of the live DMN.
 *
 * <p>This is the only place the rule data is turned into Java. It exists so that
 * {@code GET /api/rules} can publish the rule set and so that the test suite can derive its
 * expectations from the rules that are actually configured, rather than restating them. Nothing
 * here interprets a rule &mdash; that is the DMN's job.
 */
public final class RuleTables {

    public static final String CATEGORY_PRECEDENCE = "CategoryPrecedence";
    public static final String CATEGORY_INCOMPATIBILITY = "CategoryIncompatibility";
    public static final String ITEM_INCOMPATIBILITY = "ItemIncompatibility";
    public static final String CATEGORY_CARDINALITY = "CategoryCardinality";
    public static final String EXCEPTIONS = "Exceptions";

    /** The tables that carry a ruleId, i.e. everything except the precedence lookup. */
    public static final List<String> RULE_TABLES = List.of(
            CATEGORY_INCOMPATIBILITY, ITEM_INCOMPATIBILITY, CATEGORY_CARDINALITY, EXCEPTIONS);

    private final Map<String, List<Map<String, String>>> tables;
    private final String version;

    private RuleTables(Map<String, List<Map<String, String>>> tables, String version) {
        this.tables = tables;
        this.version = version;
    }

    public static RuleTables parse(String dmnXml) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            DocumentBuilder builder = factory.newDocumentBuilder();
            org.w3c.dom.Document doc = builder.parse(
                    new ByteArrayInputStream(dmnXml.getBytes(StandardCharsets.UTF_8)));

            Map<String, List<Map<String, String>>> parsed = new LinkedHashMap<>();
            NodeList decisions = doc.getElementsByTagNameNS("*", "decision");
            for (int i = 0; i < decisions.getLength(); i++) {
                Element decision = (Element) decisions.item(i);
                Element table = firstChild(decision, "decisionTable");
                if (table == null) {
                    continue;
                }
                parsed.put(decision.getAttribute("name"), readRows(table));
            }
            return new RuleTables(parsed, versionOf(dmnXml));
        } catch (Exception e) {
            throw new IllegalArgumentException("Could not read the rule tables: " + e.getMessage(), e);
        }
    }

    private static List<Map<String, String>> readRows(Element table) {
        List<String> columns = new ArrayList<>();
        for (Element output : children(table, "output")) {
            columns.add(output.getAttribute("name"));
        }

        List<Map<String, String>> rows = new ArrayList<>();
        for (Element rule : children(table, "rule")) {
            List<Element> entries = children(rule, "outputEntry");
            Map<String, String> row = new LinkedHashMap<>();
            for (int c = 0; c < columns.size(); c++) {
                String value = c < entries.size() ? unquote(textOf(entries.get(c))) : null;
                row.put(columns.get(c), value);
            }
            List<Element> notes = children(rule, "annotationEntry");
            if (!notes.isEmpty()) {
                String note = textOf(notes.get(0));
                if (note != null && !note.isBlank()) {
                    row.put("note", note.trim());
                }
            }
            rows.add(Collections.unmodifiableMap(row));
        }
        return Collections.unmodifiableList(rows);
    }

    /**
     * A FEEL literal cell becomes the plain value behind it. A cell the rule owner left blank is
     * null whether the editor wrote nothing at all or wrote an empty string literal &mdash; the
     * two must not mean different things.
     */
    private static String unquote(String text) {
        if (text == null) {
            return null;
        }
        String trimmed = text.trim();
        if (trimmed.length() >= 2 && trimmed.startsWith("\"") && trimmed.endsWith("\"")) {
            trimmed = trimmed.substring(1, trimmed.length() - 1);
        }
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static String textOf(Element element) {
        Element text = firstChild(element, "text");
        return text == null ? null : text.getTextContent();
    }

    private static Element firstChild(Element parent, String localName) {
        List<Element> found = children(parent, localName);
        return found.isEmpty() ? null : found.get(0);
    }

    private static List<Element> children(Element parent, String localName) {
        List<Element> found = new ArrayList<>();
        NodeList nodes = parent.getChildNodes();
        for (int i = 0; i < nodes.getLength(); i++) {
            Node node = nodes.item(i);
            if (node.getNodeType() == Node.ELEMENT_NODE && localName.equals(node.getLocalName())) {
                found.add((Element) node);
            }
        }
        return found;
    }

    /**
     * Identifies the rule set that produced a verdict. Hashes the model with its diagram layout
     * stripped, so moving a box around in the editor does not read as a rule change while an
     * edit to a rule or to the evaluation logic does.
     */
    public static String versionOf(String dmnXml) {
        String canonical = dmnXml.replaceAll("(?s)<dmndi:DMNDI>.*?</dmndi:DMNDI>", "")
                .replaceAll("(?s)<!--.*?-->", "")
                .replaceAll("\\s+", " ")
                .trim();
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder("sha256:");
            for (byte b : digest) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    public String version() {
        return version;
    }

    public List<Map<String, String>> table(String decisionName) {
        return tables.getOrDefault(decisionName, List.of());
    }

    public Map<String, List<Map<String, String>>> all() {
        return Collections.unmodifiableMap(tables);
    }

    public List<Map<String, String>> precedence() {
        return table(CATEGORY_PRECEDENCE);
    }

    public List<Map<String, String>> categoryRules() {
        return table(CATEGORY_INCOMPATIBILITY);
    }

    public List<Map<String, String>> itemRules() {
        return table(ITEM_INCOMPATIBILITY);
    }

    public List<Map<String, String>> cardinalityRules() {
        return table(CATEGORY_CARDINALITY);
    }

    public List<Map<String, String>> exceptions() {
        return table(EXCEPTIONS);
    }

    /** Every rule id in the four rule-carrying tables, in table order. */
    public List<String> allRuleIds() {
        List<String> ids = new ArrayList<>();
        for (String name : RULE_TABLES) {
            for (Map<String, String> row : table(name)) {
                ids.add(row.get("ruleId"));
            }
        }
        return ids;
    }

    /** Rank for a category, or empty when the category is not in the precedence table. */
    public java.util.OptionalInt rankOf(String discountCategory) {
        for (Map<String, String> row : precedence()) {
            if (discountCategory != null && discountCategory.equals(row.get("discountCategory"))) {
                try {
                    return java.util.OptionalInt.of(Integer.parseInt(row.get("precedenceRank")));
                } catch (NumberFormatException e) {
                    return java.util.OptionalInt.empty();
                }
            }
        }
        return java.util.OptionalInt.empty();
    }

    /** The categories the precedence table ranks, best first. */
    public List<String> rankedCategories() {
        List<Map<String, String>> rows = new ArrayList<>(precedence());
        rows.sort((a, b) -> Integer.compare(
                Integer.parseInt(a.getOrDefault("precedenceRank", "999")),
                Integer.parseInt(b.getOrDefault("precedenceRank", "999"))));
        List<String> categories = new ArrayList<>();
        for (Map<String, String> row : rows) {
            categories.add(row.get("discountCategory"));
        }
        return categories;
    }
}
