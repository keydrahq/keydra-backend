package io.keydra.approvals.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.keydra.approvals.entity.ApprovalRequest;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Reads back what an operation said it would do.
 *
 * <p>A bean rather than a static helper so a handler is handed the one {@link ObjectMapper} the
 * application is configured with — the same one that wrote the payload, which is the only mapper
 * guaranteed to be able to read it.
 *
 * <p>A payload that cannot be read is a failure rather than an empty operation. It means the row
 * was written by a build that described this kind differently, and the safe answer to "I do not
 * know what I agreed to" is not to do it.
 */
@ApplicationScoped
public class ApprovalPayloads {

    private final ObjectMapper json;

    @Inject
    ApprovalPayloads(ObjectMapper json) {
        this.json = json;
    }

    public <T> T read(ApprovalRequest request, Class<T> type) {
        try {
            return json.readValue(request.payload, type);
        } catch (Exception unreadable) {
            throw new IllegalStateException(
                    "This request no longer describes an operation this build understands",
                    unreadable);
        }
    }
}
