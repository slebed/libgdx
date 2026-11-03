package io.github.slebed.gdxvulkan.desktop;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;

import io.github.slebed.gdxvulkan.GdxVkFrame;
import io.github.slebed.gdxvulkan.g2d.VulkanShapeRenderer;

/**
 * The slice of ShapeRenderer the shape scene uses, so one scene can drive stock {@code ShapeRenderer} (the OpenGL reference) and
 * {@code VulkanShapeRenderer}, whose nested ShapeType enum is a different type.
 */
interface ShapeApi {
    enum Kind { Point, Line, Filled }

    void setColor(Color color);

    default void setColor(float r, float g, float b, float a) { setColor(new Color(r, g, b, a)); }

    void begin(Kind kind);

    void end();

    void line(float x, float y, float x2, float y2);

    void point(float x, float y);

    void rect(float x, float y, float w, float h);

    void circle(float x, float y, float radius, int segments);

    void triangle(float x1, float y1, float x2, float y2, float x3, float y3);

    void arc(float x, float y, float radius, float start, float degrees, int segments);

    void curve(float x1, float y1, float cx1, float cy1, float cx2, float cy2, float x2, float y2, int segments);

    /** 3D: used by the Gate 3 scene, which draws filled boxes under a perspective camera. */
    void setProjectionMatrix(com.badlogic.gdx.math.Matrix4 matrix);

    void identity();

    void translate(float x, float y, float z);

    void rotate(float axisX, float axisY, float axisZ, float degrees);

    void box(float x, float y, float z, float width, float height, float depth);

    void dispose();

    static ShapeApi stock() {
        final ShapeRenderer r = new ShapeRenderer();
        return new ShapeApi() {
            public void setColor(Color c) { r.setColor(c); }
            public void begin(Kind k) { r.begin(k == Kind.Point ? ShapeRenderer.ShapeType.Point : k == Kind.Line ? ShapeRenderer.ShapeType.Line : ShapeRenderer.ShapeType.Filled); }
            public void end() { r.end(); }
            public void line(float x, float y, float x2, float y2) { r.line(x, y, x2, y2); }
            public void point(float x, float y) { r.point(x, y, 0); }
            public void rect(float x, float y, float w, float h) { r.rect(x, y, w, h); }
            public void circle(float x, float y, float radius, int s) { r.circle(x, y, radius, s); }
            public void triangle(float a, float b, float c, float d, float e, float f) { r.triangle(a, b, c, d, e, f); }
            public void arc(float x, float y, float radius, float start, float degrees, int s) { r.arc(x, y, radius, start, degrees, s); }
            public void curve(float a, float b, float c, float d, float e, float f, float g, float h, int s) { r.curve(a, b, c, d, e, f, g, h, s); }
            public void setProjectionMatrix(com.badlogic.gdx.math.Matrix4 m) { r.setProjectionMatrix(m); }
            public void identity() { r.identity(); }
            public void translate(float x, float y, float z) { r.translate(x, y, z); }
            public void rotate(float ax, float ay, float az, float deg) { r.rotate(ax, ay, az, deg); }
            public void box(float x, float y, float z, float w, float h, float d) { r.box(x, y, z, w, h, d); }
            public void dispose() { r.dispose(); }
        };
    }

    static ShapeApi vulkan(GdxVkFrame frame) {
        final VulkanShapeRenderer r = new VulkanShapeRenderer(frame);
        return new ShapeApi() {
            public void setColor(Color c) { r.setColor(c); }
            public void begin(Kind k) { r.begin(k == Kind.Point ? VulkanShapeRenderer.ShapeType.Point : k == Kind.Line ? VulkanShapeRenderer.ShapeType.Line : VulkanShapeRenderer.ShapeType.Filled); }
            public void end() { r.end(); }
            public void line(float x, float y, float x2, float y2) { r.line(x, y, x2, y2); }
            public void point(float x, float y) { r.point(x, y, 0); }
            public void rect(float x, float y, float w, float h) { r.rect(x, y, w, h); }
            public void circle(float x, float y, float radius, int s) { r.circle(x, y, radius, s); }
            public void triangle(float a, float b, float c, float d, float e, float f) { r.triangle(a, b, c, d, e, f); }
            public void arc(float x, float y, float radius, float start, float degrees, int s) { r.arc(x, y, radius, start, degrees, s); }
            public void curve(float a, float b, float c, float d, float e, float f, float g, float h, int s) { r.curve(a, b, c, d, e, f, g, h, s); }
            public void setProjectionMatrix(com.badlogic.gdx.math.Matrix4 m) { r.setProjectionMatrix(m); }
            public void identity() { r.identity(); }
            public void translate(float x, float y, float z) { r.translate(x, y, z); }
            public void rotate(float ax, float ay, float az, float deg) { r.rotate(ax, ay, az, deg); }
            public void box(float x, float y, float z, float w, float h, float d) { r.box(x, y, z, w, h, d); }
            public void dispose() { r.dispose(); }
        };
    }
}
