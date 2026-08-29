package io.keydra.keys.service;

import io.keydra.keys.dto.NamespaceNode;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Groups keys into one level of a namespace tree.
 *
 * <p>Pure function over key names: given the prefix already expanded, it returns the immediate
 * children. The tree is built a level at a time rather than all at once, because a keyspace large
 * enough to need a tree is also too large to materialise as one.
 */
@ApplicationScoped
public class NamespaceTreeBuilder {

    /** Mutable tally used while grouping, converted to the immutable DTO at the end. */
    private static final class Tally {

        private long keys;
        private boolean hasChildren;
    }

    /**
     * Returns the immediate children of {@code prefix}.
     *
     * <p>A key that has no further delimiter beyond the prefix is a leaf and is not reported as a
     * node — the caller lists those as keys, not folders.
     *
     * @param keys key names; nothing else about a key is needed to place it in the tree
     * @param prefix already-expanded prefix including its trailing delimiter, empty for the root
     * @param delimiter namespace separator, conventionally {@code :}
     * @param partial whether {@code keys} is everything under the prefix or only as much as the
     *     caller was willing to walk. Carried onto every node rather than worked out per node: the
     *     scan stopped at some point in one arbitrary cursor order, so what was cut short is not
     *     one branch but all of them, and a node that happens to be under the ceiling is no more
     *     complete than the one that reached it.
     */
    public List<NamespaceNode> children(
            List<String> keys, String prefix, String delimiter, boolean partial) {
        Map<String, Tally> tallies = new LinkedHashMap<>();

        for (String name : keys) {
            // Offset-based indexOf rather than substring().indexOf(): at a million keys the
            // discarded substrings are the difference between a scan and a garbage-collection
            // storm. A cut at or before the prefix means no segment here — either the key is
            // outside the prefix, or it is a leaf at this level, which the caller lists as a key
            // rather than a folder.
            int cut = name.startsWith(prefix) ? name.indexOf(delimiter, prefix.length()) : -1;
            if (cut <= prefix.length()) {
                continue;
            }
            String segment = name.substring(prefix.length(), cut);
            Tally tally = tallies.computeIfAbsent(segment, ignored -> new Tally());
            tally.keys++;
            // Anything after this segment's delimiter means another level exists.
            tally.hasChildren |= name.indexOf(delimiter, cut + delimiter.length()) > 0;
        }

        List<NamespaceNode> nodes = new ArrayList<>(tallies.size());
        tallies.forEach(
                (segment, tally) ->
                        nodes.add(
                                new NamespaceNode(
                                        segment,
                                        prefix + segment + delimiter,
                                        tally.keys,
                                        tally.hasChildren,
                                        partial)));
        nodes.sort(Comparator.comparing(NamespaceNode::name));
        return nodes;
    }
}
