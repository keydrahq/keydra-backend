package io.keydra.alerts.service;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;

import io.keydra.alerts.dto.AlertNotice;
import io.keydra.alerts.entity.AlertMetric;
import io.keydra.alerts.entity.Comparison;
import io.keydra.alerts.entity.EventKind;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/**
 * The sentence that decides whether an alert is any use.
 *
 * <p>Worth its own tests because it is the only part of this feature a person reads at four in the
 * morning, on a phone, having been woken up by it. A message that makes somebody go and look up
 * what a constant means has failed at the one thing it was for.
 */
class AlertWordingTest {

    private static final Instant NOON = Instant.parse("2026-08-20T12:00:00Z");

    @Test
    void aFiringRuleSaysWhichTargetAndWhatWasRead() {
        String sentence =
                AlertWording.sentence(
                        notice(EventKind.FIRED, AlertMetric.MEMORY_USED_BYTES, 851443712.0, 5.0E8));

        assertThat(sentence, containsString("payments-cache"));
        // Bytes at a scale somebody reads rather than counts.
        assertThat(sentence, containsString("812 MB"));
        assertThat(sentence, containsString("above"));
        assertThat(sentence, not(containsString("851443712")));
    }

    @Test
    void aClearedRuleSaysItIsOver() {
        String sentence =
                AlertWording.sentence(
                        notice(EventKind.CLEARED, AlertMetric.MEMORY_FILL_PERCENT, 41.0, 90.0));

        assertThat(sentence, containsString("back to 41%"));
        assertThat(sentence, containsString("no longer above 90%"));
    }

    @Test
    void aTargetThatIsNotAnsweringIsSaidPlainly() {
        AlertNotice silence =
                new AlertNotice(
                        1L,
                        "Unreachable",
                        4L,
                        "payments-cache",
                        null,
                        EventKind.FIRED,
                        AlertMetric.NO_ANSWER,
                        Comparison.ABOVE,
                        null,
                        0,
                        NOON);

        // Not "no answer is above 0", which is what a uniform template would produce.
        assertThat(
                AlertWording.sentence(silence),
                is("payments-cache is not answering (Unreachable)"));
    }

    @Test
    void aSubjectLineNamesTheTargetBeforeAnythingElseIsRead() {
        String subject =
                AlertWording.subject(
                        notice(EventKind.FIRED, AlertMetric.CONNECTED_CLIENTS, 900.0, 500.0));

        assertThat(subject, is("[Keydra] Clients - payments-cache is firing"));
    }

    @Test
    void aTargetWithNoNameIsStillIdentified() {
        AlertNotice nameless =
                new AlertNotice(
                        1L,
                        "Clients",
                        7L,
                        null,
                        null,
                        EventKind.FIRED,
                        AlertMetric.CONNECTED_CLIENTS,
                        Comparison.ABOVE,
                        900.0,
                        500.0,
                        NOON);

        assertThat(AlertWording.sentence(nameless), containsString("connection 7"));
    }

    private static AlertNotice notice(
            EventKind kind, AlertMetric metric, Double reading, double threshold) {
        return new AlertNotice(
                1L,
                metric == AlertMetric.CONNECTED_CLIENTS ? "Clients" : "Memory",
                4L,
                "payments-cache",
                // A rule is about a target, whose own name is the whole answer.
                null,
                kind,
                metric,
                Comparison.ABOVE,
                reading,
                threshold,
                NOON);
    }
}
