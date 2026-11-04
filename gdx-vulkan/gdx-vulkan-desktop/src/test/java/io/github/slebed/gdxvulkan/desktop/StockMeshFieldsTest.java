package io.github.slebed.gdxvulkan.desktop;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.badlogic.gdx.graphics.Mesh;
import com.badlogic.gdx.graphics.glutils.IndexBufferObject;
import com.badlogic.gdx.graphics.glutils.IndexBufferObjectSubData;
import com.badlogic.gdx.graphics.glutils.IndexData;
import com.badlogic.gdx.graphics.glutils.VertexBufferObject;
import com.badlogic.gdx.graphics.glutils.VertexBufferObjectSubData;
import com.badlogic.gdx.graphics.glutils.VertexData;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.nio.ByteBuffer;

/**
 * Pins the private and package-private fields of libGDX that {@code StockMeshAccess} reads (plan, Core decision 5). It needs no GPU. If a libGDX
 * upgrade renames or retypes one of them this fails with the name, before any mesh is drawn: that is the cue to update {@code StockMeshAccess}, or
 * to switch to the public accessors if the upstream change that adds them has shipped.
 */
class StockMeshFieldsTest {
    private static Field field(Class<?> type, String name) throws Exception {
        return type.getDeclaredField(name);
    }

    @Test void meshHoldsItsVertexAndIndexDataInTheseFields() throws Exception {
        assertEquals(VertexData.class, field(Mesh.class, "vertices").getType());
        assertEquals(IndexData.class, field(Mesh.class, "indices").getType());
    }

    @Test void everyGlBackedDataClassHasTheHandleTheDirtyFlagAndTheBytes() throws Exception {
        for (Class<?> c : new Class<?>[] {VertexBufferObject.class, VertexBufferObjectSubData.class, IndexBufferObject.class, IndexBufferObjectSubData.class}) {
            assertEquals(int.class, field(c, "bufferHandle").getType(), c.getSimpleName() + ".bufferHandle");
            assertEquals(boolean.class, field(c, "isDirty").getType(), c.getSimpleName() + ".isDirty");
            assertEquals(ByteBuffer.class, field(c, "byteBuffer").getType(), c.getSimpleName() + ".byteBuffer");
        }
    }
}
