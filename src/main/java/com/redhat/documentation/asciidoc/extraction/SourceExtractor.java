package com.redhat.documentation.asciidoc.extraction;

import java.util.StringJoiner;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.asciidoctor.ast.Block;
import org.asciidoctor.ast.ContentNode;
import org.asciidoctor.ast.DescriptionList;
import org.asciidoctor.ast.List;
import org.asciidoctor.ast.ListItem;
import org.asciidoctor.ast.Section;
import org.asciidoctor.ast.StructuralNode;
import org.asciidoctor.ast.Table;

public class SourceExtractor {
    private ContentNode node;
    private StringBuilder source;

    enum BlockType {
        OPEN("--"),
        QUOTE("____"),
        VERSE("____"),
        SIDEBAR("****"),
        FENCED("```"),
        LITERAL("...."),
        PASSTHROUGH("++++"),
        STEM("++++"),
        ADMONITION("===="),
        EXAMPLE("===="),
        SOURCE("----"),
        PARAGRAPH(""),
        LISTING("----");

        private String delimiter;

        BlockType(String delimiter) {
            this.delimiter = delimiter;
        }

        public String delimiter() {
            return this.delimiter;
        }
    }

    public SourceExtractor(ContentNode node) {
        this.node = node;
        this.source = new StringBuilder();

        extractMetadata();

        if (node instanceof Section)
            extractSection();

        if (node instanceof Block)
            extractBlock();

        if (node instanceof Table)
            extractTable();

        if (node instanceof List)
            extractList();

        if (node instanceof DescriptionList)
            extractDescriptionList();
    }

    public String getSource() {
        return source.toString();
    }

    private void extractSection() {
        var section = (Section) node;
        var id = section.getId();

        // If there isn't an explicit id, it starts with an _
        if (id.startsWith("_")) {
            // Don't use the first character (an underscore) and replace underscore with hyphen
            id = id.substring(1).replaceAll("_", "-");
        }
        var context = id;
        var title = section.getTitle();
        var level = section.getLevel() < 2 ? 1 : section.getLevel() - 1;

        source
                // Metadata first
                .append("\n")
                .append("=".repeat(Math.max(0, level))).append(" ").append(title)
                .append("\n")
                .append(":context: ").append(context);
    }

    private void extractBlock() {
        var block = (Block) node;

        // the metadata comes first and does not end in a new line
        if (source.length() > 0)
            source.append("\n");

        // depending on the type of block, there are different delimiters
        // This should work for all different types of blocks
        var delimiter = BlockType.valueOf(block.getContext().toUpperCase()).delimiter();

        // This is mostly for paragraphs which have no delimiter
        // and so they don't need a new line before or after the delimiter
        var joiner = (delimiter.isEmpty()) ? new StringJoiner("")
                                           : new StringJoiner("", delimiter + "\n", "\n" + delimiter);

        if ("compound".equals(block.getContentModel())) {
            for (StructuralNode innerBlock : block.getBlocks()) {
                joiner.add(new SourceExtractor(innerBlock).getSource());
            }
        }

        source.append(joiner.add(block.getSource()));
    }

    private void extractList() {
        var list = (List) node;
        var listItemJoiner = new StringJoiner("\n");
        boolean nested = false;

        // Nested lists, should also work if a list has more than just a basic text for an item
        var items = list.getItems();
        for (int i = 0; i < items.size(); i++) {
            StructuralNode structuralNode = items.get(i);
            var item = (ListItem) structuralNode;
            String marker = item.getMarker();

            // If the marker is more than one character and it doesn't start with a number
            // then this should be a nested list, and is not a callout list
            if (item.getMarker().length() > 1
                && !item.getMarker().matches("^\\d+\\.") && !"colist".equals(list.getContext())) {
                nested = true;
            }

            // TODO: fix up callouts so they have the correct number
            if ("olist".equals(list.getContext()) && !nested)
                marker = ".";

            if ("colist".equals(list.getContext()) && !nested) {
                marker = marker.replaceAll("\\d+", String.valueOf(i + 1));
            }

            if (item.getBlocks() != null && item.getBlocks().size() > 0) {
                // We need the item that has the nested list or block as well
                StringBuilder itemSource = new StringBuilder(item.getSource());

                // Get all the blocks or nested lists
                item.getBlocks().forEach(listItemBlock -> {
                    itemSource.append("\n");

                    // Need to attach the block to the list
                    // However, nested lists don't need the "+"
                    if (!((listItemBlock instanceof ListItem) || (listItemBlock instanceof List)))
                        itemSource.append("+\n");

                    itemSource.append(new SourceExtractor(listItemBlock).getSource());
                });

                // Now add list/blocks to the main list
                listItemJoiner.add(marker + " " + itemSource);
            } else { // Just a normal list item, nothing special
                listItemJoiner.add(marker + " " + item.getSource());
            }
        }

        // new line after the metadata, but only if there is metadata
        if (source.length() > 0)
            source.append("\n");

        // Add the title of the list, if there is one
        if (list.getTitle() != null)
            source.append(".").append(list.getTitle()).append("\n");

        source.append(listItemJoiner.toString());
    }

