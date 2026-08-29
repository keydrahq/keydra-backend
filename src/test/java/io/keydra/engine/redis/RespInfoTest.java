package io.keydra.engine.redis;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.nullValue;

import java.util.Map;
import org.junit.jupiter.api.Test;

class RespInfoTest {

    private static final String SAMPLE =
            """
            # Server
            redis_version:8.10.0
            uptime_in_seconds:68

            # Memory
            used_memory:1048576
            maxmemory:0

            # Keyspace
            db0:keys=12,expires=3,avg_ttl=0
            db3:keys=7,expires=0,avg_ttl=0
            """;

    @Test
    void groupsFieldsUnderTheirSection() {
        Map<String, Map<String, String>> sections = RespInfo.parse(SAMPLE);

        assertThat(sections.keySet(), contains("server", "memory", "keyspace"));
        assertThat(sections.get("server").get("redis_version"), equalTo("8.10.0"));
        assertThat(sections.get("memory").get("used_memory"), equalTo("1048576"));
    }

    @Test
    void findsAFieldWithoutBeingToldItsSection() {
        // Callers know field names, not which section a given server puts them in.
        assertThat(RespInfo.number(RespInfo.parse(SAMPLE), "used_memory"), equalTo(1048576L));
    }

    @Test
    void answersNullForAFieldTheServerDidNotReport() {
        assertThat(RespInfo.number(RespInfo.parse(SAMPLE), "not_reported"), nullValue());
    }

    @Test
    void answersNullForAFieldThatIsNotANumber() {
        // Rather than throwing: one unparseable field must not lose the whole reading.
        assertThat(RespInfo.number(RespInfo.parse(SAMPLE), "redis_version"), nullValue());
    }

    @Test
    void readsTheKeyCountOutOfTheKeyspaceLine() {
        Map<String, Map<String, String>> sections = RespInfo.parse(SAMPLE);

        assertThat(RespInfo.keyCount(sections, 0), equalTo(12L));
        assertThat(RespInfo.keyCount(sections, 3), equalTo(7L));
    }

    @Test
    void reportsZeroForADatabaseTheServerOmitted() {
        // A database with no keys is left out of INFO entirely; that is zero, not unknown.
        assertThat(RespInfo.keyCount(RespInfo.parse(SAMPLE), 9), equalTo(0L));
    }

    @Test
    void survivesEmptyInput() {
        assertThat(RespInfo.parse(null).isEmpty(), equalTo(true));
        assertThat(RespInfo.parse("").isEmpty(), equalTo(true));
    }
}
