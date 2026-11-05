package io.github.slebed.gdxvulkan.glsl;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import io.github.slebed.gdxvulkan.GlslCompiler;

/**
 * Turns libGDX's old GLSL ({@code attribute}, {@code varying}, loose {@code uniform}s, {@code gl_FragColor}, {@code texture2D}) into Vulkan GLSL for the
 * library's one pipeline layout, and tells the caller where every uniform ended up. The design, and the reasons for each placement, are in
 * {@code docs/SHADER_TRANSLATOR.md}.
 *
 * <p>The source is expanded with glslang's preprocessor first, so {@code #ifdef} flags and macros are already resolved when declarations are read, and the
 * number of lines never changes: a message from the compiler still names the line of the original source (counting the {@code prepend} text, as stock's
 * {@code ShaderProgram} log does).
 */
public final class GlslTranslator {
    private GlslTranslator () {}

    /** Space for uniforms in the push constants (the pipeline layout has 128 bytes). */
    public static final int PUSH_BYTES = 128;
    /** The most the uniform ring can take for one program (the dynamic uniform buffer's range). */
    public static final int UNIFORM_BYTES = 16384;

    /** A GLSL program that cannot be translated; the message names the stage, the line and the construct. */
    public static final class GlslTranslationException extends RuntimeException {
        public GlslTranslationException (String message) {
            super(message);
        }
    }

    /** One loose uniform of the source and where it lives. */
    public static final class Uniform {
        public final String name;
        /** The GLSL type as written: {@code float}, {@code vec4}, {@code mat4}, {@code int}, {@code bool}, ... */
        public final String type;
        /** 0 when the uniform is not an array. */
        public final int arraySize;
        /** Whether the shaders read it. An inactive uniform takes no space ({@code offset} is -1) and setting it does nothing. */
        public boolean active;
        /** True: in the push constants. False: in the uniform ring (set 0, binding 0). */
        public boolean push;
        /** Byte offset inside the push constants or the uniform block. */
        public int offset = -1;
        /** Bytes the uniform takes there. */
        public int size;
        /** Bytes between the columns of a matrix, 0 for everything else. A mat3 has 16 although it holds 12 bytes of data per column. */
        public int columnStride;
        /** Bytes between array elements, 0 when it is not an array. */
        public int arrayStride;

        /** For a member of a struct uniform (named like {@code u_dirLights[1].color}): the struct uniform it belongs to; null otherwise. */
        StructUniform parent;
        /** The member's name inside the struct, and its element of the struct array. */
        String member;
        int element;

        Uniform (String name, String type, int arraySize) {
            this.name = name;
            this.type = type;
            this.arraySize = arraySize;
        }

        public boolean isBool () {
            return type.equals("bool");
        }

        @Override public String toString () {
            return type + " " + name + (arraySize > 0 ? "[" + arraySize + "]" : "") + (active ? (push ? " push@" : " ring@") + offset : " inactive");
        }
    }

    /** A {@code struct} whose members are float, int or vec/ivec types; it can be the type of a uniform. */
    static final class StructDef {
        final String name;
        final List<String[]> members = new ArrayList<String[]>(); // {type, name}

        StructDef (String name) {
            this.name = name;
        }
    }

    /** A uniform whose type is a struct (or an array of them). It lives in the uniform block; its members are listed as uniforms of their own. */
    static final class StructUniform {
        final String name;
        final StructDef type;
        final int arraySize;
        boolean active;
        int offset, stride;

        StructUniform (String name, StructDef type, int arraySize) {
            this.name = name;
            this.type = type;
            this.arraySize = arraySize;
        }
    }

    /** One vertex attribute and the location it was given. */
    public static final class Attribute {
        public final String name;
        public final String type;
        public final int location;

        Attribute (String name, String type, int location) {
            this.name = name;
            this.type = type;
            this.location = location;
        }
    }

    /** One {@code sampler2D}. */
    public static final class Sampler {
        public final String name;
        /** Where the texture's slot in the bindless table is kept in the push constants; -1 on the compatibility path (the one bound texture). */
        public int slotOffset = -1;
        public boolean active;

        Sampler (String name) {
            this.name = name;
        }
    }

