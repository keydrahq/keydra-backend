package io.keydra.keys.dto;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * One level of the namespace tree, built by splitting keys on a delimiter.
 *
 * @param name the segment itself, e.g. {@code user}
 * @param prefix the full prefix including the delimiter, e.g. {@code user:} — what a client sends
 *     back to expand this node
 * @param keyCount keys seen beneath this node during the sampled scan
 * @param hasChildren whether expanding it would show further levels
 * @param partial whether that scan stopped at its sample limit, which makes {@code keyCount} a
 *     floor rather than a total. Worth saying out loud because the tree is read a level at a time
 *     and each level is its own sample: a parent counted out of the first ten thousand keys of the
 *     whole keyspace sat above a child counted out of the first ten thousand keys under that
 *     parent, and the child could therefore show a larger number than the parent it is inside. Both
 *     numbers were true of their own sample and the pair of them was nonsense.
 */
@Schema(name = "NamespaceNode", description = "A node in the key namespace tree")
public record NamespaceNode(
        String name, String prefix, long keyCount, boolean hasChildren, boolean partial) {}
