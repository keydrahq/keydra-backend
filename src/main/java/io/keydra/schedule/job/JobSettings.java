package io.keydra.schedule.job;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.keydra.schedule.exception.ScheduleRefusedException;

/**
 * Reading a job's own settings out of the JSON it keeps them in.
 *
 * <p>JSON rather than a column per field because the job types have almost nothing in common; this
 * is the small amount of care that costs. Every read names what it wanted, so a missing field is a
 * sentence rather than a null three frames later.
 */
public final class JobSettings {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final JsonNode node;
    private final String jobName;

    private JobSettings(JsonNode node, String jobName) {
        this.node = node;
        this.jobName = jobName;
    }

    public static JobSettings of(String json, String jobName) {
        try {
            return new JobSettings(MAPPER.readTree(json == null ? "{}" : json), jobName);
        } catch (Exception unreadable) {
            throw new ScheduleRefusedException(jobName + " has settings that are not JSON");
        }
    }

    public String required(String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull() || value.asText().isBlank()) {
            throw new ScheduleRefusedException(jobName + " needs " + field);
        }
        return value.asText();
    }

    public long requiredNumber(String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.canConvertToLong()) {
            throw new ScheduleRefusedException(jobName + " needs " + field);
        }
        return value.asLong();
    }

    public String optional(String field, String fallback) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() || value.asText().isBlank()
                ? fallback
                : value.asText();
    }

    public int optionalNumber(String field, int fallback) {
        JsonNode value = node.get(field);
        return value == null || !value.canConvertToInt() ? fallback : value.asInt();
    }

    public boolean optionalFlag(String field, boolean fallback) {
        JsonNode value = node.get(field);
        return value == null || !value.isBoolean() ? fallback : value.asBoolean();
    }
}