    /** The outcome: the two shaders for the requested path and the tables to set uniforms, bind textures and build vertex input from. */
    public static final class Translation {
        public String vertex, fragment;
        public final List<Uniform> uniforms = new ArrayList<Uniform>();
        public final List<Attribute> attributes = new ArrayList<Attribute>();
        public final List<Sampler> samplers = new ArrayList<Sampler>();
        /** How many colour outputs the fragment shader writes (1 for {@code gl_FragColor}, more with {@code gl_FragData[n]}). */
        public int colorOutputs = 1;
        /** Bytes of push constants the program uses (a multiple of 4). */
        public int pushBytes;
        /** Bytes of the uniform block in the ring, 0 when everything fits in the push constants. */
        public int blockBytes;
        public boolean bindless;
        public final List<String> warnings = new ArrayList<String>();
        final Map<String, StructDef> structs = new LinkedHashMap<String, StructDef>();
        final List<StructUniform> structUniforms = new ArrayList<StructUniform>();

        StructUniform structUniform (String name) {
            for (StructUniform s : structUniforms) if (s.name.equals(name)) return s;
            return null;
        }

        public Uniform uniform (String name) {
            for (Uniform u : uniforms) if (u.name.equals(name)) return u;
            return null;
        }

        public Attribute attribute (String name) {
            for (Attribute a : attributes) if (a.name.equals(name)) return a;
            return null;
        }

        public Sampler sampler (String name) {
            for (Sampler s : samplers) if (s.name.equals(name)) return s;
            return null;
        }
    }

    // ---------------------------------------------------------------------------------------------------------------------------- translate

    /**
     * @param name shown in messages
     * @param prependVertex text put in front of the vertex source, like {@code ShaderProgram.prependVertexCode}; may be null
     * @param bindless true for the modern path (textures through the bindless table), false for the compatibility path (one bound texture)
     */
    public static Translation translate (String name, String vertexSource, String fragmentSource, String prependVertex, String prependFragment,
        boolean bindless) {
        final Translation t = new Translation();
        t.bindless = bindless;
        final Stage v = new Stage(GlslCompiler.Stage.VERTEX, name + ".vert", join(prependVertex, vertexSource));
        final Stage f = new Stage(GlslCompiler.Stage.FRAGMENT, name + ".frag", join(prependFragment, fragmentSource));
        v.scan(t);
        f.scan(t);

        // Varyings: the vertex shader decides the locations; the fragment shader must use a subset with the same types.
        final Map<String, Varying> varyings = new LinkedHashMap<String, Varying>();
        int nextLocation = 0;
        for (Varying out : v.varyings) {
            out.location = nextLocation;
            nextLocation += locationsOf(out.type); // a mat3 takes three locations, a mat4 four
            varyings.put(out.name, out);
        }
        for (Varying in : f.varyings) {
            final Varying out = varyings.get(in.name);
            if (out == null) throw new GlslTranslationException(f.name + ":" + in.line + ": the varying '" + in.name + "' is read by the fragment shader but the vertex shader does not declare it");
            if (!out.type.equals(in.type)) throw new GlslTranslationException(f.name + ":" + in.line + ": the varying '" + in.name + "' is " + in.type + " here and " + out.type + " in the vertex shader");
            in.location = out.location;
        }

        assignUniforms(t, v, f);
        v.emit(t, varyings);
        f.emit(t, varyings);
        t.vertex = v.output;
        t.fragment = f.output;
        return t;
    }

    private static String join (String prepend, String source) {
        return (prepend == null ? "" : prepend) + source;
    }

    private static final class Varying {
        final String name, type, flat;
        final int line;
        int location;

        Varying (String name, String type, String flat, int line) {
            this.name = name;
            this.type = type;
            this.flat = flat;
            this.line = line;
        }
    }

    /** One declaration found at the top level of a source. */
    private static final class Decl {
        final int start, end; // the statement's characters in the expanded text, the ';' included
        final String storage, type, flat;
        final List<String[]> names = new ArrayList<String[]>(); // {name, arraySize or ""}
        final int line;

        Decl (int start, int end, String storage, String type, String flat, int line) {
            this.start = start;
            this.end = end;
            this.storage = storage;
            this.type = type;
            this.flat = flat;
            this.line = line;
        }
    }

    // ------------------------------------------------------------------------------------------------------------------------------- one stage

