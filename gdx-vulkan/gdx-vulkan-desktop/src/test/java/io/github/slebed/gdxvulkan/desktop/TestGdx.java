package io.github.slebed.gdxvulkan.desktop;

import com.badlogic.gdx.Application;
import com.badlogic.gdx.Application.ApplicationType;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Graphics;
import com.badlogic.gdx.utils.GdxNativesLoader;

import io.github.slebed.gdxvulkan.GdxVkContext;
import io.github.slebed.gdxvulkan.gl.VulkanGL20;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

/**
 * Installs the GL20 shim into {@link Gdx} so stock libGDX classes run against it, without a full Application: just enough
 * {@code Gdx.app} and {@code Gdx.graphics} for {@code Texture}, {@code Pixmap} and {@code MipMapGenerator}. Everything else answers 0/null.
 */
final class TestGdx {
    private TestGdx() {}

    static VulkanGL20 install(GdxVkContext ctx) {
        GdxNativesLoader.load(); // gdx2d, for Pixmap
        final VulkanGL20 gl = new VulkanGL20(ctx);

        Gdx.app = proxy(Application.class, new InvocationHandler() {
            @Override public Object invoke(Object p, Method m, Object[] a) {
                if (m.getName().equals("getType")) return ApplicationType.Desktop;
                return defaultFor(m.getReturnType());
            }
        });
        Gdx.graphics = proxy(Graphics.class, new InvocationHandler() {
            @Override public Object invoke(Object p, Method m, Object[] a) {
                switch (m.getName()) {
                case "supportsExtension": return gl.supportsExtension((String)a[0]);
                case "getGL20": return gl;
                default: return defaultFor(m.getReturnType());
                }
            }
        });
        Gdx.gl = gl;
        Gdx.gl20 = gl;
        Gdx.gl30 = null; // always null: a non-null value would switch stock Mesh to VAOs (plan, Core decision 3)
        return gl;
    }

    static void uninstall() {
        Gdx.app = null;
        Gdx.graphics = null;
        Gdx.gl = null;
        Gdx.gl20 = null;
        Gdx.gl30 = null;
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, InvocationHandler h) {
        return (T)Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, h);
    }

    private static Object defaultFor(Class<?> t) {
        if (t == boolean.class) return false;
        if (t == int.class) return 0;
        if (t == float.class) return 0f;
        if (t == long.class) return 0L;
        if (t == double.class) return 0.0;
        return null;
    }
}
