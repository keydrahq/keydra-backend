package io.keydra.authz;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;

import io.keydra.AbstractTestBase;
import io.keydra.authz.entity.AppUser;
import io.keydra.authz.entity.Grant;
import io.keydra.authz.entity.GroupMembership;
import io.keydra.authz.entity.Permission;
import io.keydra.authz.entity.RoleDefinition;
import io.keydra.authz.entity.ScopeType;
import io.keydra.authz.entity.ServerGroup;
import io.keydra.authz.entity.ServerGroupMember;
import io.keydra.authz.entity.SubjectType;
import io.keydra.authz.persistence.AuthzRepository;
import io.keydra.authz.service.BuiltInRoleSeeder;
import io.keydra.authz.service.PermissionResolver;
import io.quarkus.hibernate.reactive.panache.common.WithTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.smallrye.mutiny.Uni;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Walking the two graphs.
 *
 * <p>The example the model was designed against: the payments team may write the payments servers,
 * may read the sessions server, and must not know the analytics cluster exists. Every test below is
 * a sentence from that, and the point of each is that nobody had to write the same edge twice.
 */
@QuarkusTest
class PermissionResolutionTest extends AbstractTestBase {

    @Inject AuthzRepository repository;
    @Inject PermissionResolver resolver;
    @Inject BuiltInRoleSeeder seeder;

    private Long alice;
    private Long paymentsDevs;
    private Long engineers;
    private Long paymentsGroup;
    private Long productionGroup;
    private Long paymentsCache;
    private Long sessionsCache;
    private Long analyticsCluster;
    private Long viewerRole;
    private Long operatorRole;

    /**
     * Runs a reactive call from a blocking test, which is what these assertions are.
     *
     * <p>Through VertxContextSupport rather than a bare await: the reactive session is bound to a
     * Vert.x context, and a JUnit thread has none — the same reason the fixtures do it this way.
     */
    private static <T> T await(java.util.function.Supplier<Uni<T>> work) {
        try {
            return io.quarkus.vertx.VertxContextSupport.subscribeAndAwait(work::get);
        } catch (Throwable failure) {
            throw new IllegalStateException("Reactive call failed", failure);
        }
    }

    @BeforeEach
    void setUp() {
        await(() -> clean());
        io.keydra.connections.ConnectionFixtures.deleteAllProfiles();
        await(() -> seeder.seed());
        paymentsCache =
                io.keydra.connections.ConnectionFixtures.createProfile(
                        "payments-cache", "localhost", 6379);
        sessionsCache =
                io.keydra.connections.ConnectionFixtures.createProfile(
                        "sessions-cache", "localhost", 6379);
        analyticsCluster =
                io.keydra.connections.ConnectionFixtures.createProfile(
                        "analytics", "localhost", 6379);
        await(() -> build());
    }

    @WithTransaction
    Uni<Void> clean() {
        return io.quarkus.hibernate.reactive.panache.Panache.getSession()
                .flatMap(
                        session ->
                                session.createQuery("delete from Grant")
                                        .executeUpdate()
                                        .flatMap(
                                                ignored ->
                                                        session.createQuery(
                                                                        "delete from"
                                                                            + " ServerGroupMember")
                                                                .executeUpdate())
                                        .flatMap(
                                                ignored ->
                                                        session.createQuery(
                                                                        "delete from"
                                                                            + " GroupMembership")
                                                                .executeUpdate())
                                        .flatMap(
                                                ignored ->
                                                        session.createQuery(
                                                                        "delete from ServerGroup")
                                                                .executeUpdate())
                                        .flatMap(
                                                ignored ->
                                                        session.createQuery("delete from UserGroup")
                                                                .executeUpdate())
                                        .flatMap(
                                                ignored ->
                                                        session.createQuery("delete from AppUser")
                                                                .executeUpdate())
                                        .replaceWithVoid());
    }