    private static final Pattern DECL = Pattern.compile(
        "^((?:\\s*\\b(?:invariant|flat|smooth|centroid|highp|mediump|lowp|uniform|attribute|varying|in|out|const)\\b)+)\\s+([A-Za-z_]\\w*)\\s+([^;]*);\\s*$",
        Pattern.DOTALL);
    private static final Pattern STRUCT = Pattern.compile("^\\s*struct\\s+(\\w+)\\s*\\{([^{}]*)\\}\\s*;\\s*$", Pattern.DOTALL);
    private static final Pattern WORD =Pattern.compile("\\b(invariant|flat|smooth|centroid|highp|mediump|lowp|uniform|attribute|varying|in|out|const)\\b");

    private static final class Stage {
        final GlslCompiler.Stage stage;
        final String name;
        final String assembled;
        String text; // the expanded source, with the declarations still in it
        final List<Decl> decls = new ArrayList<Decl>();
        final List<Varying> varyings = new ArrayList<Varying>();
        final List<Attribute> attributes = new ArrayList<Attribute>();
        final List<String> fragmentOutputs = new ArrayList<String>();
        String output;

        Stage (GlslCompiler.Stage stage, String name, String assembled) {
            this.stage = stage;
            this.name = name;
            this.assembled = assembled;
        }

        boolean vertex () {
            return stage == GlslCompiler.Stage.VERTEX;
        }

        void fail (int line, String message) {
            throw new GlslTranslationException(name + ":" + line + ": " + message);
        }

        int lineAt (int index) {
            int line = 1;
            for (int i = 0; i < index && i < text.length(); i++) if (text.charAt(i) == '\n') line++;
            return line;
        }

        /** Preprocesses and finds the top-level declarations; adds the uniforms and samplers it meets to {@code t}. */
        void scan (Translation t) {
            final GlslCompiler.Preprocessed pp = GlslCompiler.preprocess(stage, name, assembled);
            if (!pp.ok()) throw new GlslTranslationException("preprocessing " + name + " failed:\n" + pp.log);
            text = pp.text.replace("\r\n", "\n");

            int depth = 0, paren = 0, start = 0;
            for (int i = 0; i < text.length(); i++) {
                final char c = text.charAt(i);
                if (c == '#' && depth == 0 && lineStart(i)) { // a directive left by the preprocessor (#extension, #pragma): not part of a statement
                    while (i < text.length() && text.charAt(i) != '\n') i++;
                    start = i;
                    continue;
                }
                if (c == '(') paren++;
                else if (c == ')') paren--;
                else if (c == '{') depth++;
                else if (c == '}') {
                    depth--;
                    if (depth == 0 && paren == 0) {
                        int j = i + 1;
                        while (j < text.length() && Character.isWhitespace(text.charAt(j))) j++;
                        if (j >= text.length() || text.charAt(j) != ';') start = i + 1; // a function body ends the statement; a struct waits for its ';'
                    }
                } else if (c == ';' && depth == 0 && paren == 0) {
                    classify(start, i + 1, t);
                    start = i + 1;
                }
            }
        }

        private boolean lineStart (int i) {
            for (int j = i - 1; j >= 0; j--) {
                final char c = text.charAt(j);
                if (c == '\n') return true;
                if (!Character.isWhitespace(c)) return false;
            }
            return true;
        }

