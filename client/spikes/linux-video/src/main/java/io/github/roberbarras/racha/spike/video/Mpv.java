package io.github.roberbarras.racha.spike.video;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

/** Minimal FFM binding of libmpv's client and render APIs (mpv/client.h, mpv/render.h, mpv/render_gl.h). */
public final class Mpv implements AutoCloseable {

    // mpv_render_param_type
    public static final int PARAM_INVALID = 0;
    public static final int PARAM_API_TYPE = 1;
    public static final int PARAM_OPENGL_INIT_PARAMS = 2;
    public static final int PARAM_OPENGL_FBO = 3;
    public static final int PARAM_FLIP_Y = 4;
    public static final int PARAM_X11_DISPLAY = 8;
    public static final int PARAM_BLOCK_FOR_TARGET_TIME = 12;
    public static final int PARAM_SW_SIZE = 17;
    public static final int PARAM_SW_FORMAT = 18;
    public static final int PARAM_SW_STRIDE = 19;
    public static final int PARAM_SW_POINTER = 20;

    /** Bit set in mpv_render_context_update() when a new frame should be rendered. */
    public static final long UPDATE_FRAME = 1;

    private static final Linker LINKER = Linker.nativeLinker();
    private static final SymbolLookup LIB = SymbolLookup.libraryLookup("libmpv.so.2", Arena.global());

    private static MethodHandle fn(String name, FunctionDescriptor descriptor) {
        return LINKER.downcallHandle(LIB.find(name).orElseThrow(), descriptor);
    }

