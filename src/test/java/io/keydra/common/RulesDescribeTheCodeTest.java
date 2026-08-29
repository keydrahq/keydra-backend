package io.keydra.common;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.is;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * The domains CLAUDE.md lists are the domains that exist.
 *
 * <p>CLAUDE.md is the file that always applies: every phase is written against it, and its list of
 * domains is what tells somebody where a new piece of code belongs. A list that is wrong is worse
 * than no list, because it is read as though it were right — and by the time this was written it
 * had drifted by nine domains out of twenty-four, some of them years of phases old.
 *
 * <p>Checked rather than remembered, for the reason every other coverage test here exists: the
 * failure is not a wrong entry, which somebody notices, but a missing one, which looks exactly like
 * a file nobody has scrolled to.
 *
 * <p>Only the domains. The rest of that file is prose about why things are the way they are, and a
 * test that tried to check prose would be a test that fails on a rewrite.
 */
class RulesDescribeTheCodeTest {

    private static final Path RULES = Path.of("..", "CLAUDE.md");

    private static final Path README = Path.of("..", "README.md");

    private static final Path ROADMAP = Path.of("..", "docs", "ROADMAP.md");

    private static final Path AUTHORIZATION = Path.of("..", "docs", "AUTHORIZATION.md");

    private static final Path DOMAINS = Path.of("src/main/java/io/keydra");

    /** What CLAUDE.md itself says is not a domain: the place the domains meet. */
    private static final String CROSS_CUTTING = "common";

    @Test
    void everyDomainOnDiskIsNamedInTheRules() {
        Set<String> listed = listedInRules();
        Set<String> onDisk = onDisk();

        Set<String> missing = new TreeSet<>(onDisk);
        missing.removeAll(listed);
        // A domain that exists and is not in the list is a place somebody's next class will not
        // be put, because the file that says where things go does not know it is there.
        assertThat(missing, is(empty()));
    }

    @Test
    void andEveryDomainInTheRulesExists() {
        Set<String> listed = listedInRules();
        Set<String> onDisk = onDisk();

        Set<String> imagined = new TreeSet<>(listed);
        imagined.removeAll(onDisk);
        imagined.remove(CROSS_CUTTING);
        // The other direction, which is the one that outlives a rename: a list naming something
        // that is gone sends whoever reads it looking for a package.
        assertThat(imagined, is(empty()));
    }

    /**
     * The front page says how far this has got, and is right about it.
     *
     * <p>A line that counts something rots by construction — it was four phases stale when this was
     * written, and it would have been stale again by the next one. Kept rather than removed,
     * because "what is done" is the first thing somebody wants from a README; checked rather than
     * maintained, because the number is a claim about a file that is right here.
     */
    @Test
    void theFrontPageSaysHowFarThisHasGot() {
        Matcher claimed = Pattern.compile("phases 1[–-](\\d+) complete").matcher(read(README));
        assertThat("README no longer says how far this has got", claimed.find(), is(true));

        Matcher phase = Pattern.compile("(?m)^## Phase (\\d+)").matcher(read(ROADMAP));
        int last = 0;
        while (phase.find()) {
            last = Math.max(last, Integer.parseInt(phase.group(1)));
        }

        assertThat(Integer.parseInt(claimed.group(1)), is(last));
    }

    /**
     * Every permission is written down where the permission model is explained.
     *
     * <p>The strongest of these three, because of what it is about. A permission nobody documented
     * is a permission nobody reviews: the table in AUTHORIZATION.md is where somebody decides
     * whether a role should carry something, and ten of thirty-eight were missing from it when this
     * was written — including the one that rotates every stored credential and the two about taking
     * an instance out of service.
     *
     * <p>Both directions again. A permission in the document and not in the enum is a role somebody
     * will try to grant.
     */
    @Test
    void everyPermissionIsExplainedWhereThePermissionsAreExplained() {
        Set<String> documented = new TreeSet<>();
        Matcher inDoc = Pattern.compile("`([a-z-]+:[a-z-]+)`").matcher(read(AUTHORIZATION));
        while (inDoc.find()) {
            documented.add(inDoc.group(1));
        }

        Set<String> defined = new TreeSet<>();
        for (io.keydra.authz.entity.Permission permission :
                io.keydra.authz.entity.Permission.values()) {
            defined.add(permission.id());
        }

        Set<String> undocumented = new TreeSet<>(defined);
        undocumented.removeAll(documented);
        assertThat(undocumented, is(empty()));

        Set<String> imagined = new TreeSet<>(documented);
        imagined.removeAll(defined);
        assertThat(imagined, is(empty()));
    }

    private static Set<String> listedInRules() {
        String text = read(RULES);
        int start = text.indexOf("Domains:");
        if (start < 0) {
            throw new IllegalStateException("CLAUDE.md no longer has a list of domains");
        }
        // As far as the end of that paragraph: the sentence about io.keydra.common runs on, and
        // anything after the blank line is about something else.
        int end = text.indexOf("\n\n", start);
        String paragraph = text.substring(start, end < 0 ? text.length() : end);

        Matcher named = Pattern.compile("io\\.keydra\\.([a-z]+)").matcher(paragraph);
        Set<String> listed = new TreeSet<>();
        while (named.find()) {
            listed.add(named.group(1));
        }
        return listed;
    }

    private static Set<String> onDisk() {
        try (Stream<Path> packages = Files.list(DOMAINS)) {
            return packages.filter(Files::isDirectory)
                    .map(path -> path.getFileName().toString())
                    .filter(name -> !CROSS_CUTTING.equals(name))
                    .collect(java.util.stream.Collectors.toCollection(TreeSet::new));
        } catch (IOException unreadable) {
            throw new UncheckedIOException(unreadable);
        }
    }

    private static String read(Path path) {
        try {
            return Files.readString(path);
        } catch (IOException unreadable) {
            throw new UncheckedIOException(unreadable);
        }
    }
}
