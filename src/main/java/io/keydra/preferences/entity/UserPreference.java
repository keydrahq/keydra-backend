package io.keydra.preferences.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.io.Serializable;

/**
 * One thing somebody prefers, kept with their account rather than with their browser.
 *
 * <p>Keydra has had accounts since phase 7 and until now the theme, the language, the page size and
 * the rest lived in {@code localStorage} — which means they belonged to a browser. Sign in from a
 * second machine and everything is back to the default; clear site data and it is gone; use Keydra
 * from a laptop and a desktop and the two disagree forever. A person's settings are as much theirs
 * as their password is.
 *
 * <p>A row per preference rather than one JSON column, because the shape here is genuinely
 * name-and-value: a page can write the one it changed without reading and rewriting the rest, and a
 * preference added next year needs no migration. That is the opposite of the reasoning behind
 * {@code ScheduledJob.settings}, where three kinds of work share almost no fields.
 *
 * <p>The value is text and nothing here parses it. What a preference means is the browser's
 * business — "dark", "200", "tr" — and a server that validated them would be a second place to
 * change every time the interface grew a switch.
 */
@Entity
@Table(name = "user_preference")
@IdClass(UserPreference.Key.class)
public class UserPreference {

    @Id
    @Column(name = "user_id", nullable = false)
    public Long userId;

    @Id
    @Column(nullable = false, length = 64)
    public String name;

    /**
     * Whatever the browser stored under that name.
     *
     * <p>Bounded so a preference cannot become a place to keep a file. Nothing legitimate here is
     * longer than a word or a number, and the limit is what stops that from being discovered the
     * hard way.
     */
    @Column(nullable = false, length = 4096)
    public String value;

    /** The pair that identifies a row: whose, and which. */
    public static class Key implements Serializable {
        public Long userId;
        public String name;

        @Override
        public boolean equals(Object other) {
            return other instanceof Key key
                    && java.util.Objects.equals(userId, key.userId)
                    && java.util.Objects.equals(name, key.name);
        }

        @Override
        public int hashCode() {
            return java.util.Objects.hash(userId, name);
        }
    }
}
