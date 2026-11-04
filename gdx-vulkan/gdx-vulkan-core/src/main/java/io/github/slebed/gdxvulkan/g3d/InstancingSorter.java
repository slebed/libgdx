package io.github.slebed.gdxvulkan.g3d;

import com.badlogic.gdx.graphics.Camera;
import com.badlogic.gdx.graphics.g3d.Renderable;
import com.badlogic.gdx.graphics.g3d.attributes.BlendingAttribute;
import com.badlogic.gdx.graphics.g3d.utils.RenderableSorter;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.utils.Array;

import java.util.Comparator;

/**
 * A {@link RenderableSorter} that puts identical opaque renderables next to each other, so {@link VulkanModelBatch} draws them as one instanced
 * call. Opaque renderables are ordered by mesh part and material first and by distance (nearest first, so early depth rejection helps) second;
 * blended ones come after, farthest first, exactly as the stock {@code DefaultRenderableSorter} orders them, so transparency looks the same.
 *
 * <p>The stock sorter orders opaque renderables by distance alone, which is fine for a few objects and scatters copies of one mesh through the
 * list; use this one when a scene repeats meshes (trees, props, crowds).
 */
public class InstancingSorter implements RenderableSorter, Comparator<Renderable> {
    private Camera camera;
    private final Vector3 tmpV1 = new Vector3();
    private final Vector3 tmpV2 = new Vector3();

    @Override public void sort (final Camera camera, final Array<Renderable> renderables) {
        this.camera = camera;
        renderables.sort(this);
    }

    private static boolean blended (Renderable r) {
        if (r.material == null) return false;
        final BlendingAttribute b = (BlendingAttribute)r.material.get(BlendingAttribute.Type);
        return b != null && b.blended;
    }

    private float distance (Renderable r) {
        r.worldTransform.getTranslation(tmpV1);
        if (r.meshPart.center.len2() > 0f) tmpV1.add(r.meshPart.center);
        return camera.position.dst2(tmpV1);
    }

    @Override public int compare (final Renderable a, final Renderable b) {
        final boolean ba = blended(a), bb = blended(b);
        if (ba != bb) return ba ? 1 : -1;
        if (ba) { // blended: farthest first
            return Float.compare(distance(b), distance(a));
        }
        // opaque: group identical draws, then nearest first
        final int ma = System.identityHashCode(a.meshPart.mesh), mb = System.identityHashCode(b.meshPart.mesh);
        if (ma != mb) return ma < mb ? -1 : 1;
        if (a.meshPart.offset != b.meshPart.offset) return a.meshPart.offset < b.meshPart.offset ? -1 : 1;
        final int ha = a.material == null ? 0 : a.material.attributesHash(), hb = b.material == null ? 0 : b.material.attributesHash();
        if (ha != hb) return ha < hb ? -1 : 1;
        return Float.compare(distance(a), distance(b));
    }
}