        private void classify (int start, int end, Translation t) {
            final String statement = text.substring(start, end);
            if (statement.trim().startsWith("struct")) {
                final Matcher sm = STRUCT.matcher(statement);
                if (sm.matches()) recordStruct(t, sm.group(1), sm.group(2), start, end, lineAt(start));
                return; // a type definition
            }
            if (statement.trim().startsWith("layout")) return; // already Vulkan style
            final Matcher m = DECL.matcher(statement);
            if (!m.matches()) return;
            final String qualifiers = m.group(1);
            final String type = m.group(2);
            String storage = null, flat = "";
            final Matcher w = WORD.matcher(qualifiers);
            while (w.find()) {
                final String q = w.group(1);
                if (q.equals("uniform") || q.equals("attribute") || q.equals("varying") || q.equals("in") || q.equals("out")) storage = q;
                else if (q.equals("flat")) flat = "flat ";
            }
            if (storage == null) return; // const and plain globals stay as they are
            final int line = lineAt(start + (statement.length() - statement.replaceAll("^\\s+", "").length()));
            final Decl d = new Decl(start, end, storage, type, flat, line);
            for (String part : splitTopLevel(m.group(3))) {
                String declarator = part.trim();
                final int eq = declarator.indexOf('=');
                if (eq >= 0) {
                    t.warnings.add(name + ":" + line + ": the initial value of '" + declarator.substring(0, eq).trim() + "' is ignored; set it from the program");
                    declarator = declarator.substring(0, eq).trim();
                }
                String array = "";
                final int br = declarator.indexOf('[');
                if (br >= 0) {
                    array = declarator.substring(br + 1, declarator.indexOf(']')).trim();
                    declarator = declarator.substring(0, br).trim();
                }
                d.names.add(new String[] {declarator, array});
            }
            decls.add(d);

            switch (storage) {
            case "uniform":
                for (String[] n : d.names) addUniform(t, d, n);
                break;
            case "attribute":
                if (vertex()) for (String[] n : d.names) attributes.add(new Attribute(n[0], type, -1));
                else fail(line, "'attribute' is only allowed in a vertex shader");
                break;
            case "varying":
                for (String[] n : d.names) varyings.add(new Varying(n[0], type, flat, line));
                break;
            case "in":
                if (vertex()) for (String[] n : d.names) attributes.add(new Attribute(n[0], type, -1));
                else for (String[] n : d.names) varyings.add(new Varying(n[0], type, flat, line));
                break;
            default: // out
                if (vertex()) for (String[] n : d.names) varyings.add(new Varying(n[0], type, flat, line));
                else for (String[] n : d.names) fragmentOutputs.add(n[0]);
                break;
            }
        }

        /** Struct definitions found in this stage: {start, end} of the statement, and the name. */
        final List<int[]> structStatements = new ArrayList<int[]>();
        final List<String> structNames = new ArrayList<String>();

        private void recordStruct (Translation t, String structName, String body, int start, int end, int line) {
            final StructDef def = new StructDef(structName);
            for (String m : body.split(";")) {
                final String member = m.trim();
                if (member.isEmpty()) continue;
                final Matcher mm = Pattern.compile("^(?:(?:highp|mediump|lowp)\\s+)?(\\w+)\\s+(\\w+)$").matcher(member);
                if (!mm.matches()) return; // an array member or another form: the struct stays as it is, and cannot be a uniform type
                def.members.add(new String[] {mm.group(1), mm.group(2)});
            }
            for (String[] m : def.members) {
                if (!isBlockType(m[0]) || m[0].equals("bool") || m[0].startsWith("mat")) return; // only float, int, vecN and ivecN members
            }
            if (!t.structs.containsKey(structName)) t.structs.put(structName, def);
            structStatements.add(new int[] {start, end});
            structNames.add(structName);
        }

        private void addStructUniform (Translation t, Decl d, String[] n, int array) {
            final StructDef def = t.structs.get(d.type);
            if (t.structUniform(n[0]) != null) return; // declared in both stages
            final StructUniform su = new StructUniform(n[0], def, array);
            t.structUniforms.add(su);
            for (int e = 0; e < Math.max(1, array); e++) {
                for (String[] m : def.members) {
                    final Uniform leaf = new Uniform(array > 0 ? n[0] + "[" + e + "]." + m[1] : n[0] + "." + m[1], m[0], 0);
                    leaf.parent = su;
                    leaf.member = m[1];
                    leaf.element = e;
                    t.uniforms.add(leaf);
                }
            }
        }

        private void addUniform (Translation t, Decl d, String[] n) {
            final String type = d.type;
            int array = 0;
            if (!n[1].isEmpty()) {
                try {
                    array = Integer.parseInt(n[1]);
                } catch (NumberFormatException e) {
                    fail(d.line, "the array size of the uniform '" + n[0] + "' must be a number after preprocessing, not '" + n[1] + "'");
                }
            }
            if (t.structs.containsKey(type)) {
                addStructUniform(t, d, n, array);
                return;
            }
            if (type.equals("sampler2D")) {
                if (array > 0) fail(d.line, "an array of samplers ('" + n[0] + "') is not supported");
                if (t.sampler(n[0]) == null) t.samplers.add(new Sampler(n[0]));
                return;
            }
            if (type.startsWith("sampler") || type.startsWith("isampler") || type.startsWith("usampler")) {
                fail(d.line, "the uniform '" + n[0] + "' is a " + type + ": only sampler2D is supported (the texture shim has no cube or 3D textures yet)");
            }
            if (!isBlockType(type)) fail(d.line, "the uniform '" + n[0] + "' has the type '" + type + "', which is not supported (structs, bvec and other types are not)");
            if (type.equals("bool") && array > 0) fail(d.line, "an array of bool ('" + n[0] + "') is not supported");
            final Uniform existing = t.uniform(n[0]);
            if (existing != null) {
                if (!existing.type.equals(type) || existing.arraySize != array) {
                    fail(d.line, "the uniform '" + n[0] + "' is " + existing + " in the other shader and " + type + " here");
                }
                return;
            }
            t.uniforms.add(new Uniform(n[0], type, array));
        }