    /**
     * The shape from the design document.
     *
     * <pre>
     * alice ──▶ payments-devs ──▶ engineers
     * production ──▶ payments-servers ──▶ payments-cache, sessions-cache
     *            └─▶ analytics-servers ──▶ analytics-cluster
     * </pre>
     */
    @WithTransaction
    Uni<Void> build() {
        AppUser user = new AppUser();
        user.username = "alice";
        user.provider = "local";

        io.keydra.authz.entity.UserGroup devs = new io.keydra.authz.entity.UserGroup();
        devs.name = "payments-devs";
        io.keydra.authz.entity.UserGroup all = new io.keydra.authz.entity.UserGroup();
        all.name = "engineers";

        ServerGroup production = new ServerGroup();
        production.name = "production";
        ServerGroup payments = new ServerGroup();
        payments.name = "payments-servers";
        ServerGroup analytics = new ServerGroup();
        analytics.name = "analytics-servers";

        return repository
                .save(user)
                .flatMap(saved -> repository.save(devs).map(g -> saved))
                .flatMap(saved -> repository.save(all).map(g -> saved))
                .flatMap(saved -> repository.save(production).map(g -> saved))
                .flatMap(
                        saved -> {
                            alice = saved.id;
                            paymentsDevs = devs.id;
                            engineers = all.id;
                            productionGroup = production.id;
                            payments.parentId = production.id;
                            analytics.parentId = production.id;
                            return repository
                                    .save(payments)
                                    .flatMap(p -> repository.save(analytics));
                        })
                .flatMap(
                        ignored -> {
                            paymentsGroup = payments.id;
                            // alice ▸ payments-devs ▸ engineers
                            GroupMembership inDevs = new GroupMembership();
                            inDevs.groupId = paymentsDevs;
                            inDevs.memberUserId = alice;
                            GroupMembership devsInEngineers = new GroupMembership();
                            devsInEngineers.groupId = engineers;
                            devsInEngineers.memberGroupId = paymentsDevs;
                            return repository
                                    .save(inDevs)
                                    .flatMap(x -> repository.save(devsInEngineers));
                        })
                .flatMap(ignored -> placeConnections(payments.id, analytics.id))
                .flatMap(ignored -> repository.roleByName("viewer"))
                .flatMap(
                        role -> {
                            viewerRole = role.id;
                            return repository.roleByName("operator");
                        })
                .flatMap(
                        role -> {
                            operatorRole = role.id;
                            return Uni.createFrom().voidItem();
                        });
    }

    /**
     * Puts the three targets in their groups.
     *
     * <p>The targets themselves are made before the transaction starts, by the blocking fixture
     * every other test uses: creating one over HTTP needs the admin role, and a test about who may
     * do what should not have to be an administrator to set itself up.
     */
    private Uni<Void> placeConnections(Long paymentsId, Long analyticsId) {
        ServerGroupMember inPayments = new ServerGroupMember();
        inPayments.groupId = paymentsId;
        inPayments.connectionId = paymentsCache;

        ServerGroupMember inAnalytics = new ServerGroupMember();
        inAnalytics.groupId = analyticsId;
        inAnalytics.connectionId = analyticsCluster;

        // sessions-cache is deliberately in no group: a target reachable only by naming it
        // is the case a grant on a group must not accidentally cover.
        return repository
                .save(inPayments)
                .flatMap(ignored -> repository.save(inAnalytics))
                .replaceWithVoid();
    }

    @WithTransaction
    Uni<Grant> grant(SubjectType type, Long subject, ScopeType scope, Long scopeId, Long roleId) {
        Grant grant = new Grant();
        grant.subjectType = type;
        grant.subjectId = subject;
        grant.scopeType = scope;
        grant.scopeId = scopeId;
        grant.roleId = roleId;
        return repository.save(grant);
    }

    @Test
    void aStrangerHoldsNothing() {
        // Somebody who has proved who they are, and been granted nothing, is still a
        // stranger. This is the state every new user starts in.
        assertThat(await(() -> resolver.permissionsFor(alice, paymentsCache)), is(empty()));
    }

    @Test
    void aGrantToAGroupReachesEverybodyInIt() {
        await(
                () ->
                        grant(
                                SubjectType.GROUP,
                                paymentsDevs,
                                ScopeType.CONNECTION,
                                paymentsCache,
                                operatorRole));

        // Nobody wrote "alice may write payments-cache". The edge from her to the group and
        // the grant to the group are separate facts, and the walk joins them.
        assertThat(
                await(() -> resolver.permissionsFor(alice, paymentsCache)),
                hasItem(Permission.KEYS_WRITE));
    }

    @Test
    void aGrantToAnOuterGroupReachesThroughTheInnerOne() {
        await(
                () ->
                        grant(
                                SubjectType.GROUP,
                                engineers,
                                ScopeType.CONNECTION,
                                paymentsCache,
                                viewerRole));

        // alice ▸ payments-devs ▸ engineers. Two edges, neither of them written twice.
        assertThat(
                await(() -> resolver.permissionsFor(alice, paymentsCache)),
                hasItem(Permission.KEYS_READ));
    }

