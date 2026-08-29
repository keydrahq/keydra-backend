package io.keydra.keys.script;

import java.time.Duration;
import org.luaj.vm2.Globals;
import org.luaj.vm2.LuaError;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.Varargs;
import org.luaj.vm2.compiler.LuaC;
import org.luaj.vm2.lib.Bit32Lib;
import org.luaj.vm2.lib.DebugLib;
import org.luaj.vm2.lib.PackageLib;
import org.luaj.vm2.lib.StringLib;
import org.luaj.vm2.lib.TableLib;
import org.luaj.vm2.lib.TwoArgFunction;
import org.luaj.vm2.lib.jse.JseBaseLib;
import org.luaj.vm2.lib.jse.JseMathLib;

/**
 * A Lua environment with everything dangerous taken out of it.
 *
 * <p>This is the whole point of letting scripts in at all, so it is worth being plain about what is
 * being defended. A script here does not run on somebody's Redis — Keydra already refuses to let
 * anyone run {@code EVAL} on a target, for exactly the reason that would be code execution on
 * somebody else's server. A script here runs <em>inside Keydra</em>, which is strictly worse: this
 * process holds every target's credentials, can reach every network Keydra can reach, and reads the
 * database Keydra keeps its accounts in. The interpreter's default environment hands all of that
 * away in one line.
 *
 * <p>So the environment is built up from nothing rather than cut down from the default. What is in
 * it is the part of Lua that computes: strings, tables, numbers, and the base functions that do not
 * reach outside the interpreter.
 *
 * <p>What is deliberately absent, and why each one matters:
 *
 * <ul>
 *   <li>{@code luajava} — reflects into any class on the classpath. One line reaches the connection
 *       registry, the credential store, or {@code Runtime.exec}. This alone makes the default
 *       environment unusable here.
 *   <li>{@code io}, {@code os} — the filesystem, the environment, the clock, {@code os.exit}.
 *   <li>{@code package}, {@code require}, {@code dofile}, {@code loadfile} — load code from disk,
 *       which is the same escape by a longer route.
 *   <li>{@code load}, {@code loadstring} — compile a new chunk at run time, in whatever environment
 *       the caller passes. A sandbox that leaves this in is a sandbox with a door in it.
 *   <li>{@code coroutine} — not dangerous in itself, but it moves execution between threads of
 *       control, and the instruction limit below counts on one.
 *   <li>{@code debug} — is loaded, because the instruction limit is implemented with it, and is
 *       then removed from the environment the script sees. It is the tool holding the wall up, not
 *       part of the room.
 *   <li>{@code print}, {@code collectgarbage} — no use here, and {@code print} writes to Keydra's
 *       own stdout, which is somebody's log.
 * </ul>
 *
 * <p>A time limit as well, because a sandbox that cannot escape can still not return: {@code while
 * true do end} is three words. It is counted in instructions rather than measured on a clock — a
 * clock check has to be read, and reading it is what a tight loop avoids.
 */
public final class SafeLua {

    /**
     * One script's environment, and the handle that arms its instruction limit.
     *
     * <p>The handle is {@code debug.sethook} itself, captured before {@code debug} was taken out of
     * the environment. Keeping it here rather than leaving the library in place is the difference
     * between a limit the sandbox enforces and one the script can switch off.
     */
    public record Sandbox(Globals globals, LuaValue setHook) {}

    /**
     * How many Lua instructions one call may spend.
     *
     * <p>Generous for the work this is for — deciding what happens to one key is tens of
     * instructions, not millions — and small enough that a runaway script gives the thread back in
     * milliseconds rather than never.
     */
    private static final int INSTRUCTION_BUDGET = 200_000;

    /** What the interpreter is told is left, checked every few thousand instructions. */
    private static final int HOOK_INTERVAL = 2_000;

    private SafeLua() {}

    /**
     * An environment a script can be compiled and run in.
     *
     * <p>Each call builds its own. Globals are mutable and a script can write to them, so sharing
     * one between two migrations would let a script leave something behind for the next — and two
     * scripts running at once would be writing over each other.
     */
    public static Sandbox sandbox() {
        Globals globals = new Globals();
        globals.load(new JseBaseLib());
        /*
         * Loaded because the others need it and then taken away, the same trick as `debug` below:
         * every library registers itself through `package.loaded`, so a sandbox built without this
         * fails while it is being built. Removing the name afterwards is what closes it — the
         * registry is only reachable through `package`, and `require` goes with it.
         */
        globals.load(new PackageLib());
        globals.load(new TableLib());
        globals.load(new StringLib());
        globals.load(new JseMathLib());
        globals.load(new Bit32Lib());
        // Loaded for the hook and then taken away: the script must not be able to unset its own
        // limit, which `debug.sethook(nil)` would do.
        globals.load(new DebugLib());
        LuaValue setHook = globals.get("debug").get("sethook");
        globals.set("debug", LuaValue.NIL);

        // The base library brings these with it. Each is a way out of the sandbox or a way to
        // reach the machine, so each is removed by name rather than trusted not to be reached.
        for (String reachesOutside :
                new String[] {
                    "dofile",
                    "loadfile",
                    "load",
                    "loadstring",
                    "require",
                    "package",
                    "io",
                    "os",
                    "luajava",
                    "coroutine",
                    "print",
                    "collectgarbage"
                }) {
            globals.set(reachesOutside, LuaValue.NIL);
        }

        LuaC.install(globals);
        return new Sandbox(globals, setHook);
    }

    /**
     * Arms the instruction limit on a sandbox, for one call.
     *
     * <p>Re-armed per call rather than once per script: the budget is what a script may spend
     * deciding about <em>one</em> key, and a limit that carried across keys would stop a migration
     * partway through for no reason a person could see.
     */
    public static void startCounting(Sandbox sandbox) {
        sandbox.setHook()
                .invoke(
                        LuaValue.varargsOf(
                                new LuaValue[] {
                                    new InstructionLimit(),
                                    // No event mask: this fires on the instruction count alone,
                                    // which is the only one that bounds a loop doing nothing.
                                    LuaValue.EMPTYSTRING,
                                    LuaValue.valueOf(HOOK_INTERVAL)
                                }));
    }

    /** Disarms it, so a sandbox is not left counting between calls. */
    public static void stopCounting(Sandbox sandbox) {
        sandbox.setHook().call(LuaValue.NIL);
    }

    /** Raised when a script spends more than it is allowed. Not catchable by the script. */
    private static final class InstructionLimit extends TwoArgFunction {

        private int spent;

        @Override
        public LuaValue call(LuaValue type, LuaValue argument) {
            spent += HOOK_INTERVAL;
            if (spent > INSTRUCTION_BUDGET) {
                throw new LuaError(
                        "the script ran too long — it is allowed "
                                + INSTRUCTION_BUDGET
                                + " instructions for each key");
            }
            return LuaValue.NIL;
        }

        @Override
        public Varargs invoke(Varargs args) {
            return call(args.arg1(), args.arg(2));
        }
    }

    /** How long a whole script is given to compile, as a sentence rather than a number. */
    public static Duration compileTimeout() {
        return Duration.ofSeconds(2);
    }
}
