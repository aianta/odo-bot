package ca.ualberta.odobot.common;
import org.jsoup.nodes.Attribute;
import org.jsoup.nodes.Attributes;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.util.*;
import java.util.regex.Pattern;

/**
 * ChatGPT 5.2-pro generated Java port of Robula+ written by Maurizio Leotta, Andrea Stocco, Filippo Ricca and Paolo Tonella.
 *
 * Original Robula Plus project: https://github.com/cyluxx/robula-plus
 */

public class RobulaPlus {

    public static final class RobulaPlusOptions {
        public List<String> attributePrioritizationList =
                new ArrayList<>(List.of("name", "class", "title", "alt", "value"));

        public List<String> attributeBlackList =
                new ArrayList<>(List.of("href", "src", "onclick", "onload", "tabindex",
                        "width", "height", "style", "size", "maxlength"));
    }

    private final List<String> attributePrioritizationList;
    private final Set<String> attributeBlackList;

    public RobulaPlus() {
        this(new RobulaPlusOptions());
    }

    public RobulaPlus(RobulaPlusOptions options) {
        this.attributePrioritizationList = new ArrayList<>(options.attributePrioritizationList);
        this.attributeBlackList = new HashSet<>();
        for (String s : options.attributeBlackList) this.attributeBlackList.add(s.toLowerCase(Locale.ROOT));
    }

    /**
     * Returns an optimized robust XPath locator string for the given element in the given document.
     */
    public String getRobustXPath(Element element, Document document) {
        if (element == null || document == null) throw new IllegalArgumentException("element/document must not be null");

        // Rough equivalent of: document.body.contains(element)
        // (works even if element is outside body, e.g. in head).
        if (element.ownerDocument() != document) {
            throw new IllegalArgumentException("Document does not contain given element!");
        }

        ArrayDeque<XPathExpr> queue = new ArrayDeque<>();
        queue.add(new XPathExpr("//*"));

        // Optional: global visited set to avoid infinite re-adding.
        // The original TS only de-dupes per expansion step; this is safer in Java.
        HashSet<XPathExpr> visited = new HashSet<>();
        visited.add(new XPathExpr("//*"));

        while (!queue.isEmpty()) {
            XPathExpr xPath = queue.removeFirst();

            LinkedHashSet<XPathExpr> next = new LinkedHashSet<>();
            next.addAll(transfConvertStar(xPath, element));
            next.addAll(transfAddId(xPath, element));
            next.addAll(transfAddText(xPath, element));
            next.addAll(transfAddAttribute(xPath, element));
            next.addAll(transfAddAttributeSet(xPath, element));
            next.addAll(transfAddPosition(xPath, element));
            next.addAll(transfAddLevel(xPath, element));

            for (XPathExpr candidate : next) {
                if (uniquelyLocate(candidate.getValue(), element, document)) {
                    return candidate.getValue();
                }
                if (visited.add(candidate)) {
                    queue.addLast(candidate);
                }
            }
        }

        throw new IllegalStateException("Internal Error: exhausted search without finding a unique XPath");
    }

    /**
     * Returns the first matching element located by the given XPath locator.
     */
    public Element getElementByXPath(String xPath, Document document) {
        Elements matches = document.selectXpath(xPath);
        return matches.isEmpty() ? null : matches.first();
    }

    /**
     * True iff the XPath describes only the given element.
     */
    public boolean uniquelyLocate(String xPath, Element element, Document document) {
        Elements matches;
        try {
            matches = document.selectXpath(xPath);
        } catch (RuntimeException ex) {
            // If jsoup's XPath engine rejects an expression, treat as "not uniquely locating".
            return false;
        }
        return matches.size() == 1 && matches.get(0) == element;
    }

    // -----------------------
    // Transformations
    // -----------------------

    private List<XPathExpr> transfConvertStar(XPathExpr xPath, Element element) {
        ArrayList<XPathExpr> out = new ArrayList<>();
        Element ancestor = getAncestor(element, xPath.getLength() - 1);

        if (xPath.startsWith("//*")) {
            // "//*" is 3 chars; replace head star with actual tag
            String tag = ancestor.normalName(); // lower-cased
            out.add(new XPathExpr("//" + tag + xPath.substring(3)));
        }
        return out;
    }

    private List<XPathExpr> transfAddId(XPathExpr xPath, Element element) {
        ArrayList<XPathExpr> out = new ArrayList<>();
        Element ancestor = getAncestor(element, xPath.getLength() - 1);

        String id = ancestor.id();
        if (id != null && !id.isEmpty() && !xPath.headHasAnyPredicates()) {
            XPathExpr nx = new XPathExpr(xPath.getValue());
            nx.addPredicateToHead("[@id=" + xpathLiteral(id) + "]");
            out.add(nx);
        }
        return out;
    }