        /** Writes this stage's Vulkan GLSL. */
        void emit (Translation t, Map<String, Varying> varyings) {
            // The body: declarations replaced in place (same lines), identifiers renamed.
            final StringBuilder body = new StringBuilder(text.length() + 256);
            int at = 0;
            int attributeLocation = 0, outputLocation = 0;
            for (Decl d : decls) {
                body.append(text, at, d.start);
                final String original = text.substring(d.start, d.end);
                final String lead = original.substring(0, original.length() - original.replaceAll("^\\s+", "").length());
                final StringBuilder repl = new StringBuilder(lead);
                switch (d.storage) {
                case "uniform":
                    break; // declared in the blocks at the top
                case "attribute":
                case "in":
                    if (vertex()) {
                        for (String[] n : d.names) {
                            final int loc = attributeLocation;
                            attributeLocation += locationsOf(d.type);
                            if (vertex() && t.attribute(n[0]) == null) t.attributes.add(new Attribute(n[0], d.type, loc));
                            repl.append("layout(location = ").append(loc).append(") in ").append(d.type).append(' ').append(n[0]).append(arraySuffix(n[1])).append("; ");
                        }
                        break;
                    }
                    // a fragment 'in' is a varying
                    for (String[] n : d.names) repl.append(varyingText("in", d, n, varyings));
                    break;
                case "varying":
                    for (String[] n : d.names) repl.append(varyingText(vertex() ? "out" : "in", d, n, varyings));
                    break;
                default: // out
                    if (vertex()) for (String[] n : d.names) repl.append(varyingText("out", d, n, varyings));
                    else for (String[] n : d.names) repl.append("layout(location = ").append(outputLocation++).append(") out ").append(d.type).append(' ').append(n[0]).append("; ");
                    break;
                }
                for (int i = lead.length(); i < original.length(); i++) if (original.charAt(i) == '\n') repl.append('\n'); // keep the line count (the lead is kept as it is)
                body.append(repl);
                at = d.end;
            }
            body.append(text, at, text.length());
            String code = body.toString();

            code = code.replaceAll("(?m)^[ \\t]*#[ \\t]*version\\b.*$", ""); // we write the version
            code = code.replaceAll("(?m)^[ \\t]*#[ \\t]*extension[ \\t]+GL_OES_\\w+.*$", ""); // standard in Vulkan GLSL
            code = code.replaceAll("\\btexture2DProjLod\\b", "textureProjLod").replaceAll("\\btexture2DProj\\b", "textureProj")
                .replaceAll("\\btexture2DLod\\b", "textureLod").replaceAll("\\btexture2D\\b", "texture");
            if (vertex()) {
                code = Pattern.compile("\\bgl_Position\\s*=(?!=)([^;]*);").matcher(code).replaceAll("gl_Position = gdx_vk_clip($1);");
            } else {
                final boolean usesData = Pattern.compile("\\bgl_FragData\\s*\\[").matcher(code).find();
                if (usesData) {
                    final Matcher fd = Pattern.compile("\\bgl_FragData\\s*\\[\\s*(\\d+)\\s*\\]").matcher(code);
                    int max = 0;
                    final StringBuffer sb = new StringBuffer();
                    while (fd.find()) {
                        max = Math.max(max, Integer.parseInt(fd.group(1)));
                        fd.appendReplacement(sb, "gdx_FragData" + fd.group(1));
                    }
                    fd.appendTail(sb);
                    code = sb.toString();
                    outputLocation = 0;
                    final StringBuilder outs = new StringBuilder();
                    for (int i = 0; i <= max; i++) outs.append("layout(location = ").append(i).append(") out vec4 gdx_FragData").append(i).append("; ");
                    fragmentPrelude = outs.toString();
                    t.colorOutputs = max + 1;
                }
                if (Pattern.compile("\\bgl_FragColor\\b").matcher(code).find()) {
                    code = code.replaceAll("\\bgl_FragColor\\b", "gdx_FragColor");
                    fragmentPrelude += "layout(location = 0) out vec4 gdx_FragColor; ";
                }
            }

            final StringBuilder out = new StringBuilder(code.length() + 1024);
            out.append("#version 450\n");
            if (t.bindless && !t.samplers.isEmpty()) out.append("#extension GL_EXT_nonuniform_qualifier : require\n");
            if (vertex()) {
                out.append("layout(constant_id = 0) const int GDX_PREROT = 0;\n");
                out.append("vec4 gdx_vk_clip(vec4 p) { p.z = 0.5 * (p.z + p.w); if (GDX_PREROT == 1) p.xy = vec2(p.y, -p.x); "
                    + "else if (GDX_PREROT == 2) p.xy = -p.xy; else if (GDX_PREROT == 3) p.xy = vec2(-p.y, p.x); return p; }\n");
            }
            appendBlocks(out, t, stripDeclarations(this));
            out.append(fragmentPrelude).append('\n');
            out.append("#line 1\n");
            out.append(code);
            output = out.toString();
        }

