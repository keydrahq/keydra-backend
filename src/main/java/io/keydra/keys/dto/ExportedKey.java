package io.keydra.keys.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * One key in an export file.
 *
 * <p>The payload is the store's own serialisation, which Jackson writes as base64 — so an export is
 * a plain JSON document that can be read, diffed and checked into a repository, while the values
 * inside it are byte-exact rather than a lossy rendering of what the store holds.
 *
 * @param key the key's name
 * @param ttlMillis remaining life in milliseconds, 0 for a key that does not expire
 * @param payload the store's serialisation of the value
 */
public record ExportedKey(@NotBlank String key, long ttlMillis, @NotNull byte[] payload) {}