    private List<XPathExpr> transfAddText(XPathExpr xPath, Element element) {
        ArrayList<XPathExpr> out = new ArrayList<>();
        Element ancestor = getAncestor(element, xPath.getLength() - 1);

        String text = ancestor.text(); // similar spirit to textContent (descendant text)
        if (text != null) text = text.trim();

        if (text != null && !text.isEmpty()
                && !xPath.headHasPositionPredicate()
                && !xPath.headHasTextPredicate()) {

            XPathExpr nx = new XPathExpr(xPath.getValue());
            nx.addPredicateToHead("[contains(text()," + xpathLiteral(text) + ")]");
            out.add(nx);
        }
        return out;
    }

    private List<XPathExpr> transfAddAttribute(XPathExpr xPath, Element element) {
        ArrayList<XPathExpr> out = new ArrayList<>();
        Element ancestor = getAncestor(element, xPath.getLength() - 1);

        if (!xPath.headHasAnyPredicates()) {
            Attributes attrs = ancestor.attributes();

            // Priority attributes first (in list order), at most one per priority name.
            for (String priorityName : attributePrioritizationList) {
                String p = priorityName.toLowerCase(Locale.ROOT);
                for (Attribute a : attrs) {
                    String key = a.getKey().toLowerCase(Locale.ROOT);
                    if (key.equals(p)) {
                        XPathExpr nx = new XPathExpr(xPath.getValue());
                        nx.addPredicateToHead("[@" + a.getKey() + "=" + xpathLiteral(a.getValue()) + "]");
                        out.add(nx);
                        break;
                    }
                }
            }

            // Then all other non-blacklist, non-priority attributes
            for (Attribute a : attrs) {
                String key = a.getKey().toLowerCase(Locale.ROOT);
                if (!attributeBlackList.contains(key) && !containsIgnoreCase(attributePrioritizationList, key)) {
                    XPathExpr nx = new XPathExpr(xPath.getValue());
                    nx.addPredicateToHead("[@" + a.getKey() + "=" + xpathLiteral(a.getValue()) + "]");
                    out.add(nx);
                }
            }
        }
        return out;
    }

    private List<XPathExpr> transfAddAttributeSet(XPathExpr xPath, Element element) {
        ArrayList<XPathExpr> out = new ArrayList<>();
        Element ancestor = getAncestor(element, xPath.getLength() - 1);

        if (!xPath.headHasAnyPredicates()) {
            // Build attributes list excluding blacklist
            ArrayList<Attribute> attrs = new ArrayList<>();
            for (Attribute a : ancestor.attributes()) {
                String key = a.getKey().toLowerCase(Locale.ROOT);
                if (!attributeBlackList.contains(key)) {
                    attrs.add(a);
                }
            }

            // Generate power set
            List<List<Attribute>> power = generatePowerSet(attrs);

            // Remove sets with cardinality < 2
            power.removeIf(set -> set.size() < 2);

            // Comparator that mimics TS intent; treat "id" as highest priority for this transformation
            Comparator<Attribute> attrCmp = (a1, a2) -> elementCompareFunctionWithIdPriority(a1, a2);

            // Sort elements inside each subset
            for (List<Attribute> set : power) {
                set.sort(attrCmp); // Java sort is stable
            }

            // Sort the power set: size, then lexicographic by elementCompareFunction
            power.sort((s1, s2) -> {
                if (s1.size() != s2.size()) return Integer.compare(s1.size(), s2.size());
                for (int i = 0; i < s1.size(); i++) {
                    Attribute a = s1.get(i);
                    Attribute b = s2.get(i);
                    if (!attrKeyValEquals(a, b)) {
                        int c = attrCmp.compare(a, b);
                        if (c != 0) return c;
                        // If comparator considers them equal, fall back to deterministic ordering
                        c = a.getKey().compareTo(b.getKey());
                        if (c != 0) return c;
                        c = a.getValue().compareTo(b.getValue());
                        if (c != 0) return c;
                    }
                }
                return 0;
            });

            // Convert to predicate
            for (List<Attribute> set : power) {
                StringBuilder predicate = new StringBuilder();
                predicate.append("[@").append(set.get(0).getKey())
                        .append("=").append(xpathLiteral(set.get(0).getValue()));

                for (int i = 1; i < set.size(); i++) {
                    predicate.append(" and @").append(set.get(i).getKey())
                            .append("=").append(xpathLiteral(set.get(i).getValue()));
                }
                predicate.append("]");

                XPathExpr nx = new XPathExpr(xPath.getValue());
                nx.addPredicateToHead(predicate.toString());
                out.add(nx);
            }
        }
        return out;
    }

    private List<XPathExpr> transfAddPosition(XPathExpr xPath, Element element) {
        ArrayList<XPathExpr> out = new ArrayList<>();
        Element ancestor = getAncestor(element, xPath.getLength() - 1);

        if (xPath.headHasPositionPredicate()) return out;

        Element parent = ancestor.parent();
        if (parent == null) return out;

        int position = 1;

        if (xPath.startsWith("//*")) {
            // position among all element children
            position = ancestor.elementSiblingIndex() + 1; // 1-based
        } else {
            // position among siblings with same tag name
            String tag = ancestor.normalName();
            for (Element child : parent.children()) {
                if (child == ancestor) break;
                if (tag.equals(child.normalName())) position++;
            }
        }

        XPathExpr nx = new XPathExpr(xPath.getValue());
        nx.addPredicateToHead("[" + position + "]");
        out.add(nx);

        return out;
    }