        private String fragmentPrelude = "";

        private String varyingText (String direction, Decl d, String[] n, Map<String, Varying> varyings) {
            final Varying v = varyings.get(n[0]);
            if (v == null) fail(d.line, "the varying '" + n[0] + "' has no matching declaration in the vertex shader");
            if (!n[1].isEmpty()) fail(d.line, "the varying '" + n[0] + "' is an array, which is not supported");
            return "layout(location = " + v.location + ") " + v.flat + direction + " " + d.type + " " + n[0] + "; ";
        }
    }

    private static String arraySuffix (String size) {
        return size.isEmpty() ? "" : "[" + size + "]";
    }

    private static int locationsOf (String type) {
        if (type.equals("mat2")) return 2;
        if (type.equals("mat3")) return 3;
        if (type.equals("mat4")) return 4;
        return 1;
    }

    private static List<String> splitTopLevel (String s) {
        final List<String> parts = new ArrayList<String>();
        int depth = 0, last = 0;
        for (int i = 0; i < s.length(); i++) {
            final char c = s.charAt(i);
            if (c == '(' || c == '[') depth++;
            else if (c == ')' || c == ']') depth--;
            else if (c == ',' && depth == 0) {
                parts.add(s.substring(last, i));
                last = i + 1;
            }
        }
        parts.add(s.substring(last));
        return parts;
    }

    // ---------------------------------------------------------------------------------------------------------------------- uniform layout

    private static boolean isBlockType (String type) {
        switch (type) {
        case "float": case "int": case "bool": case "vec2": case "vec3": case "vec4": case "ivec2": case "ivec3": case "ivec4":
        case "mat2": case "mat3": case "mat4":
            return true;
        default:
            return false;
        }
    }

    /** {size, alignment, column stride} of a type outside an array. std140 pads the columns of a mat2 to 16 bytes; the push constants (std430) do not. */
    private static int[] layoutOf (String type, boolean std140) {
        switch (type) {
        case "float": case "int": case "bool": return new int[] {4, 4, 0};
        case "vec2": case "ivec2": return new int[] {8, 8, 0};
        case "vec3": case "ivec3": return new int[] {12, 16, 0};
        case "vec4": case "ivec4": return new int[] {16, 16, 0};
        case "mat2": return std140 ? new int[] {32, 16, 16} : new int[] {16, 8, 8};
        case "mat3": return new int[] {48, 16, 16};
        default: return new int[] {64, 16, 16}; // mat4
        }
    }

    private static int roundUp (int v, int to) {
        return (v + to - 1) / to * to;
    }

    private static String blockType (Uniform u) {
        return u.type.equals("bool") ? "int" : u.type;
    }