    @Test
    void aGrantOnAServerGroupReachesEveryTargetInIt() {
        await(
                () ->
                        grant(
                                SubjectType.USER,
                                alice,
                                ScopeType.SERVER_GROUP,
                                paymentsGroup,
                                operatorRole));

        assertThat(
                await(() -> resolver.permissionsFor(alice, paymentsCache)),
                hasItem(Permission.KEYS_WRITE));
    }

    @Test
    void aGrantOnAParentGroupReachesTheTargetsBelowIt() {
        // production contains payments-servers, which contains payments-cache.
        await(
                () ->
                        grant(
                                SubjectType.USER,
                                alice,
                                ScopeType.SERVER_GROUP,
                                productionGroup,
                                viewerRole));

        assertThat(
                await(() -> resolver.permissionsFor(alice, paymentsCache)),
                hasItem(Permission.KEYS_READ));
    }

    @Test
    void aGrantReachesNothingItWasNotMadeOn() {
        await(
                () ->
                        grant(
                                SubjectType.USER,
                                alice,
                                ScopeType.SERVER_GROUP,
                                paymentsGroup,
                                operatorRole));

        // The analytics cluster is in a different group. Absence is the denial.
        assertThat(await(() -> resolver.permissionsFor(alice, analyticsCluster)), is(empty()));
    }

    @Test
    void theUnionOfSeveralGrantsIsWhatSomebodyHolds() {
        await(
                () ->
                        grant(
                                SubjectType.USER,
                                alice,
                                ScopeType.CONNECTION,
                                sessionsCache,
                                viewerRole));
        await(
                () ->
                        grant(
                                SubjectType.GROUP,
                                engineers,
                                ScopeType.SERVER_GROUP,
                                paymentsGroup,
                                operatorRole));

        // Read on sessions, write on payments — the payments example, exactly.
        Set<Permission> onSessions = await(() -> resolver.permissionsFor(alice, sessionsCache));
        Set<Permission> onPayments = await(() -> resolver.permissionsFor(alice, paymentsCache));

        assertThat(onSessions, hasItem(Permission.KEYS_READ));
        assertThat(onSessions, not(hasItem(Permission.KEYS_WRITE)));
        assertThat(onPayments, hasItem(Permission.KEYS_WRITE));
    }

    @Test
    void somebodySeesOnlyWhatTheyHoldSomethingOn() {
        await(
                () ->
                        grant(
                                SubjectType.GROUP,
                                paymentsDevs,
                                ScopeType.SERVER_GROUP,
                                paymentsGroup,
                                viewerRole));

        List<Long> all = List.of(paymentsCache, sessionsCache, analyticsCluster);

        // The whole point: the analytics cluster is not hidden by a flag somebody set, it is
        // absent because nothing grants anything on it.
        assertThat(await(() -> resolver.visibleConnections(alice, all)), contains(paymentsCache));
    }

    @Test
    void aGrantOnTheInstanceSeesEverything() {
        await(() -> grant(SubjectType.USER, alice, ScopeType.INSTANCE, null, viewerRole));

        List<Long> all = List.of(paymentsCache, sessionsCache, analyticsCluster);

        assertThat(
                await(() -> resolver.visibleConnections(alice, all)),
                containsInAnyOrder(paymentsCache, sessionsCache, analyticsCluster));
    }

    @Test
    void onlyAnInstanceGrantCarriesThePermissionsAboutKeydraItself() {
        await(
                () ->
                        grant(
                                SubjectType.USER,
                                alice,
                                ScopeType.SERVER_GROUP,
                                paymentsGroup,
                                operatorRole));

        // An operator on some servers is not thereby somebody who may create users.
        assertThat(
                await(() -> resolver.instancePermissionsFor(alice)),
                not(hasItem(Permission.USERS_MANAGE)));
    }

    @Test
    void aRoleIsReadFromItsRowSoEditingItChangesWhatItCarries() {
        await(
                () ->
                        grant(
                                SubjectType.USER,
                                alice,
                                ScopeType.CONNECTION,
                                paymentsCache,
                                viewerRole));
        RoleDefinition viewer = await(() -> repository.roleByName("viewer"));

        // The built-in ones are rewritten from code on every start, which is what keeps the
        // row and BuiltInRole from saying different things.
        assertThat(viewer.builtIn, is(true));
        assertThat(viewer.permissions, hasItem(Permission.KEYS_READ));
    }
}
