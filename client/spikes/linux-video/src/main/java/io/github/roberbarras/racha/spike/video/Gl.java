package io.github.roberbarras.racha.spike.video;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

/** The handful of GLX/OpenGL entry points needed to give mpv an FBO backed by a Skia-owned texture. */
public final class Gl {

    public static final int TEXTURE_2D = 0x0DE1;
    public static final int RGBA8 = 0x8058;
    private static final int RGBA = 0x1908;
    private static final int UNSIGNED_BYTE = 0x1401;
    private static final int TEXTURE_MIN_FILTER = 0x2801;
    private static final int TEXTURE_MAG_FILTER = 0x2800;
    private static final int TEXTURE_WRAP_S = 0x2802;
    private static final int TEXTURE_WRAP_T = 0x2803;
    private static final int LINEAR = 0x2601;
    private static final int CLAMP_TO_EDGE = 0x812F;
    private static final int FRAMEBUFFER = 0x8D40;
    private static final int COLOR_ATTACHMENT0 = 0x8CE0;
    private static final int FRAMEBUFFER_COMPLETE = 0x8CD5;
    private static final int FRAMEBUFFER_BINDING = 0x8CA6;
    private static final int TEXTURE_BINDING_2D = 0x8069;

    private static final Linker LINKER = Linker.nativeLinker();
    private static final SymbolLookup LIB_GL = SymbolLookup.libraryLookup("libGL.so.1", Arena.global());
    private static final MethodHandle GET_PROC_ADDRESS = LINKER.downcallHandle(
            LIB_GL.find("glXGetProcAddressARB").orElseThrow(), FunctionDescriptor.of(ADDRESS, ADDRESS));
    private static final MethodHandle GET_CURRENT_DISPLAY = LINKER.downcallHandle(
            LIB_GL.find("glXGetCurrentDisplay").orElseThrow(), FunctionDescriptor.of(ADDRESS));

    /** C function pointer {@code void *(*)(void *ctx, const char *name)} handed to mpv. */
    public static final MemorySegment GET_PROC_ADDRESS_CALLBACK;

