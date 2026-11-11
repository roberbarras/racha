package io.github.roberbarras.racha.spike.video;

import java.awt.Component;
import java.awt.Container;
import java.lang.reflect.Field;
import java.util.function.Consumer;
import kotlin.Unit;
import kotlin.jvm.functions.Function1;
import org.jetbrains.skia.Canvas;
import org.jetbrains.skia.DirectContext;
import org.jetbrains.skiko.GraphicsApi;
import org.jetbrains.skiko.SkiaLayer;

/**
 * Reaches Compose Desktop's Skia {@link DirectContext}. Skiko does not expose it publicly, so this relies on
 * internal members of Skiko 0.150.x and must be re-validated on every Skiko upgrade.
 */
public final class SkikoInternals {

    private SkikoInternals() {}

    public static SkiaLayer findSkiaLayer(Component component) {
        if (component instanceof SkiaLayer layer) return layer;
        if (component instanceof Container container) {
            for (Component child : container.getComponents()) {
                SkiaLayer found = findSkiaLayer(child);
                if (found != null) return found;
            }
        }
        return null;
    }

    public static GraphicsApi renderApi(Component root) {
        SkiaLayer layer = findSkiaLayer(root);
        return layer == null ? null : layer.getRenderApi();
    }

    /** Returns the DirectContext of the window's GL redrawer, or null if the layout of Skiko internals changed. */
    public static DirectContext directContext(Component root) {
        try {
            SkiaLayer layer = findSkiaLayer(root);
            if (layer == null) return null;
            Object redrawer = layer.getRedrawer$skiko();
            if (redrawer == null) return null;
            Object handler = field(redrawer.getClass(), "contextHandler").get(redrawer);
            return (DirectContext) field(handler.getClass(), "context").get(handler);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }

    /**
     * Runs {@code hook} at the start of every GL draw pass (context current, DirectContext initialised), right before
     * Skiko replays the picture that Compose recorded. Returns false if Skiko internals changed.
     */
    @SuppressWarnings("unchecked")
    public static boolean installPreDrawHook(Component root, Consumer<DirectContext> hook) {
        try {
            SkiaLayer layer = findSkiaLayer(root);
            if (layer == null) return false;
            Object redrawer = layer.getRedrawer$skiko();
            if (redrawer == null) return false;
            Object handler = field(redrawer.getClass(), "contextHandler").get(redrawer);
            Field contextField = field(handler.getClass(), "context");
            Field drawContentField = field(handler.getClass(), "drawContent");
            var original = (Function1<Canvas, Unit>) drawContentField.get(handler);
            Function1<Canvas, Unit> wrapped = canvas -> {
                try {
                    hook.accept((DirectContext) contextField.get(handler));
                } catch (IllegalAccessException e) {
                    throw new IllegalStateException(e);
                }
                return original.invoke(canvas);
            };
            drawContentField.set(handler, wrapped);
            return true;
        } catch (ReflectiveOperationException | RuntimeException e) {
            return false;
        }
    }

    /** Diagnostic description of the path used by {@link #directContext}. */
    public static String describe(Component root) {
        var sb = new StringBuilder();
        tree(root, 0, sb);
        SkiaLayer layer = findSkiaLayer(root);
        if (layer != null) {
            Object redrawer = layer.getRedrawer$skiko();
            sb.append("redrawer=").append(redrawer == null ? null : redrawer.getClass().getName()).append('\n');
            try {
                Object handler = field(redrawer.getClass(), "contextHandler").get(redrawer);
                sb.append("handler=").append(handler.getClass().getName()).append('\n');
                sb.append("context=").append(field(handler.getClass(), "context").get(handler)).append('\n');
            } catch (Exception e) {
                sb.append("error=").append(e).append('\n');
            }
        }
        return sb.toString();
    }

    private static void tree(Component c, int depth, StringBuilder sb) {
        sb.append("  ".repeat(depth)).append(c.getClass().getName()).append('\n');
        if (c instanceof Container container) for (Component child : container.getComponents()) tree(child, depth + 1, sb);
    }

    private static Field field(Class<?> type, String name) throws NoSuchFieldException {
        for (Class<?> c = type; c != null; c = c.getSuperclass()) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f;
            } catch (NoSuchFieldException ignored) {
                // keep walking up the hierarchy
            }
        }
        throw new NoSuchFieldException(name);
    }
}
