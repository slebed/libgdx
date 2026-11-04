package io.github.slebed.gdxvulkan.g3d;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Camera;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.OrthographicCamera;
import com.badlogic.gdx.graphics.PerspectiveCamera;
import com.badlogic.gdx.graphics.Pixmap.Format;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g3d.environment.DirectionalLight;
import com.badlogic.gdx.graphics.g3d.environment.ShadowMap;
import com.badlogic.gdx.graphics.g3d.utils.TextureDescriptor;
import com.badlogic.gdx.graphics.glutils.FrameBuffer;
import com.badlogic.gdx.math.MathUtils;
import com.badlogic.gdx.math.Matrix4;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.utils.Disposable;

/**
 * A directional light with a cascaded shadow map (beyond stock libGDX, whose {@code DirectionalShadowLight} has one map). The view frustum is cut
 * into 2 to 4 slices by distance; each gets its own shadow map, fitted to the slice, so shadows near the camera are sharp and shadows far away
 * still exist. Use it as stock's light: add it to the {@code Environment} and set {@code environment.shadowMap}.
 *
 * <pre>
 * light.update(camera);                       // splits the frustum and fits the cascades
 * for (int i = 0; i &lt; light.cascades(); i++) {
 *     light.begin(i);                         // binds the cascade's framebuffer and clears it
 *     depthBatch.begin(light.camera(i));      // a VulkanModelBatch.depth(frame) batch
 *     depthBatch.render(instances);
 *     depthBatch.end();
 *     light.end(i);
 * }
 * </pre>
 *
 * The cascade is chosen per pixel from its distance along the view direction, so the main camera must be a perspective camera. Each cascade's
 * bounds are the bounding sphere of its slice, moved in whole shadow-map texels, so the shadows do not shimmer when the camera turns. Tier A
 * only, like the plain shadow map.
 */
public class VulkanCascadedShadowLight extends DirectionalLight implements ShadowMap, Disposable {
    private final int count, size;
    private final float near, far, lambda, casterRange;
    private final FrameBuffer[] fbo;
    private final OrthographicCamera[] cams;
    private final TextureDescriptor<Texture>[] descs;
    private final float[] splits;
    private final Vector3 center = new Vector3(), right = new Vector3(), up = new Vector3(), fwd = new Vector3(), tmp = new Vector3();
    private final Vector3[] corners = new Vector3[8];

    /**
     * @param mapSize the width and height of each cascade's map
     * @param cascades 2 to 4
     * @param near where the first cascade starts (distance from the camera)
     * @param far where the last one ends: shadows are drawn up to here
     * @param lambda 0 splits the range evenly, 1 logarithmically (more detail near the camera); 0.5 to 0.8 is usual
     * @param casterRange how far toward the light from a slice the shadow casters are collected (a tall tree outside the slice still shadows it)
     */
    @SuppressWarnings("unchecked")
    public VulkanCascadedShadowLight (int mapSize, int cascades, float near, float far, float lambda, float casterRange) {
        if (cascades < 2 || cascades > 4) throw new IllegalArgumentException("cascades must be 2 to 4, was " + cascades);
        if (!(near > 0 && far > near)) throw new IllegalArgumentException("need 0 < near < far");
        this.count = cascades;
        this.size = mapSize;
        this.near = near;
        this.far = far;
        this.lambda = lambda;
        this.casterRange = casterRange;
        fbo = new FrameBuffer[cascades];
        cams = new OrthographicCamera[cascades];
        descs = new TextureDescriptor[cascades];
        splits = new float[cascades + 1];
        for (int i = 0; i < cascades; i++) {
            fbo[i] = new FrameBuffer(Format.RGBA8888, mapSize, mapSize, true);
            cams[i] = new OrthographicCamera(1f, 1f);
            descs[i] = new TextureDescriptor<Texture>();
            descs[i].minFilter = descs[i].magFilter = Texture.TextureFilter.Nearest;
            descs[i].uWrap = descs[i].vWrap = Texture.TextureWrap.ClampToEdge;
        }
        for (int i = 0; i < 8; i++) corners[i] = new Vector3();
    }

