package io.keydra.graphql;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.is;

import io.keydra.AbstractTestBase;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * The schema in the repository is the schema the server serves.
 *
 * <p>Keydra's GraphQL is written in Java and the schema is derived from it, which is the wrong way
 * round for reviewing an API: nobody reads a diff of annotations and sees that a field changed
 * type, or that an argument stopped being optional, or that a query nobody meant to expose is now
 * exposed. Checking the schema in and failing here when it drifts puts the API in front of a
 * reviewer — the change and the schema move in one commit, or the build says so.
 *
 * <p>What this does not do is make the schema the source of truth. A field is still added in Java
 * and the file still follows. What it buys is that following is not optional.
 *
 * <p>One file per domain rather than one file, mirroring the packages: a query belongs beside the
 * types it answers with, and a single generated dump is a file people scroll past. Each domain
 * writes {@code extend type Query}, which is how SDL says "these are mine" — so the comparison here
 * is between what the files mean together and what the server serves, not between two strings.
 *
 * <p>When this fails and the change was intended, take the served schema and put the changed piece
 * in the file for its domain:
 *
 * <pre>
 *   curl -s http://localhost:8181/graphql/schema.graphql
 * </pre>
 */
@QuarkusTest
class SchemaDriftTest extends AbstractTestBase {

    private static final Path SCHEMAS = Path.of("src/main/graphql");

    /** Names the server declares that no domain has to own, because nothing here defines them. */
    private static final Set<String> BUILT_IN = Set.of("BigInteger", "Date", "DateTime", "Time");

    @Test
    void everyTypeAndOperationIsCheckedIn() {
        Map<String, Set<String>> served = read(fromServer());
        Map<String, Set<String>> checkedIn = read(fromFiles());

        List<String> differences = new ArrayList<>();
        for (var declared : served.entrySet()) {
            Set<String> ours = checkedIn.get(declared.getKey());
            if (ours == null) {
                differences.add(
                        declared.getKey() + " is served but is in no file under " + SCHEMAS);
                continue;
            }
            declared.getValue().stream()
                    .filter(member -> !ours.contains(member))
                    .forEach(
                            member ->
                                    differences.add(
                                            declared.getKey()
                                                    + "."
                                                    + member
                                                    + " is served but is"
                                                    + " not checked in"));
            ours.stream()
                    .filter(member -> !declared.getValue().contains(member))
                    .forEach(
                            member ->
                                    differences.add(
                                            declared.getKey()
                                                    + "."
                                                    + member
                                                    + " is checked in but"
                                                    + " is not served"));
        }
        checkedIn.keySet().stream()
                .filter(name -> !served.containsKey(name))
                .forEach(name -> differences.add(name + " is checked in but is not served"));

        assertThat(
                "The GraphQL schema and " + SCHEMAS + " disagree. Update the domain's file.",
                differences,
                is(empty()));
    }

    // --- Reading a schema, in whichever form it arrives -----------------------

    private static String fromServer() {
        return RestAssured.given().when().get("/graphql/schema.graphql").asString();
    }

    private static String fromFiles() {
        try (Stream<Path> files = Files.list(SCHEMAS)) {
            List<String> read = new ArrayList<>();
            for (Path file : files.filter(one -> one.toString().endsWith(".graphql")).toList()) {
                read.add(Files.readString(file));
            }
            assertThat("No schema files under " + SCHEMAS, read.isEmpty(), is(false));
            return String.join("\n", read);
        } catch (IOException unreadable) {
            throw new UncheckedIOException("Could not read " + SCHEMAS, unreadable);
        }
    }

    /** Opens a block: an optional {@code extend}, a kind, a name, and a brace. */
    private static final Pattern OPENS =
            Pattern.compile("^(?:extend )?(type|enum|input|interface|union) (\\w+)");

    /** A member's name — a field, an enum value — at one level of indentation. */
    private static final Pattern MEMBER = Pattern.compile("^ {2}(\\w+)");

    /**
     * A schema as names, not as text.
     *
     * <p>Type to the members it declares. Arguments, defaults and descriptions are deliberately not
     * compared: what this is guarding is that nothing appears or disappears without somebody
     * writing it down, and a comparison that also failed on a reworded description would be one
     * people learn to update without reading.
     *
     * <p>{@code extend type Query} in five files and {@code type Query} from the server come out
     * the same, which is the point of comparing this way.
     */
    private static Map<String, Set<String>> read(String schema) {
        Map<String, Set<String>> found = new LinkedHashMap<>();
        String open = null;
        int depth = 0;
        for (String line : schema.split("\n")) {
            if (open == null) {
                Matcher opens = OPENS.matcher(line);
                if (opens.find() && line.contains("{")) {
                    open = opens.group(2);
                    found.computeIfAbsent(open, ignored -> new TreeSet<>());
                    depth = 1;
                }
                continue;
            }
            depth += count(line, '(') - count(line, ')');
            if (line.startsWith("}")) {
                open = null;
                continue;
            }
            Matcher member = MEMBER.matcher(line);
            // Only at the outer level: the lines inside an argument list are argument names, and
            // they are the ones this deliberately does not compare.
            if (depth == 1 && member.find()) {
                found.get(open).add(member.group(1));
            }
        }
        BUILT_IN.forEach(found::remove);
        return found;
    }

    private static int count(String line, char of) {
        return (int) line.chars().filter(character -> character == of).count();
    }
}
