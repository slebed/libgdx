# Upstream libGDX change: public buffer handle and dirty flag

**Status: prepared, not filed.** `0001-public-buffer-handle-and-dirty-flag.patch` applies cleanly to libGDX's `gdx` module (checked with
`git apply --check` against the 1.14.x sources). Nobody has opened a pull request on `libgdx/libgdx`; that is the owner's call.

## What it changes

Adds two public, read-only accessors to `VertexBufferObject`, `VertexBufferObjectSubData`, `VertexBufferObjectWithVAO`, `IndexBufferObject` and
`IndexBufferObjectSubData`:

```java
public int getBufferHandle ();   // the GL buffer name, 0 once disposed
public boolean isDirty ();       // the CPU-side data changed since it was last uploaded
```

Nothing else changes: no behaviour, no new field, no interface method (so no custom `VertexData`/`IndexData` implementation breaks).

## Why

A renderer that does not use `ShaderProgram` (a Vulkan backend, a deferred or compute-driven renderer) never calls `bind(ShaderProgram)`, which is the
only place stock libGDX uploads a `Mesh`'s data and clears `isDirty`. To draw stock meshes it has to find the buffer that stands for the mesh and refresh
it when the data changed. Today that means reading the package-private/private fields `bufferHandle` and `isDirty` by reflection, which breaks
silently if they are renamed and, on JDK 18+, allocates on every access.

## What gdx-vulkan does until it ships

`StockMeshAccess` reads the same fields through JNI field IDs (no allocation), checks at construction that they exist with the expected types
(`StockMeshFieldsTest` pins them), and falls back to reflection. When this patch is released, switching to the accessors is a three-line change and
the reflective path becomes the fallback for older libGDX versions.

## Suggested pull request text

> **Expose the GL buffer name and dirty flag of VertexBufferObject / IndexBufferObject**
>
> Backends and renderers that draw `Mesh` data without a `ShaderProgram` (Vulkan, compute-driven rendering) need to know which buffer holds a mesh's
> data and whether it must be re-uploaded. Both are already tracked (`bufferHandle`, `isDirty`) but not accessible, so such code reads private fields
> by reflection. This adds `getBufferHandle()` and `isDirty()` to the five concrete classes. No behaviour change.
