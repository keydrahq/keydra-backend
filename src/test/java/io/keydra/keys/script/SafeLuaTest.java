package io.keydra.keys.script;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.luaj.vm2.LuaError;
import org.luaj.vm2.LuaValue;

/**
 * What a script cannot do, which is the only reason scripts are allowed at all.
 *
 * <p>These are not tests of Lua. They are the statement of what this sandbox is for: a script here
 * runs inside Keydra, holding every target's credentials and able to reach every network Keydra
 * can, so each name below is a way out that the default interpreter environment leaves open and
 * this one does not. A test that goes green because the name was spelled wrong would be worse than
 * no test, so each asserts the name is *absent* rather than that some call failed.
 */
class SafeLuaTest {

    private static LuaValue run(String source) {
        SafeLua.Sandbox sandbox = SafeLua.sandbox();
        SafeLua.startCounting(sandbox);
        try {
            return sandbox.globals().load(source, "test", sandbox.globals()).call();
        } finally {
            SafeLua.stopCounting(sandbox);
        }
    }

    /**
     * Every door out, checked by name.
     *
     * <p>{@code luajava} is the one that matters most: it reflects into any class on the classpath,
     * so one line of it reaches the connection registry, the credential store or Runtime.exec. The
     * rest reach the filesystem, the machine, or a fresh compiler.
     */
    @ParameterizedTest
    @ValueSource(
            strings = {
                "luajava",
                "io",
                "os",
                "package",
                "require",
                "dofile",
                "loadfile",
                "load",
                "loadstring",
                "debug",
                "coroutine",
                "print",
                "collectgarbage"
            })
    void hasNoWayOut(String name) {
        assertThat(run("return type(" + name + ")").tojstring(), equalTo("nil"));
    }

    /** And the parts that only compute are all there, or the feature would be pointless. */
    @Test
    void keepsTheParticsThatCompute() {
        assertThat(
                run("return ('user:1'):gsub('^user:', 'account:')").tojstring(),
                equalTo("account:1"));
        assertThat(run("return math.floor(3.7)").toint(), is(3));
        assertThat(run("return #({1, 2, 3})").toint(), is(3));
    }

    /**
     * A sandbox that cannot be escaped can still fail to return.
     *
     * <p>`while true do end` is three words and no syntax error. Counted in instructions rather
     * than measured against a clock, because reading a clock is exactly what a tight loop does not
     * do.
     */
    @Test
    @Timeout(10)
    void stopsAScriptThatNeverFinishes() {
        LuaError stopped =
                org.junit.jupiter.api.Assertions.assertThrows(
                        LuaError.class, () -> run("while true do end"));

        assertThat(stopped.getMessage(), containsString("ran too long"));
    }

    /** Including one that tries to sit inside a call it hoped the hook would not reach. */
    @Test
    @Timeout(10)
    void stopsOneHidingInsideAFunction() {
        org.junit.jupiter.api.Assertions.assertThrows(
                LuaError.class, () -> run("local function spin() while true do end end spin()"));
    }

    /**
     * And it cannot switch the limit off, because the tool that sets it is not in the room.
     *
     * <p>This is why `debug` is loaded and then removed rather than never loaded: the limit is
     * implemented with it, and a script that could call `debug.sethook(nil)` would be a script with
     * no limit at all.
     */
    @Test
    @Timeout(10)
    void cannotUnsetItsOwnLimit() {
        org.junit.jupiter.api.Assertions.assertThrows(
                LuaError.class,
                () -> run("pcall(function() debug.sethook() end) while true do end"));
    }

    /** Two scripts do not share an environment, so one cannot leave anything for the next. */
    @Test
    void givesEachScriptItsOwnEnvironment() {
        run("leftover = 'from the last one'");

        assertThat(run("return type(leftover)").tojstring(), equalTo("nil"));
    }
}