    static {
        try {
            var target = MethodHandles.lookup().findStatic(Gl.class, "procAddress",
                    MethodType.methodType(MemorySegment.class, MemorySegment.class, MemorySegment.class));
            GET_PROC_ADDRESS_CALLBACK = LINKER.upcallStub(target, FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS), Arena.global());
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private static MemorySegment procAddress(MemorySegment ignoredContext, MemorySegment name) {
        try {
            return (MemorySegment) GET_PROC_ADDRESS.invokeExact(name);
        } catch (Throwable t) {
            return MemorySegment.NULL;
        }
    }

    public static MemorySegment currentX11Display() {
        try {
            return (MemorySegment) GET_CURRENT_DISPLAY.invokeExact();
        } catch (Throwable t) {
            throw new IllegalStateException(t);
        }
    }

    private final MethodHandle genTextures;
    private final MethodHandle bindTexture;
    private final MethodHandle texImage2D;
    private final MethodHandle texParameteri;
    private final MethodHandle genFramebuffers;
    private final MethodHandle bindFramebuffer;
    private final MethodHandle framebufferTexture2D;
    private final MethodHandle checkFramebufferStatus;
    private final MethodHandle deleteFramebuffers;
    private final MethodHandle getIntegerv;

    /** Resolves GL functions; must be constructed with a GL context current. */
    public Gl() {
        genTextures = gl("glGenTextures", FunctionDescriptor.ofVoid(JAVA_INT, ADDRESS));
        bindTexture = gl("glBindTexture", FunctionDescriptor.ofVoid(JAVA_INT, JAVA_INT));
        texImage2D = gl("glTexImage2D", FunctionDescriptor.ofVoid(
                JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, ADDRESS));
        texParameteri = gl("glTexParameteri", FunctionDescriptor.ofVoid(JAVA_INT, JAVA_INT, JAVA_INT));
        genFramebuffers = gl("glGenFramebuffers", FunctionDescriptor.ofVoid(JAVA_INT, ADDRESS));
        bindFramebuffer = gl("glBindFramebuffer", FunctionDescriptor.ofVoid(JAVA_INT, JAVA_INT));
        framebufferTexture2D = gl("glFramebufferTexture2D", FunctionDescriptor.ofVoid(JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT));
        checkFramebufferStatus = gl("glCheckFramebufferStatus", FunctionDescriptor.of(JAVA_INT, JAVA_INT));
        deleteFramebuffers = gl("glDeleteFramebuffers", FunctionDescriptor.ofVoid(JAVA_INT, ADDRESS));
        getIntegerv = gl("glGetIntegerv", FunctionDescriptor.ofVoid(JAVA_INT, ADDRESS));
    }

    private static MethodHandle gl(String name, FunctionDescriptor descriptor) {
        try (var a = Arena.ofConfined()) {
            var address = (MemorySegment) GET_PROC_ADDRESS.invokeExact(a.allocateFrom(name));
            if (address.equals(MemorySegment.NULL)) throw new IllegalStateException("missing GL function " + name);
            return LINKER.downcallHandle(address, descriptor);
        } catch (Throwable t) {
            throw t instanceof RuntimeException re ? re : new IllegalStateException(t);
        }
    }

    /** A texture-backed framebuffer. The texture is later adopted (owned and deleted) by Skia. */
    public record TextureTarget(int texture, int framebuffer, int width, int height) {}

    public TextureTarget createTextureTarget(int width, int height) {
        try (var a = Arena.ofConfined()) {
            var ids = a.allocate(JAVA_INT);
            var previousTexture = getInt(a, TEXTURE_BINDING_2D);
            var previousFramebuffer = getInt(a, FRAMEBUFFER_BINDING);

            genTextures.invokeExact(1, ids);
            int texture = ids.get(JAVA_INT, 0);
            bindTexture.invokeExact(TEXTURE_2D, texture);
            texImage2D.invokeExact(TEXTURE_2D, 0, RGBA8, width, height, 0, RGBA, UNSIGNED_BYTE, MemorySegment.NULL);
            texParameteri.invokeExact(TEXTURE_2D, TEXTURE_MIN_FILTER, LINEAR);
            texParameteri.invokeExact(TEXTURE_2D, TEXTURE_MAG_FILTER, LINEAR);
            texParameteri.invokeExact(TEXTURE_2D, TEXTURE_WRAP_S, CLAMP_TO_EDGE);
            texParameteri.invokeExact(TEXTURE_2D, TEXTURE_WRAP_T, CLAMP_TO_EDGE);

            genFramebuffers.invokeExact(1, ids);
            int framebuffer = ids.get(JAVA_INT, 0);
            bindFramebuffer.invokeExact(FRAMEBUFFER, framebuffer);
            framebufferTexture2D.invokeExact(FRAMEBUFFER, COLOR_ATTACHMENT0, TEXTURE_2D, texture, 0);
            int status = (int) checkFramebufferStatus.invokeExact(FRAMEBUFFER);

            bindFramebuffer.invokeExact(FRAMEBUFFER, previousFramebuffer);
            bindTexture.invokeExact(TEXTURE_2D, previousTexture);
            if (status != FRAMEBUFFER_COMPLETE) throw new IllegalStateException("incomplete FBO: 0x" + Integer.toHexString(status));
            return new TextureTarget(texture, framebuffer, width, height);
        } catch (Throwable t) {
            throw t instanceof RuntimeException re ? re : new IllegalStateException(t);
        }
    }

    public void deleteFramebuffer(int framebuffer) {
        try (var a = Arena.ofConfined()) {
            deleteFramebuffers.invokeExact(1, a.allocateFrom(JAVA_INT, framebuffer));
        } catch (Throwable t) {
            throw t instanceof RuntimeException re ? re : new IllegalStateException(t);
        }
    }

    private int getInt(Arena a, int pname) throws Throwable {
        var out = a.allocate(JAVA_INT);
        getIntegerv.invokeExact(pname, out);
        return out.get(JAVA_INT, 0);
    }
}
