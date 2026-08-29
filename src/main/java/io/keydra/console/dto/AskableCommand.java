package io.keydra.console.dto;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * One command a target can be allowed to run, and what allowing it means.
 *
 * <p>Commands that share a reason are the group a form draws them in, which is why the reason is
 * here at all: somebody ticking {@code module} is deciding what its reason says, not what its name
 * says.
 *
 * @param command the command, lower case, as the policy names it
 * @param reason a stable key for what it would let somebody do to the server — {@code runs-code},
 *     {@code writes-a-file} — rather than the sentence. The sentence is interface text and belongs
 *     with the rest of it; a sentence sent from here is a sentence in one language whatever
 *     language the page was asked for.
 */
@Schema(
        name = "AskableCommand",
        description =
                "A command a target can be allowed, and a stable key for why it is refused by"
                        + " default")
public record AskableCommand(String command, String reason) {}