    private static final MethodHandle CREATE = fn("mpv_create", FunctionDescriptor.of(ADDRESS));
    private static final MethodHandle INITIALIZE = fn("mpv_initialize", FunctionDescriptor.of(JAVA_INT, ADDRESS));
    private static final MethodHandle SET_OPTION_STRING =
            fn("mpv_set_option_string", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS));
    private static final MethodHandle SET_PROPERTY_STRING =
            fn("mpv_set_property_string", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS));
    private static final MethodHandle GET_PROPERTY_STRING =
            fn("mpv_get_property_string", FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS));
    private static final MethodHandle FREE = fn("mpv_free", FunctionDescriptor.ofVoid(ADDRESS));
    private static final MethodHandle COMMAND = fn("mpv_command", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
    private static final MethodHandle TERMINATE_DESTROY = fn("mpv_terminate_destroy", FunctionDescriptor.ofVoid(ADDRESS));
    private static final MethodHandle ERROR_STRING = fn("mpv_error_string", FunctionDescriptor.of(ADDRESS, JAVA_INT));
    private static final MethodHandle RC_CREATE =
            fn("mpv_render_context_create", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS));
    private static final MethodHandle RC_SET_UPDATE_CALLBACK =
            fn("mpv_render_context_set_update_callback", FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, ADDRESS));
    private static final MethodHandle RC_UPDATE = fn("mpv_render_context_update", FunctionDescriptor.of(JAVA_LONG, ADDRESS));
    private static final MethodHandle RC_RENDER =
            fn("mpv_render_context_render", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
    private static final MethodHandle RC_REPORT_SWAP = fn("mpv_render_context_report_swap", FunctionDescriptor.ofVoid(ADDRESS));
    private static final MethodHandle RC_FREE = fn("mpv_render_context_free", FunctionDescriptor.ofVoid(ADDRESS));

    private static final MethodHandle RUN_CALLBACK;

    /** glibc's LC_NUMERIC. libmpv refuses to start unless numbers are formatted with the "C" locale. */
    private static final int LC_NUMERIC = 1;
    private static final MethodHandle SET_LOCALE = LINKER.downcallHandle(
            LINKER.defaultLookup().find("setlocale").orElseThrow(), FunctionDescriptor.of(ADDRESS, JAVA_INT, ADDRESS));

    static {
        try {
            RUN_CALLBACK = MethodHandles.lookup().findStatic(Mpv.class, "runCallback",
                    MethodType.methodType(void.class, Runnable.class, MemorySegment.class));
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private static void runCallback(Runnable action, MemorySegment ignoredContext) {
        action.run();
    }

    private final Arena arena = Arena.ofShared();
    private final MemorySegment handle;
    private MemorySegment renderContext = MemorySegment.NULL;

    public Mpv() {
        try (var a = Arena.ofConfined()) {
            var ignored = (MemorySegment) SET_LOCALE.invokeExact(LC_NUMERIC, a.allocateFrom("C"));
        } catch (Throwable t) {
            throw new IllegalStateException(t);
        }
        try {
            handle = (MemorySegment) CREATE.invokeExact();
        } catch (Throwable t) {
            throw new IllegalStateException(t);
        }
        if (handle.equals(MemorySegment.NULL)) throw new IllegalStateException("mpv_create failed");
    }

    public void setOption(String name, String value) {
        try (var a = Arena.ofConfined()) {
            check((int) SET_OPTION_STRING.invokeExact(handle, a.allocateFrom(name), a.allocateFrom(value)), "option " + name);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    public void initialize() {
        try {
            check((int) INITIALIZE.invokeExact(handle), "initialize");
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    public void setProperty(String name, String value) {
        try (var a = Arena.ofConfined()) {
            check((int) SET_PROPERTY_STRING.invokeExact(handle, a.allocateFrom(name), a.allocateFrom(value)), "property " + name);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    /** Returns the property as a string, or null when it is unavailable. */
    public String getProperty(String name) {
        try (var a = Arena.ofConfined()) {
            var result = (MemorySegment) GET_PROPERTY_STRING.invokeExact(handle, a.allocateFrom(name));
            if (result.equals(MemorySegment.NULL)) return null;
            try {
                return result.reinterpret(Long.MAX_VALUE).getString(0);
            } finally {
                FREE.invokeExact(result);
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
            check((int) COMMAND.invokeExact(handle, argv), "command " + args[0]);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    /** Creates an OpenGL render context. Must be called with the target GL context current. */
    public void createOpenGlRenderContext(MemorySegment getProcAddress, MemorySegment x11Display) {
        var init = arena.allocate(ADDRESS.byteSize() * 2);
        init.set(ADDRESS, 0, getProcAddress);
        init.set(ADDRESS, ADDRESS.byteSize(), MemorySegment.NULL);
        var params = new RenderParams(arena, 4)
                .add(PARAM_API_TYPE, arena.allocateFrom("opengl"))
                .add(PARAM_OPENGL_INIT_PARAMS, init);
        if (!x11Display.equals(MemorySegment.NULL)) params.add(PARAM_X11_DISPLAY, x11Display);
        createRenderContext(params);
    }

    public void createSoftwareRenderContext() {
        createRenderContext(new RenderParams(arena, 2).add(PARAM_API_TYPE, arena.allocateFrom("sw")));
    }

    private void createRenderContext(RenderParams params) {
        try {
            var out = arena.allocate(ADDRESS);
            check((int) RC_CREATE.invokeExact(out, handle, params.terminated()), "render context");
            renderContext = out.get(ADDRESS, 0);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    /** Registers a callback invoked from an mpv thread whenever a redraw may be needed. */
    public void onRenderUpdate(Runnable action) {
        var stub = LINKER.upcallStub(MethodHandles.insertArguments(RUN_CALLBACK, 0, action),
                FunctionDescriptor.ofVoid(ADDRESS), arena);
        try {
            RC_SET_UPDATE_CALLBACK.invokeExact(renderContext, stub, MemorySegment.NULL);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    public long renderUpdate() {
        try {
            return (long) RC_UPDATE.invokeExact(renderContext);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    /** Renders the current frame into the given FBO. Must be called with the GL context current. */
    public void renderOpenGl(MemorySegment fboParams, boolean flipY) {
        try (var a = Arena.ofConfined()) {
            var params = new RenderParams(a, 4)
                    .add(PARAM_OPENGL_FBO, fboParams)
                    .add(PARAM_FLIP_Y, a.allocateFrom(JAVA_INT, flipY ? 1 : 0))
                    .add(PARAM_BLOCK_FOR_TARGET_TIME, a.allocateFrom(JAVA_INT, 0));
            check((int) RC_RENDER.invokeExact(renderContext, params.terminated()), "render");
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    /** Renders the current frame as BGR0 pixels into {@code pixels} (stride = width * 4). */
    public void renderSoftware(MemorySegment pixels, int width, int height) {
        try (var a = Arena.ofConfined()) {
            var size = a.allocate(JAVA_INT, 2);
            size.setAtIndex(JAVA_INT, 0, width);
            size.setAtIndex(JAVA_INT, 1, height);
            var params = new RenderParams(a, 5)
                    .add(PARAM_SW_SIZE, size)
                    .add(PARAM_SW_FORMAT, a.allocateFrom("bgr0"))
                    .add(PARAM_SW_STRIDE, a.allocateFrom(JAVA_LONG, width * 4L))
                    .add(PARAM_SW_POINTER, pixels);
            check((int) RC_RENDER.invokeExact(renderContext, params.terminated()), "sw render");
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    public void reportSwap() {
        try {
            RC_REPORT_SWAP.invokeExact(renderContext);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    /** Frees the render context; for OpenGL it must be called with the GL context current. */
    public void freeRenderContext() {
        if (renderContext.equals(MemorySegment.NULL)) return;
        try {
            RC_FREE.invokeExact(renderContext);
        } catch (Throwable t) {
            throw rethrow(t);
        }
        renderContext = MemorySegment.NULL;
    }

    @Override
    public void close() {
        freeRenderContext();
        try {
            TERMINATE_DESTROY.invokeExact(handle);
        } catch (Throwable t) {
            throw rethrow(t);
        }
        arena.close();
    }

    private static void check(int code, String what) throws Throwable {
        if (code < 0) {
            var message = ((MemorySegment) ERROR_STRING.invokeExact(code)).reinterpret(Long.MAX_VALUE).getString(0);
            throw new IllegalStateException("mpv " + what + ": " + message + " (" + code + ")");
        }
    }

    private static RuntimeException rethrow(Throwable t) {
        return t instanceof RuntimeException re ? re : new IllegalStateException(t);
    }

    /** Builder for a zero-terminated mpv_render_param[] ({int type; void* data;} = 16 bytes on LP64). */
    private static final class RenderParams {
        private static final long ENTRY = 16;
        private final MemorySegment array;
        private int count;

        RenderParams(Arena arena, int capacity) {
            array = arena.allocate(ENTRY * (capacity + 1), 8);
        }

        RenderParams add(int type, MemorySegment data) {
            array.set(JAVA_INT, count * ENTRY, type);
            array.set(ADDRESS, count * ENTRY + 8, data);
            count++;
            return this;
        }

        MemorySegment terminated() {
            array.set(JAVA_INT, count * ENTRY, PARAM_INVALID);
            array.set(ADDRESS, count * ENTRY + 8, MemorySegment.NULL);
            return array;
        }
    }
}
