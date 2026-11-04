package io.github.slebed.gdxvulkan.glsl;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.Charset;
import java.security.MessageDigest;

import io.github.slebed.gdxvulkan.glsl.GlslTranslator.Attribute;
import io.github.slebed.gdxvulkan.glsl.GlslTranslator.Sampler;
import io.github.slebed.gdxvulkan.glsl.GlslTranslator.Translation;
import io.github.slebed.gdxvulkan.glsl.GlslTranslator.Uniform;

/**
 * The compiled form of a {@link VulkanShaderProgram} on disk: both SPIR-V modules and the translation's tables, under a name made from a hash of everything that
 * decides the result (the sources and the text put in front of them, the code path, and the versions of the translator and the compiler). A program that is
 * found here is created without running the preprocessor, the translator or the compiler at all. A file that cannot be read, or was written by another
 * version, is ignored and replaced; a failure to write is ignored too, the cache only saves time.
 */
final class ShaderCache {
    /** Changes whenever the translator's output or the file layout changes, so older files are never read. */
    private static final int FORMAT = 1;
    private static final String VERSIONS = "translator-1;glslang-16.6.0;spirv-1.3";
    private static final int MAGIC = 0x47564B53; // "GVKS"

    private ShaderCache () {}

    static final class Entry {
        Translation translation;
        byte[] vertex, fragment;
    }

    static String key (boolean bindless, String prependVertex, String vertexSource, String prependFragment, String fragmentSource) {
        try {
            final MessageDigest md = MessageDigest.getInstance("SHA-256");
            update(md, VERSIONS);
            update(md, bindless ? "bindless" : "single");
            update(md, prependVertex);
            update(md, vertexSource);
            update(md, prependFragment);
            update(md, fragmentSource);
            final byte[] d = md.digest();
            final StringBuilder hex = new StringBuilder(40);
            for (int i = 0; i < 20; i++) hex.append(Character.forDigit((d[i] >> 4) & 15, 16)).append(Character.forDigit(d[i] & 15, 16));
            return hex.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void update (MessageDigest md, String s) {
        final byte[] b = (s == null ? "" : s).getBytes(Charset.forName("UTF-8"));
        md.update((byte)(b.length >> 24));
        md.update((byte)(b.length >> 16));
        md.update((byte)(b.length >> 8));
        md.update((byte)b.length); // the length first, so ("ab", "c") and ("a", "bc") are different inputs
        md.update(b);
    }

    static Entry load (File dir, String key) {
        final File file = new File(dir, key + ".spvprog");
        if (!file.isFile()) return null;
        try {
            return read(new FileInputStream(file));
        } catch (IOException e) {
            return null;
        }
    }

    /** A program compiled when the library was built: the {@code shaders/bundle} folder of the jar (see {@code recordShaderBundle}). Null when there is none for this key. */
    static Entry loadBundled (String key) {
        try (java.io.InputStream in = ShaderCache.class.getResourceAsStream("/shaders/bundle/" + key + ".spvprog")) {
            return in == null ? null : read(in);
        } catch (IOException e) {
            return null;
        }
    }

    private static Entry read (java.io.InputStream stream) {
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(stream))) {
            if (in.readInt() != MAGIC || in.readInt() != FORMAT) return null;
            final Translation t = new Translation();
            t.bindless = in.readBoolean();
            t.pushBytes = in.readInt();
            t.blockBytes = in.readInt();
            t.colorOutputs = in.readInt();
            for (int n = in.readInt(); n > 0; n--) {
                final Uniform u = new Uniform(in.readUTF(), in.readUTF(), in.readInt());
                u.active = in.readBoolean();
                u.push = in.readBoolean();
                u.offset = in.readInt();
                u.size = in.readInt();
                u.columnStride = in.readInt();
                u.arrayStride = in.readInt();
                t.uniforms.add(u);
            }
            for (int n = in.readInt(); n > 0; n--) t.attributes.add(new Attribute(in.readUTF(), in.readUTF(), in.readInt()));
            for (int n = in.readInt(); n > 0; n--) {
                final Sampler s = new Sampler(in.readUTF());
                s.slotOffset = in.readInt();
                s.active = in.readBoolean();
                t.samplers.add(s);
            }
            final Entry e = new Entry();
            e.translation = t;
            e.vertex = new byte[in.readInt()];
            in.readFully(e.vertex);
            e.fragment = new byte[in.readInt()];
            in.readFully(e.fragment);
            if (in.readInt() != MAGIC) return null; // a file cut short
            return e;
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    static void save (File dir, String key, Translation t, byte[] vertex, byte[] fragment) {
        try {
            if (!dir.isDirectory() && !dir.mkdirs()) return;
            final File tmp = new File(dir, key + ".tmp" + Thread.currentThread().getId());
            try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(new FileOutputStream(tmp)))) {
                out.writeInt(MAGIC);
                out.writeInt(FORMAT);
                out.writeBoolean(t.bindless);
                out.writeInt(t.pushBytes);
                out.writeInt(t.blockBytes);
                out.writeInt(t.colorOutputs);
                out.writeInt(t.uniforms.size());
                for (Uniform u : t.uniforms) {
                    out.writeUTF(u.name);
                    out.writeUTF(u.type);
                    out.writeInt(u.arraySize);
                    out.writeBoolean(u.active);
                    out.writeBoolean(u.push);
                    out.writeInt(u.offset);
                    out.writeInt(u.size);
                    out.writeInt(u.columnStride);
                    out.writeInt(u.arrayStride);
                }
                out.writeInt(t.attributes.size());
                for (Attribute a : t.attributes) {
                    out.writeUTF(a.name);
                    out.writeUTF(a.type);
                    out.writeInt(a.location);
                }
                out.writeInt(t.samplers.size());
                for (Sampler s : t.samplers) {
                    out.writeUTF(s.name);
                    out.writeInt(s.slotOffset);
                    out.writeBoolean(s.active);
                }
                out.writeInt(vertex.length);
                out.write(vertex);
                out.writeInt(fragment.length);
                out.write(fragment);
                out.writeInt(MAGIC);
            }
            final File file = new File(dir, key + ".spvprog");
            if (!tmp.renameTo(file)) {
                file.delete(); // Windows does not rename over an existing file
                if (!tmp.renameTo(file)) tmp.delete();
            }
        } catch (IOException | RuntimeException e) {
            // the cache only saves time
        }
    }
}
