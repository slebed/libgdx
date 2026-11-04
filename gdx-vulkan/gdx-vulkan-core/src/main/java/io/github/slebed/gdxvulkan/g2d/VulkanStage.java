package io.github.slebed.gdxvulkan.g2d;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.OrthographicCamera;
import com.badlogic.gdx.scenes.scene2d.Stage;
import com.badlogic.gdx.utils.Scaling;
import com.badlogic.gdx.utils.viewport.ScalingViewport;
import com.badlogic.gdx.utils.viewport.Viewport;

/**
 * Scene2D's {@code Stage} drawing with a {@link VulkanSpriteBatch}: stock's {@code new Stage()} and {@code new Stage(viewport)} create a stock
 * {@code SpriteBatch}, which draws through GLSL and cannot run here. Everything else is the stock {@code Stage}, and the batch is disposed with it.
 */
public class VulkanStage extends Stage {
    private final VulkanSpriteBatch ownBatch;
    private boolean disposed;

    /** Like {@code new Stage()}: a stretching viewport the size of the screen. */
    public VulkanStage () {
        this(new ScalingViewport(Scaling.stretch, Gdx.graphics.getWidth(), Gdx.graphics.getHeight(), new OrthographicCamera()));
    }

    /** Like {@code new Stage(viewport)}. */
    public VulkanStage (Viewport viewport) {
        this(viewport, new VulkanSpriteBatch());
    }

    private VulkanStage (Viewport viewport, VulkanSpriteBatch batch) {
        super(viewport, batch);
        ownBatch = batch;
    }

    @Override public void dispose () {
        super.dispose();
        if (!disposed) {
            disposed = true;
            ownBatch.dispose();
        }
    }
}
