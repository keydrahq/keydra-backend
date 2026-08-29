package io.keydra.keys.service;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;

import io.keydra.keys.dto.NamespaceNode;
import java.util.List;
import org.junit.jupiter.api.Test;

class NamespaceTreeBuilderTest {

    private final NamespaceTreeBuilder builder = new NamespaceTreeBuilder();

    /** The tree is built from names alone, so that is all a case needs to supply. */
    private static List<String> keys(String... names) {
        return List.of(names);
    }

    @Test
    void groupsTheFirstSegmentAtTheRoot() {
        List<NamespaceNode> nodes =
                builder.children(
                        keys("user:1:profile", "user:2:profile", "cache:page:1", "plain"),
                        "",
                        ":",
                        false);

        assertThat(nodes.stream().map(NamespaceNode::name).toList(), contains("cache", "user"));
        assertThat(nodes.get(1).keyCount(), equalTo(2L));
        assertThat(nodes.get(1).prefix(), equalTo("user:"));
    }

    @Test
    void treatsAKeyWithoutAFurtherDelimiterAsALeaf() {
        // "plain" and "user:1" are keys, not folders, so neither becomes a node here.
        List<NamespaceNode> nodes = builder.children(keys("plain", "user:1"), "", ":", false);

        assertThat(nodes, hasSize(1));
        assertThat(nodes.get(0).name(), equalTo("user"));
        assertThat(nodes.get(0).hasChildren(), equalTo(false));
    }

    @Test
    void reportsWhetherAnotherLevelExists() {
        List<NamespaceNode> nodes = builder.children(keys("a:b:c", "d:e"), "", ":", false);

        assertThat(nodes.get(0).name(), equalTo("a"));
        assertThat(nodes.get(0).hasChildren(), equalTo(true));
        assertThat(nodes.get(1).name(), equalTo("d"));
        assertThat(nodes.get(1).hasChildren(), equalTo(false));
    }

    @Test
    void descendsBelowAnExpandedPrefix() {
        List<NamespaceNode> nodes =
                builder.children(
                        keys("user:1:profile", "user:1:settings", "user:2:profile"),
                        "user:",
                        ":",
                        false);

        assertThat(nodes.stream().map(NamespaceNode::name).toList(), contains("1", "2"));
        assertThat(nodes.get(0).prefix(), equalTo("user:1:"));
        assertThat(nodes.get(0).keyCount(), equalTo(2L));
    }

    @Test
    void ignoresKeysOutsideThePrefix() {
        List<NamespaceNode> nodes = builder.children(keys("other:1:x"), "user:", ":", false);

        assertThat(nodes, empty());
    }

    @Test
    void supportsADelimiterOtherThanColon() {
        List<NamespaceNode> nodes = builder.children(keys("a/b/c", "a/d"), "", "/", false);

        assertThat(nodes, hasSize(1));
        assertThat(nodes.get(0).prefix(), equalTo("a/"));
        assertThat(nodes.get(0).keyCount(), equalTo(2L));
    }

    /**
     * A count from a walk that stopped early is a floor, and every node says so.
     *
     * <p>Not only the node that reached the ceiling. The walk stops at some point in one arbitrary
     * cursor order, so what was cut short is not one branch but all of them — a node sitting under
     * the limit is no more complete than the one that reached it, it just happened to be scanned
     * less. Marking only the full one would have said the opposite.
     *
     * <p>This is the shape that produced the bug: a tree drew 5,814 under `cache` and 10,000 under
     * `cache:page`, because each level is its own sample of its own population. Both were true and
     * the pair was impossible.
     */
    @Test
    void marksEveryCountAsAFloorWhenTheWalkStoppedEarly() {
        List<NamespaceNode> nodes =
                builder.children(keys("cache:a:1", "cache:b:1", "user:1:x"), "", ":", true);

        assertThat(nodes, hasSize(2));
        assertThat(nodes.stream().allMatch(NamespaceNode::partial), equalTo(true));
    }

    /**
     * And a walk that saw everything says that instead, which is what makes the flag worth having.
     */
    @Test
    void leavesCountsAloneWhenTheWalkSawEverything() {
        List<NamespaceNode> nodes = builder.children(keys("cache:a:1", "user:1:x"), "", ":", false);

        assertThat(nodes.stream().anyMatch(NamespaceNode::partial), equalTo(false));
    }
}
