package io.keydra.keys.script;

import org.luaj.vm2.LuaError;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;

/**
 * A script that decides what happens to each key a migration moves.
 *
 * <p>The reason this exists rather than more checkboxes: a migration between two real keyspaces is
 * usually not "move everything" but "move these, under those names, except the ones like that" —
 * and the shape of "like that" is different every time. Options cover the two or three cases
 * somebody thought of; a script covers the one nobody did.
 *
 * <p>The contract is small on purpose. A chunk is handed a table called {@code key} and returns
 * what to do with it:
 *
 * <pre>{@code
 * -- skip anything temporary
 * if key.name:find('^tmp:') then return nil end
 *
 * -- move the rest, renamed, and give them a week
 * return { name = key.name:gsub('^staging:', 'prod:'), ttlMillis = 7 * 24 * 3600 * 1000 }
 * }</pre>
 *
 * <p>Returning nothing, {@code nil} or {@code false} skips the key. Returning {@code true} moves it
 * unchanged. Returning a table changes whichever of {@code name} and {@code ttlMillis} it names.
 *
 * <p><strong>What a script is not given, and why.</strong> Not the value, and not the type. The
 * fast path hands the target the source's own serialised bytes without ever decoding them — that is
 * what makes it fast — so on that path there is nothing to show a script but a name and a clock.
 * Making the type available would mean a round trip per key to ask, and making the value available
 * would mean decoding every type on the way past. Worse than either cost, the two paths would then
 * offer different things to the same script, and which path a migration takes is decided by the
 * pair of stores rather than by the person who wrote it. A contract that changes underfoot is not
 * one somebody can rely on.
 */
public final class KeyScript {

    private static final String KEY = "key";
    private static final String NAME = "name";
    private static final String TTL = "ttlMillis";

    private final SafeLua.Sandbox sandbox;
    private final LuaValue chunk;

    private KeyScript(SafeLua.Sandbox sandbox, LuaValue chunk) {
        this.sandbox = sandbox;
        this.chunk = chunk;
    }

    /**
     * Compiles a script, or refuses it.
     *
     * <p>Compiled once for a whole migration rather than per key, and in its own sandbox: two jobs
     * running at once must not be able to see each other's globals, and a job that finishes must
     * not leave anything for the next one.
     */
    public static KeyScript compile(String source) {
        SafeLua.Sandbox sandbox = SafeLua.sandbox();
        try {
            return new KeyScript(
                    sandbox, sandbox.globals().load(source, "migration", sandbox.globals()));
        } catch (LuaError notLua) {
            throw new ScriptRefusedException(notLua.getMessage(), notLua);
        }
    }

    /**
     * What to do with one key.
     *
     * <p>Synchronised, because a migration moves several batches at once and a Lua environment is
     * one mutable thing. Serialising the calls costs nothing that matters: deciding about a key is
     * tens of instructions against a round trip to a server, and the alternative — an environment
     * per thread — would mean a script's own state meaning something different depending on which
     * batch it was asked from.
     */
    public synchronized KeyDecision decide(String name, long ttlMillis) {
        LuaTable key = new LuaTable();
        key.set(NAME, LuaValue.valueOf(name));
        key.set(TTL, LuaValue.valueOf((double) ttlMillis));
        sandbox.globals().set(KEY, key);

        SafeLua.startCounting(sandbox);
        LuaValue answer;
        try {
            answer = chunk.call();
        } catch (LuaError refused) {
            throw new ScriptRefusedException(refused.getMessage(), refused);
        } finally {
            SafeLua.stopCounting(sandbox);
        }

        if (answer.isnil() || (answer.isboolean() && !answer.toboolean())) {
            return new KeyDecision(false, name, ttlMillis);
        }
        if (!answer.istable()) {
            // Anything else that is not false — true, a number, a string — means "move it", and
            // saying so is kinder than refusing a script over a return value that meant yes.
            return KeyDecision.keep(name, ttlMillis);
        }

        LuaValue renamed = answer.get(NAME);
        LuaValue expiry = answer.get(TTL);
        return new KeyDecision(
                true,
                renamed.isstring() ? renamed.tojstring() : name,
                expiry.isnumber() ? (long) expiry.todouble() : ttlMillis);
    }
}