    private void extractTable() {
        var table = (Table) node;
        var delimiter = "|===";

        var bodyJoiner = new StringJoiner("\n| ", "\n| ", "");

        // We need to do a depth first join to make sure it lays out correctly
        // TODO: what about a nested table?
        table.getBody().forEach(row -> {
            var cellJoiner = new StringJoiner(" | ");
            row.getCells().forEach(cell -> cellJoiner.add(cell.getSource()));
            bodyJoiner.merge(cellJoiner);
        });

        source
                // New line for after the metadata
                .append("\n")
                .append(delimiter)
                // Now the actual body
                .append(bodyJoiner)
                .append("\n")
                .append(delimiter);
    }

    private void extractDescriptionList() {
        var descriptionList = (DescriptionList) node;

        for (var listEntryIter = descriptionList.getItems().iterator(); listEntryIter.hasNext(); ) {
            var entry = listEntryIter.next();// each term
            // It is easier to use an iterator to know if we need a space or new line at the end
            for (var termIter = entry.getTerms().iterator(); termIter.hasNext(); ) {
                var term = termIter.next();

                if (term.getBlocks().isEmpty())
                    source.append(term.getSource());
                else
                    term.getBlocks().forEach(block -> source.append(new SourceExtractor(block).getSource()));

                source.append("::");
                if (termIter.hasNext())
                    source.append("\n");
            }

            // description
            final var description = entry.getDescription();
            if (description.getBlocks().isEmpty()) {
                source.append(" ");
                source.append(description.getSource());
            } else {
                source.append("\n+\n");
                description.getBlocks().forEach(block -> source.append(new SourceExtractor(block).getSource()));
            }

            if (listEntryIter.hasNext())
                source.append("\n");
        }
    }

    private boolean hasRoles() {
        return !node.getRoles().isEmpty();
    }

    private boolean hasAttributes() {
        return !node.getAttributes().isEmpty();
    }

    private boolean hasStyle() {
        return (node instanceof StructuralNode) && ((StructuralNode) node).getStyle() != null;
    }

    private boolean hasTitle() {
        // Section and List titles need to be handled differently
        // True if node is not a Section or List and has a non-null title
        return (node instanceof StructuralNode && !((node instanceof Section) || (node instanceof List)))
                && ((StructuralNode) node).getTitle() != null;
    }

    /**
     * Pulls the id, attributes, style, and roles from the node into the proper syntax
     */
    private void extractMetadata() {
        var id = node.getId();
        var hasId = id != null;
        var hasAttributes = hasAttributes();
        var hasRoles = hasRoles();
        var hasStyle = hasStyle();
        var hasTitle = hasTitle();

        // If the node has any of these (id, roles, attributes, style, or title)
        // We need to handle it so it is above the node in the output
        if (hasId || hasRoles || hasAttributes || hasStyle || hasTitle) {

            // Titles could be an actual title (.Some title) above the node
            // or it could also be an attribute (though you rarely see that)
            if ((hasAttributes && node.getAttributes().containsKey("title")) || hasTitle) {
                if (hasTitle)
                    source.append(".").append(((StructuralNode) node).getTitle()).append("\n");
                else
                    source.append(".").append(node.getAttribute("title")).append("\n");
            }

            // Now we're into the id, role, style, attributes which will surrounded by [ and ]
            var mainJoiner = new StringJoiner(" ", "[", "]");

            if (hasId) {
                // If there isn't an explicit id for a section, it starts with an _
                if (id.startsWith("_")) {
                    // Don't use the first character (an underscore) and replace underscore with hyphen
                    id = id.substring(1).replaceAll("_", "-");
                }
                var idWithoutContext = id.split("_")[0];
                mainJoiner.add("id=\"" + idWithoutContext + "_{context}\"");
            }

            // Style, roles, and attributes are separated by a ',' and not a ' '
            var joiner = new StringJoiner(",", " ", "");

            if (hasStyle) {
                joiner.add(((StructuralNode) node).getStyle());

                var blockAttributes = node.getAttributes();

                // You typically see source and language together, so we'll put them together as well.
                if (blockAttributes.containsKey("language"))
                    joiner.add(node.getAttribute("language").toString());
                // Sometimes the language will be a positional attribute
                else if ("source".equals(blockAttributes.get("1")) && blockAttributes.containsKey("2")) {
                    joiner.add(blockAttributes.get("2").toString());
                }
            }

            if (hasRoles)
                joiner.add("role=\"" + String.join(",", node.getRoles()) + "\"");

            if (hasAttributes) {
                // We have already added some of the attributes above, we don't need to add them twice.
                var keys = new java.util.HashSet<>(node.getAttributes().keySet());
                keys.remove("style");
                keys.remove("title");
                keys.remove("language");

                keys.stream()
                        // We don't really need the positional ones as they'll be listed as named (I think)
                        .filter(s -> s.matches("\\D+.*"))
                        .forEach(key -> joiner.add(key + "=\"" + node.getAttribute(key).toString() + "\""));
            }

            // Join everything together
            source.append(mainJoiner.merge(joiner).toString());
        }
    }
}
