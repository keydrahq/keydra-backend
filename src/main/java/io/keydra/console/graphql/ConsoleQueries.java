package io.keydra.console.graphql;

import io.keydra.authz.RequiresPermission;
import io.keydra.authz.entity.Permission;
import io.keydra.common.graphql.OneAtATime;
import io.keydra.console.dto.AskableCommand;
import io.keydra.console.dto.HistoryEntry;
import io.keydra.console.service.ConsoleService;
import io.keydra.security.Roles;
import io.smallrye.mutiny.Uni;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import java.util.List;
import org.eclipse.microprofile.graphql.Description;
import org.eclipse.microprofile.graphql.GraphQLApi;
import org.eclipse.microprofile.graphql.Mutation;
import org.eclipse.microprofile.graphql.Name;
import org.eclipse.microprofile.graphql.Query;

/**
 * What the console has been asked, and what it will refuse.
 *
 * <p>Running a command is not here and never will be: a console is a conversation, and it already
 * has a socket. What is here is what surrounds one — the transcript, and the list of commands this
 * instance will not run whoever is asking.
 *
 * <p>Values are not here either, and that is a limit worth naming rather than working around. A
 * value is a sealed hierarchy at both ends: reading one answers a string page, a hash page, a
 * stream page or three others, and changing one is fourteen different things. GraphQL has no input
 * unions at all, and expressing the output side would mean a second model maintained beside the
 * sealed one. Both would throw away exactly the safety sealing gives — that every place handling a
 * value handles all of them or fails to compile. So values stay on the first surface, where Jackson
 * picks the variant from a discriminator, until GraphQL has a way to say it.
 */
@GraphQLApi
@OneAtATime
public class ConsoleQueries {

    private final ConsoleService console;

    @Inject
    ConsoleQueries(ConsoleService console) {
        this.console = console;
    }

    @Query("consoleHistory")
    @Description("What you have run against this target, newest first, with values redacted")
    @RolesAllowed({Roles.OPERATOR, Roles.ADMIN})
    @RequiresPermission(value = Permission.CONSOLE_RUN, connection = "connectionId")
    public Uni<List<HistoryEntry>> consoleHistory(@Name("connectionId") Long connectionId) {
        return console.history(connectionId);
    }

    @Query("deniedCommands")
    @Description("The commands this target refuses to run, whoever is asking")
    @RolesAllowed({Roles.OPERATOR, Roles.ADMIN})
    @RequiresPermission(value = Permission.CONSOLE_RUN, connection = "connectionId")
    public Uni<List<String>> deniedCommands(@Name("connectionId") Long connectionId) {
        return console.deniedCommands(connectionId);
    }

    /**
     * The commands a target may be allowed to run, which is what a form can offer.
     *
     * <p>A plain list rather than a connection: it says nothing about this instance and everything
     * about this build. The other half of the deny-list is not here because it is not a choice —
     * those commands are refused because of what they would do to Keydra's own connection, which is
     * the same on every target.
     *
     * <p>The role is the whole guard, and no {@code @RequiresPermission} beside it. The permission
     * this belongs to is {@code connection:edit}, which applies at connection level — and asking
     * for a connection-level permission without naming a connection resolves only the grants made
     * at instance level, so it would refuse exactly the administrators who hold it on a group,
     * which is the normal way to hold it. What it returns is a constant of this build rather than
     * anything about this installation, so the coarse gate is the right size for it.
     */
    @Query("askableCommands")
    @Description("Commands a target can be allowed to run, each with what allowing it means")
    @RolesAllowed(Roles.ADMIN)
    public Uni<List<AskableCommand>> askableCommands() {
        return Uni.createFrom().item(console.askableCommands());
    }

    @Mutation("clearConsoleHistory")
    @Description("Empties your own command history on this target")
    @RolesAllowed({Roles.OPERATOR, Roles.ADMIN})
    @RequiresPermission(value = Permission.CONSOLE_RUN, connection = "connectionId")
    public Uni<Boolean> clearConsoleHistory(@Name("connectionId") Long connectionId) {
        return console.clearHistory(connectionId).replaceWith(true);
    }
}