    /** Which uniforms are read, where each goes, and the byte offsets. */
    private static void assignUniforms (Translation t, Stage v, Stage f) {
        // The structs that are the type of a uniform are declared in the uniform block's prelude, in both stages: take their definitions out of the sources.
        for (Stage s : new Stage[] {v, f}) {
            final StringBuilder blanked = new StringBuilder(s.text);
            for (int i = 0; i < s.structStatements.size(); i++) {
                boolean used = false;
                for (StructUniform su : t.structUniforms) used |= su.type.name.equals(s.structNames.get(i));
                if (!used) continue;
                final int[] range = s.structStatements.get(i);
                for (int c = range[0]; c < range[1]; c++) if (blanked.charAt(c) != '\n') blanked.setCharAt(c, ' ');
            }
            s.text = blanked.toString();
        }
        final String bodies = stripDeclarations(v) + "\n" + stripDeclarations(f);
        for (Uniform u : t.uniforms) if (u.parent == null) u.active = Pattern.compile("\\b" + Pattern.quote(u.name) + "\\b").matcher(bodies).find();
        for (StructUniform su : t.structUniforms) su.active = Pattern.compile("\\b" + Pattern.quote(su.name) + "\\b").matcher(bodies).find();
        for (Uniform u : t.uniforms) if (u.parent != null) u.active = u.parent.active;
        for (Sampler s : t.samplers) s.active = Pattern.compile("\\b" + Pattern.quote(s.name) + "\\b").matcher(bodies).find();

        int active = 0;
        for (Sampler s : t.samplers) if (s.active) active++;
        if (!t.bindless && active > 1) {
            throw new GlslTranslationException(v.name + ": the program samples " + active + " textures; the compatibility path (Vulkan 1.1) can bind one texture per draw. "
                + "Use a driver with Vulkan 1.3, or sample one texture.");
        }

        // The texture slots (4 bytes each, modern path) go after the uniforms; their room is kept free while the uniforms are placed.
        final int slotBytes = t.bindless ? 4 * active : 0;
        int push = 0;
        int block = 0;
        for (Uniform u : t.uniforms) {
            if (!u.active || u.parent != null) continue;
            if (u.arraySize == 0) {
                final int[] l = layoutOf(u.type, false);
                final int at = roundUp(push, l[1]);
                if (at + l[0] <= PUSH_BYTES - slotBytes) {
                    u.push = true;
                    u.offset = at;
                    u.size = l[0];
                    u.columnStride = l[2];
                    push = at + l[0];
                    continue;
                }
            }
            // The uniform ring: std140.
            final int[] l = layoutOf(u.type, true);
            if (u.arraySize > 0) {
                final int stride = roundUp(l[0], 16);
                u.arrayStride = stride;
                u.columnStride = l[2];
                u.size = stride * u.arraySize;
                block = roundUp(block, 16);
                u.offset = block;
                block += u.size;
            } else {
                u.columnStride = l[2];
                u.size = l[0];
                block = roundUp(block, l[1]);
                u.offset = block;
                block += l[0];
            }
            if (block > UNIFORM_BYTES) {
                throw new GlslTranslationException(v.name + ": the uniforms need " + block + " bytes; the limit for one program is " + UNIFORM_BYTES);
            }
        }
        // Struct uniforms, std140: the struct starts on 16 bytes, its members follow their own alignment, its size and the array stride round up to 16.
        for (StructUniform su : t.structUniforms) {
            if (!su.active) continue;
            final int[] memberOffset = new int[su.type.members.size()];
            int at = 0;
            for (int i = 0; i < memberOffset.length; i++) {
                final int[] l = layoutOf(su.type.members.get(i)[0], true);
                at = roundUp(at, l[1]);
                memberOffset[i] = at;
                at += l[0];
            }
            su.stride = roundUp(at, 16);
            block = roundUp(block, 16);
            su.offset = block;
            block += su.stride * Math.max(1, su.arraySize);
            if (block > UNIFORM_BYTES) {
                throw new GlslTranslationException(v.name + ": the uniforms need " + block + " bytes; the limit for one program is " + UNIFORM_BYTES);
            }
            for (Uniform u : t.uniforms) {
                if (u.parent != su) continue;
                for (int i = 0; i < memberOffset.length; i++) {
                    if (!su.type.members.get(i)[1].equals(u.member)) continue;
                    u.push = false;
                    u.offset = su.offset + u.element * su.stride + memberOffset[i];
                    u.size = layoutOf(u.type, true)[0];
                }
            }
        }
        if (t.bindless) {
            for (Sampler sm : t.samplers) {
                if (!sm.active) continue;
                sm.slotOffset = roundUp(push, 4);
                push = sm.slotOffset + 4;
            }
        }
        t.pushBytes = roundUp(push, 4);
        t.blockBytes = roundUp(block, 16);
    }