    private List<XPathExpr> transfAddLevel(XPathExpr xPath, Element element) {
        ArrayList<XPathExpr> out = new ArrayList<>();
        if (xPath.getLength() - 1 < getAncestorCount(element)) {
            out.add(new XPathExpr("//*" + xPath.substring(1)));
        }
        return out;
    }

    // -----------------------
    // Helpers (ported)
    // -----------------------

    private static List<List<Attribute>> generatePowerSet(List<Attribute> input) {
        List<List<Attribute>> subsets = new ArrayList<>();
        subsets.add(new ArrayList<>());

        for (Attribute value : input) {
            int size = subsets.size();
            for (int i = 0; i < size; i++) {
                List<Attribute> existing = subsets.get(i);
                ArrayList<Attribute> added = new ArrayList<>(existing.size() + 1);
                added.add(value);       // [value, ...set]
                added.addAll(existing);
                subsets.add(added);
            }
        }
        return subsets;
    }

    private int elementCompareFunctionWithIdPriority(Attribute a1, Attribute a2) {
        String n1 = a1.getKey().toLowerCase(Locale.ROOT);
        String n2 = a2.getKey().toLowerCase(Locale.ROOT);

        // Behave like "attributePriorizationList.unshift('id')"
        if (n1.equals("id") && !n2.equals("id")) return -1;
        if (n2.equals("id") && !n1.equals("id")) return 1;

        for (String p : attributePrioritizationList) {
            String pr = p.toLowerCase(Locale.ROOT);
            if (pr.equals(n1)) return -1;
            if (pr.equals(n2)) return 1;
        }
        return 0;
    }

    private static Element getAncestor(Element element, int index) {
        Element out = element;
        for (int i = 0; i < index; i++) {
            out = out.parent();
            if (out == null) {
                throw new IllegalArgumentException("Requested ancestor above document root");
            }
        }
        return out;
    }

    private static int getAncestorCount(Element element) {
        int count = 0;
        Element cur = element;
        while (cur.parent() != null) {
            cur = cur.parent();
            count++;
        }
        return count;
    }

    private static boolean containsIgnoreCase(List<String> list, String lowerCaseNeedle) {
        for (String s : list) {
            if (s != null && s.toLowerCase(Locale.ROOT).equals(lowerCaseNeedle)) return true;
        }
        return false;
    }

    private static boolean attrKeyValEquals(Attribute a, Attribute b) {
        return a.getKey().equals(b.getKey()) && a.getValue().equals(b.getValue());
    }

    /**
     * Escapes a Java string as an XPath 1.0 string literal.
     * - If it contains no single quotes:  'text'
     * - If it contains single quotes:     concat('a', "'", 'b', ...)
     */
    private static String xpathLiteral(String s) {
        if (s == null) return "''";
        if (!s.contains("'")) {
            return "'" + s + "'";
        }
        // concat('foo', "'", 'bar')
        String[] parts = s.split("'", -1);
        StringBuilder sb = new StringBuilder();
        sb.append("concat(");
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) sb.append(", \"'\", ");
            sb.append("'").append(parts[i]).append("'");
        }
        sb.append(")");
        return sb.toString();
    }

    // -----------------------
    // XPathExpr value object (ported XPath class)
    // -----------------------

    public static final class XPathExpr {
        private static final Pattern POSITION_LITERAL = Pattern.compile("\\[\\d+\\]");
        private String value;

        public XPathExpr(String value) {
            this.value = Objects.requireNonNull(value, "value");
        }

        public String getValue() {
            return value;
        }

        public boolean startsWith(String prefix) {
            return value.startsWith(prefix);
        }

        public String substring(int beginIndex) {
            return value.substring(beginIndex);
        }

        public int getLength() {
            String[] split = value.split("/");
            int length = 0;
            for (String piece : split) {
                if (!piece.isEmpty()) length++;
            }
            return length;
        }

        public boolean headHasAnyPredicates() {
            String head = headSegment();
            return head.contains("[");
        }

        public boolean headHasPositionPredicate() {
            String head = headSegment();
            return head.contains("position()") || head.contains("last()") || POSITION_LITERAL.matcher(head).find();
        }

        public boolean headHasTextPredicate() {
            String head = headSegment();
            return head.contains("text()");
        }

        public void addPredicateToHead(String predicate) {
            String[] split = value.split("/", -1);
            // For //tag... split is ["", "", "tag..."]
            if (split.length < 3) throw new IllegalStateException("Unexpected XPath shape: " + value);
            split[2] = split[2] + predicate;
            value = String.join("/", split);
        }

        private String headSegment() {
            String[] split = value.split("/", -1);
            if (split.length < 3) return "";
            return split[2];
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof XPathExpr)) return false;
            XPathExpr xPathExpr = (XPathExpr) o;
            return value.equals(xPathExpr.value);
        }

        @Override
        public int hashCode() {
            return value.hashCode();
        }

        @Override
        public String toString() {
            return value;
        }
    }
}