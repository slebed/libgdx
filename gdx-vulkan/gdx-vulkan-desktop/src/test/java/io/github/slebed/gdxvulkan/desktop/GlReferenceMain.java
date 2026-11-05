package io.github.slebed.gdxvulkan.desktop;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3ApplicationConfiguration;
import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.utils.ScreenUtils;

import java.awt.image.BufferedImage;
import java.io.File;

import javax.imageio.ImageIO;

/**
 * The compatibility reference (plan, Core decision 8): the Gate 2 scenes drawn by <b>stock SpriteBatch on OpenGL</b>, through the stock
 * lwjgl3 backend, written as PNGs. gdx-vulkan's output is compared with these. On CI this runs against llvmpipe; on a workstation, against
 * whatever OpenGL driver is installed.
 *
 * <p>{@code GlReferenceMain <outputDir> <assetsDir>}. Exit code 0 when every scene was written.
 */
public final class GlReferenceMain extends ApplicationAdapter {
    private final File outDir;
    private final FileHandle data;
    private final Gate2Scenes.Scene[] scenes = Gate2Scenes.all();
    private final Gate3Scenes.Scene[] scenes3 = Gate3Scenes.all();
    private final Gate5Scenes.Scene[] scenes5 = Gate5Scenes.all();
    private int next5;
    private Gate3Scenes.Fx fx;
    private int next;
    private int next3;
    private int failures;
    private SpriteBatch batch;

    private GlReferenceMain(File outDir, File assets) {
        this.outDir = outDir;
        this.data = new FileHandle(new File(assets, "data"));
    }

    public static void main(String[] args) {
        File out = new File(args[0]);
        out.mkdirs();
        Lwjgl3ApplicationConfiguration cfg = new Lwjgl3ApplicationConfiguration();
        cfg.setTitle("gdx-vulkan GL reference");
        cfg.setWindowedMode(Gate2Scenes.SIZE, Gate2Scenes.SIZE);
        cfg.setResizable(false);
        cfg.useVsync(false);
        cfg.setIdleFPS(0);
        GlReferenceMain app = new GlReferenceMain(out, new File(args[1]));
        try {
            new Lwjgl3Application(app, cfg);
        } catch (Throwable t) {
            System.err.println("[gl-reference] could not run: " + t);
            System.exit(2);
        }
        System.exit(app.failures == 0 && app.next >= app.scenes.length && app.next3 >= app.scenes3.length && app.next5 >= app.scenes5.length ? 0 : 1);
    }

    @Override public void create() {
        batch = new SpriteBatch();
        System.out.println("[gl-reference] " + Gdx.gl.glGetString(GL20.GL_RENDERER) + " / " + Gdx.gl.glGetString(GL20.GL_VERSION));
    }

    @Override public void render() {
        if (next >= scenes.length) {
            if (next3 >= scenes3.length) {
                if (next5 >= scenes5.length) {
                    Gdx.app.exit();
                    return;
                }
                renderGate5();
                return;
            }
            renderGate3();
            return;
        }
        Gate2Scenes.Scene scene = scenes[next++];
        int w = Gdx.graphics.getBackBufferWidth(), h = Gdx.graphics.getBackBufferHeight();
        try {
            scene.prepare(data, Gdx.graphics.getWidth(), Gdx.graphics.getHeight());
            float[] bg = Gate2Scenes.BACKGROUND;
            ScreenUtils.clear(bg[0], bg[1], bg[2], bg[3]);
            Gdx.gl.glViewport(0, 0, w, h);
            batch.getProjectionMatrix().setToOrtho2D(0, 0, w, h);
            if (scene instanceof Gate2Scenes.ShapeScene) {
                ShapeApi shapes = ShapeApi.stock(); // stock ShapeRenderer, which draws through GL programs
                try {
                    ((Gate2Scenes.ShapeScene)scene).drawShapes(shapes, Gdx.graphics.getWidth(), Gdx.graphics.getHeight());
                } finally {
                    shapes.dispose();
                }
            } else {
                scene.draw(batch, Gdx.graphics.getWidth(), Gdx.graphics.getHeight());
            }
            byte[] rgba = ScreenUtils.getFrameBufferPixels(0, 0, w, h, true); // row 0 at the top
            BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
            for (int i = 0, p = 0; p < w * h; p++, i += 4) {
                img.setRGB(p % w, p / w, ((rgba[i + 3] & 0xFF) << 24) | ((rgba[i] & 0xFF) << 16) | ((rgba[i + 1] & 0xFF) << 8) | (rgba[i + 2] & 0xFF));
            }
            ImageIO.write(img, "png", new File(outDir, scene.name() + ".png"));
            System.out.println("[gl-reference] wrote " + scene.name() + " (" + w + "x" + h + ")");
        } catch (Throwable t) {
            failures++;
            System.err.println("[gl-reference] scene " + scene.name() + " failed: " + t);
            t.printStackTrace();
        } finally {
            scene.dispose();
        }
    }