    /** The source with the declarations removed, to find which names the code reads. */
    private static String stripDeclarations (Stage s) {
        final StringBuilder b = new StringBuilder();
        int at = 0;
        for (Decl d : s.decls) {
            b.append(s.text, at, d.start);
            at = d.end;
        }
        b.append(s.text, at, s.text.length());
        return b.toString();
    }

    /** Whether the code reads a name. A texture is declared only in the stage that reads it: an unused unsized array would be sized to one element. */
    private static boolean reads (String body, String name) {
        return Pattern.compile("\\b" + Pattern.quote(name) + "\\b").matcher(body).find();
    }

    private static void appendBlocks (StringBuilder out, Translation t, String body) {
        // Push constants: texture slots, then the uniforms that fit.
        final List<String> pushMembers = new ArrayList<String>();
        for (Sampler s : t.samplers) if (s.active && s.slotOffset >= 0) pushMembers.add("layout(offset = " + s.slotOffset + ") int gdx_slot_" + s.name + ";");
        final List<Uniform> pushUniforms = new ArrayList<Uniform>();
        final List<Uniform> ringUniforms = new ArrayList<Uniform>();
        for (Uniform u : t.uniforms) {
            if (!u.active || u.parent != null) continue;
            (u.push ? pushUniforms : ringUniforms).add(u);
        }
        final StringBuilder structText = new StringBuilder();
        final java.util.Set<String> written = new java.util.HashSet<String>();
        for (StructUniform su : t.structUniforms) {
            if (!su.active || !written.add(su.type.name)) continue;
            structText.append("struct ").append(su.type.name).append(" { ");
            for (String[] m : su.type.members) structText.append(m[0]).append(' ').append(m[1]).append("; ");
            structText.append("};\n");
        }
        out.append(structText);
        final StringBuilder defines = new StringBuilder();
        for (Uniform u : pushUniforms) {
            if (u.isBool()) {
                pushMembers.add("layout(offset = " + u.offset + ") int gdx_b_" + u.name + ";");
                defines.append("#define ").append(u.name).append(" (gdx_b_").append(u.name).append(" != 0)\n");
            } else {
                pushMembers.add("layout(offset = " + u.offset + ") " + u.type + " " + u.name + ";");
            }
        }
        if (!pushMembers.isEmpty()) {
            out.append("layout(push_constant) uniform GdxPush { ");
            for (String m : pushMembers) out.append(m).append(' ');
            out.append("};\n");
        }
        boolean anyStruct = false;
        for (StructUniform su : t.structUniforms) anyStruct |= su.active;
        if (!ringUniforms.isEmpty() || anyStruct) {
            out.append("layout(std140, set = 0, binding = 0) uniform GdxUniforms { ");
            for (StructUniform su : t.structUniforms) {
                if (su.active) out.append("layout(offset = ").append(su.offset).append(") ").append(su.type.name).append(' ').append(su.name)
                    .append(su.arraySize > 0 ? "[" + su.arraySize + "]" : "").append("; ");
            }
            for (Uniform u : ringUniforms) {
                if (u.isBool()) {
                    out.append("layout(offset = ").append(u.offset).append(") int gdx_b_").append(u.name).append("; ");
                    defines.append("#define ").append(u.name).append(" (gdx_b_").append(u.name).append(" != 0)\n");
                } else {
                    out.append("layout(offset = ").append(u.offset).append(") ").append(u.type).append(' ').append(u.name)
                        .append(u.arraySize > 0 ? "[" + u.arraySize + "]" : "").append("; ");
                }
            }
            out.append("};\n");
        }
        // Textures.
        boolean any = false;
        for (Sampler s : t.samplers) any |= s.active && reads(body, s.name);
        if (any) {
            if (t.bindless) {
                out.append("layout(set = 1, binding = 0) uniform sampler2D gdx_textures[];\n");
                for (Sampler s : t.samplers) {
                    if (s.active && reads(body, s.name)) {
                        defines.append("#define ").append(s.name).append(" gdx_textures[nonuniformEXT(gdx_slot_").append(s.name).append(")]\n");
                    }
                }
            } else {
                for (Sampler s : t.samplers) {
                    if (s.active && reads(body, s.name)) out.append("layout(set = 1, binding = 0) uniform sampler2D ").append(s.name).append(";\n");
                }
            }
        }
        out.append(defines);
    }
}