    /** Splits the camera's frustum between {@code near} and {@code far} and fits each cascade's camera to its slice. Call it once per frame. */
    public void update (Camera camera) {
        if (!(camera instanceof PerspectiveCamera)) throw new IllegalArgumentException("cascaded shadows need a PerspectiveCamera");
        final PerspectiveCamera pc = (PerspectiveCamera)camera;
        final float farLimit = Math.min(far, pc.far);
        splits[0] = near;
        for (int i = 1; i <= count; i++) {
            final float f = i / (float)count;
            final float log = near * (float)Math.pow(farLimit / near, f);
            final float uniform = near + (farLimit - near) * f;
            splits[i] = MathUtils.lerp(uniform, log, lambda);
        }
        splits[count] = farLimit;

        fwd.set(pc.direction).nor();
        up.set(pc.up).nor();
        right.set(fwd).crs(up).nor();
        up.set(right).crs(fwd).nor();
        final float tanY = (float)Math.tan(Math.toRadians(pc.fieldOfView) * 0.5);
        final float aspect = pc.viewportWidth / pc.viewportHeight;

        final Vector3 dir = new Vector3(direction).nor();
        for (int c = 0; c < count; c++) {
            int k = 0;
            for (int e = 0; e < 2; e++) {
                final float d = splits[c + e];
                final float h = tanY * d, w = h * aspect;
                for (int sy = -1; sy <= 1; sy += 2) {
                    for (int sx = -1; sx <= 1; sx += 2) {
                        corners[k++].set(pc.position).mulAdd(fwd, d).mulAdd(up, sy * h).mulAdd(right, sx * w);
                    }
                }
            }
            // The bounding sphere of the slice: its radius does not change when the camera turns, so the cascade's size is stable.
            center.setZero();
            for (int i = 0; i < 8; i++) center.add(corners[i]);
            center.scl(1f / 8f);
            float radius = 0;
            for (int i = 0; i < 8; i++) radius = Math.max(radius, corners[i].dst(center));
            radius = (float)Math.ceil(radius * 16f) / 16f;

            final OrthographicCamera cam = cams[c];
            // The light's basis, and the centre moved in whole texels of the map.
            tmp.set(Math.abs(dir.y) > 0.99f ? Vector3.X : Vector3.Y);
            final Vector3 lr = new Vector3(dir).crs(tmp).nor();
            final Vector3 lu = new Vector3(lr).crs(dir).nor();
            final float texel = 2f * radius / size;
            final float x = center.dot(lr), y = center.dot(lu);
            center.mulAdd(lr, (float)Math.floor(x / texel) * texel - x).mulAdd(lu, (float)Math.floor(y / texel) * texel - y);

            cam.viewportWidth = cam.viewportHeight = 2f * radius;
            cam.near = 0.1f;
            cam.far = radius + casterRange + radius;
            cam.position.set(center).mulAdd(dir, -(radius + casterRange));
            cam.direction.set(dir);
            cam.up.set(lu);
            cam.update();
        }
    }

    /** Binds cascade {@code i}'s framebuffer and clears it, as stock's {@code DirectionalShadowLight.begin()} does. */
    public void begin (int i) {
        final int w = fbo[i].getWidth(), h = fbo[i].getHeight();
        fbo[i].begin();
        Gdx.gl.glViewport(0, 0, w, h);
        Gdx.gl.glClearColor(1, 1, 1, 1);
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT | GL20.GL_DEPTH_BUFFER_BIT);
        Gdx.gl.glEnable(GL20.GL_SCISSOR_TEST);
        Gdx.gl.glScissor(1, 1, w - 2, h - 2);
    }

    public void end (int i) {
        Gdx.gl.glDisable(GL20.GL_SCISSOR_TEST);
        fbo[i].end();
    }

    public int cascades () { return count; }

    /** The orthographic camera of cascade {@code i}: the one to draw the shadow casters with. */
    public Camera camera (int i) { return cams[i]; }

    public Matrix4 projViewTrans (int i) { return cams[i].combined; }

    public TextureDescriptor<Texture> depthMap (int i) {
        descs[i].texture = fbo[i].getColorBufferTexture();
        return descs[i];
    }

    /** The distance from the camera where cascade {@code i} ends and the next begins (valid for {@code i} below {@code cascades() - 1}). */
    public float split (int i) { return i + 1 < count ? splits[i + 1] : splits[count]; }

    /** The distance where the shadow map's first cascade starts and where the last ends: {@code [near, far]} of the constructor, clipped to the camera. */
    public float shadowNear () { return splits[0]; }

    public float shadowFar () { return splits[count]; }

    @Override public Matrix4 getProjViewTrans () { return projViewTrans(0); }

    @Override public TextureDescriptor<Texture> getDepthMap () { return depthMap(0); }

    @Override public void dispose () {
        for (int i = 0; i < count; i++) {
            if (fbo[i] != null) fbo[i].dispose();
            fbo[i] = null;
        }
    }
}
