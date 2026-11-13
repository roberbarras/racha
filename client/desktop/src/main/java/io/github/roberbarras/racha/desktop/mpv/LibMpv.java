package io.github.roberbarras.racha.desktop.mpv;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_DOUBLE;
import static java.lang.foreign.ValueLayout.JAVA_INT;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;

/**
 * FFM binding of the libmpv client API (mpv/client.h, libmpv.so.2). One instance = one mpv core.
 * Not thread-safe for configuration; libmpv itself allows property reads and commands from any thread.
 */
public final class LibMpv implements AutoCloseable {

    public static final int EVENT_NONE = 0;
    public static final int EVENT_SHUTDOWN = 1;
    public static final int EVENT_END_FILE = 7;
    public static final int EVENT_FILE_LOADED = 8;
    public static final int EVENT_IDLE = 11;
    public static final int END_FILE_REASON_ERROR = 4;

    private static final Linker LINKER = Linker.nativeLinker();
    private static final int LC_NUMERIC = 1; // glibc
    private static volatile Native nativeLibrary;

    /** Event read from mpv_wait_event: id, error code and, for END_FILE, the end reason. */
    public record Event(int id, int error, int endReason) {}

    private final Native lib;
    private final MemorySegment handle;

    /** @throws UnsatisfiedLinkError if libmpv is not installed. */
    public LibMpv() {
        lib = Native.load();
        try (var a = Arena.ofConfined()) {
            // libmpv refuses to start unless numbers are formatted with the "C" locale (es_ES uses a decimal comma).
            var ignored = (MemorySegment) lib.setLocale.invokeExact(LC_NUMERIC, a.allocateFrom("C"));
            handle = (MemorySegment) lib.create.invokeExact();
        } catch (Throwable t) {
            throw rethrow(t);
        }
        if (handle.equals(MemorySegment.NULL)) throw new IllegalStateException("mpv_create failed");
    }

    public static boolean isAvailable() {
        try {
            Native.load();
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    public void setOption(String name, String value) {
        try (var a = Arena.ofConfined()) {
            check((int) lib.setOptionString.invokeExact(handle, a.allocateFrom(name), a.allocateFrom(value)), "option " + name);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    public void initialize() {
        try {
            check((int) lib.initialize.invokeExact(handle), "initialize");
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    public void setProperty(String name, String value) {
        try (var a = Arena.ofConfined()) {
            check((int) lib.setPropertyString.invokeExact(handle, a.allocateFrom(name), a.allocateFrom(value)), "property " + name);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    /** The property as a string, or null when it is unavailable. */
    public String getProperty(String name) {
        try (var a = Arena.ofConfined()) {
            var result = (MemorySegment) lib.getPropertyString.invokeExact(handle, a.allocateFrom(name));
            if (result.equals(MemorySegment.NULL)) return null;
            try {
                return result.reinterpret(Long.MAX_VALUE).getString(0);
            } finally {
                lib.free.invokeExact(result);
            }
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    public void command(String... args) {
        try (var a = Arena.ofConfined()) {
            var argv = a.allocate(ADDRESS, args.length + 1L);
            for (int i = 0; i < args.length; i++) argv.setAtIndex(ADDRESS, i, a.allocateFrom(args[i]));
            argv.setAtIndex(ADDRESS, args.length, MemorySegment.NULL);
            check((int) lib.command.invokeExact(handle, argv), "command " + args[0]);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    /** Blocks up to [timeoutSeconds] for the next event; returns an EVENT_NONE event on timeout. */
    public Event waitEvent(double timeoutSeconds) {
        try {
            // mpv_event { int event_id; int error; uint64_t reply_userdata; void *data; }
            var event = ((MemorySegment) lib.waitEvent.invokeExact(handle, timeoutSeconds)).reinterpret(24);
            int id = event.get(JAVA_INT, 0);
            int error = event.get(JAVA_INT, 4);
            int reason = -1;
            if (id == EVENT_END_FILE) {
                // mpv_event_end_file { int reason; int error; ... }
                var data = event.get(ADDRESS, 16);
                if (!data.equals(MemorySegment.NULL)) {
                    var endFile = data.reinterpret(8);
                    reason = endFile.get(JAVA_INT, 0);
                    error = endFile.get(JAVA_INT, 4);
                }
            }
            return new Event(id, error, reason);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    @Override
    public void close() {
        try {
            lib.terminateDestroy.invokeExact(handle);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    private void check(int code, String what) throws Throwable {
        if (code < 0) {
            var message = ((MemorySegment) lib.errorString.invokeExact(code)).reinterpret(Long.MAX_VALUE).getString(0);
            throw new IllegalStateException("mpv " + what + ": " + message + " (" + code + ")");
        }
    }

    private static RuntimeException rethrow(Throwable t) {
        return t instanceof RuntimeException re ? re : new IllegalStateException(t);
    }

    /** Lazily resolved symbols, so a missing libmpv only fails when mpv is actually used. */
    private static final class Native {
        final MethodHandle setLocale;
        final MethodHandle create;
        final MethodHandle initialize;
        final MethodHandle setOptionString;
        final MethodHandle setPropertyString;
        final MethodHandle getPropertyString;
        final MethodHandle free;
        final MethodHandle command;
        final MethodHandle waitEvent;
        final MethodHandle terminateDestroy;
        final MethodHandle errorString;

        private Native() {
            var lookup = SymbolLookup.libraryLookup("libmpv.so.2", Arena.global());
            setLocale = LINKER.downcallHandle(LINKER.defaultLookup().find("setlocale").orElseThrow(),
                    FunctionDescriptor.of(ADDRESS, JAVA_INT, ADDRESS));
            create = fn(lookup, "mpv_create", FunctionDescriptor.of(ADDRESS));
            initialize = fn(lookup, "mpv_initialize", FunctionDescriptor.of(JAVA_INT, ADDRESS));
            setOptionString = fn(lookup, "mpv_set_option_string", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS));
            setPropertyString = fn(lookup, "mpv_set_property_string", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS));
            getPropertyString = fn(lookup, "mpv_get_property_string", FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS));
            free = fn(lookup, "mpv_free", FunctionDescriptor.ofVoid(ADDRESS));
            command = fn(lookup, "mpv_command", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
            waitEvent = fn(lookup, "mpv_wait_event", FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_DOUBLE));
            terminateDestroy = fn(lookup, "mpv_terminate_destroy", FunctionDescriptor.ofVoid(ADDRESS));
            errorString = fn(lookup, "mpv_error_string", FunctionDescriptor.of(ADDRESS, JAVA_INT));
        }

        static Native load() {
            var current = nativeLibrary;
            if (current == null) {
                synchronized (Native.class) {
                    if (nativeLibrary == null) nativeLibrary = new Native();
                    current = nativeLibrary;
                }
            }
            return current;
        }

        private static MethodHandle fn(SymbolLookup lookup, String name, FunctionDescriptor descriptor) {
            return LINKER.downcallHandle(lookup.find(name).orElseThrow(), descriptor);
        }
    }
}