    /** The Gate 3 scenes: framebuffers, depth and GLSL post-processing, all on stock libGDX. */
    private void renderGate3() {
        Gate3Scenes.Scene scene = scenes3[next3++];
        final int w = Gdx.graphics.getBackBufferWidth(), h = Gdx.graphics.getBackBufferHeight();
        try {
            if (fx == null) fx = Gate3Scenes.glFx();
            scene.prepare(data, Gdx.graphics.getWidth(), Gdx.graphics.getHeight());
            float[] bg = Gate3Scenes.BACKGROUND;
            ScreenUtils.clear(bg[0], bg[1], bg[2], bg[3]);
            Gdx.gl.glViewport(0, 0, w, h);
            batch.getProjectionMatrix().setToOrtho2D(0, 0, w, h);
            scene.draw(new Gate3Scenes.Env() {
                public com.badlogic.gdx.graphics.g2d.Batch batch() { return batch; }

                public Gate3Scenes.Fx fx() { return fx; }

                public ShapeApi shapes() { return ShapeApi.stock(); }
            }, Gdx.graphics.getWidth(), Gdx.graphics.getHeight());
            byte[] rgba = ScreenUtils.getFrameBufferPixels(0, 0, w, h, true); // row 0 at the top
            BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
            for (int i = 0, p = 0; p < w * h; p++, i += 4) {
                img.setRGB(p % w, p / w, ((rgba[i + 3] & 0xFF) << 24) | ((rgba[i] & 0xFF) << 16) | ((rgba[i + 1] & 0xFF) << 8) | (rgba[i + 2] & 0xFF));
            }
            ImageIO.write(img, "png", new File(outDir, scene.name() + ".png"));
            System.out.println("[gl-reference] wrote " + scene.name() + " (" + w + "x" + h + ")");
        } catch (Throwable t) {
            failures++;
            System.err.println("[gl-reference] scene " + scene.name() + " failed: " + t);
            t.printStackTrace();
        } finally {
            scene.dispose();
        }
    }

    /** The Gate 5 scenes: stock ModelBuilder models drawn by stock ModelBatch and its GLSL default shader. */
    private void renderGate5() {
        Gate5Scenes.Scene scene = scenes5[next5++];
        if (!scene.hasStockReference()) return; // shows something stock's default shader cannot draw
        final int w = Gdx.graphics.getBackBufferWidth(), h = Gdx.graphics.getBackBufferHeight();
        Gate5Scenes.ModelApi api = null;
        try {
            scene.prepare(data);
            float[] bg = Gate5Scenes.BACKGROUND;
            ScreenUtils.clear(bg[0], bg[1], bg[2], bg[3]);
            Gdx.gl.glViewport(0, 0, w, h);
            api = scene.stockApi();
            scene.draw(api, Gdx.graphics.getWidth(), Gdx.graphics.getHeight());
            byte[] rgba = ScreenUtils.getFrameBufferPixels(0, 0, w, h, true); // row 0 at the top
            BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
            for (int i = 0, p = 0; p < w * h; p++, i += 4) {
                img.setRGB(p % w, p / w, ((rgba[i + 3] & 0xFF) << 24) | ((rgba[i] & 0xFF) << 16) | ((rgba[i + 1] & 0xFF) << 8) | (rgba[i + 2] & 0xFF));
            }
            ImageIO.write(img, "png", new File(outDir, scene.name() + ".png"));
            System.out.println("[gl-reference] wrote " + scene.name() + " (" + w + "x" + h + ")");
        } catch (Throwable t) {
            failures++;
            System.err.println("[gl-reference] scene " + scene.name() + " failed: " + t);
            t.printStackTrace();
        } finally {
            if (api != null) api.dispose();
            scene.dispose();
        }
    }

    @Override public void dispose() {
        if (fx != null) fx.dispose();
        if (batch != null) batch.dispose();
    }
}
