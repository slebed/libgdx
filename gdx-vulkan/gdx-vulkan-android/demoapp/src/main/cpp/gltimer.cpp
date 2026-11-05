// GL_EXT_disjoint_timer_query for the OpenGL ES side of the demo app: begin and end a time-elapsed query and read the result later.
#include <EGL/egl.h>
#include <GLES3/gl3.h>
#include <jni.h>

namespace {
typedef void (*GenQueries)(GLsizei, GLuint*);
typedef void (*DeleteQueries)(GLsizei, const GLuint*);
typedef void (*BeginQuery)(GLenum, GLuint);
typedef void (*EndQuery)(GLenum);
typedef void (*GetQueryObjectuiv)(GLuint, GLenum, GLuint*);
typedef void (*GetQueryObjectui64v)(GLuint, GLenum, unsigned long long*);

GenQueries genQueries;
DeleteQueries deleteQueries;
BeginQuery beginQuery;
EndQuery endQuery;
GetQueryObjectuiv getQueryObjectuiv;
GetQueryObjectui64v getQueryObjectui64v;
GLuint queries[8];
const GLenum TIME_ELAPSED = 0x88BF, RESULT = 0x8866, RESULT_AVAILABLE = 0x8867;
}

extern "C" {
// Call with the GL context current. Returns the number of queries made, 0 when the phone does not have the extension.
JNIEXPORT jint JNICALL Java_io_github_slebed_gdxvulkan_demoapp_GlesTimer_nativeInit(JNIEnv*, jclass, jint count) {
    genQueries = (GenQueries)eglGetProcAddress("glGenQueriesEXT");
    deleteQueries = (DeleteQueries)eglGetProcAddress("glDeleteQueriesEXT");
    beginQuery = (BeginQuery)eglGetProcAddress("glBeginQueryEXT");
    endQuery = (EndQuery)eglGetProcAddress("glEndQueryEXT");
    getQueryObjectuiv = (GetQueryObjectuiv)eglGetProcAddress("glGetQueryObjectuivEXT");
    getQueryObjectui64v = (GetQueryObjectui64v)eglGetProcAddress("glGetQueryObjectui64vEXT");
    if (!genQueries || !beginQuery || !endQuery || !getQueryObjectuiv || !getQueryObjectui64v) return 0;
    const jint n = count > 8 ? 8 : count;
    genQueries(n, queries);
    return n;
}

JNIEXPORT void JNICALL Java_io_github_slebed_gdxvulkan_demoapp_GlesTimer_nativeBegin(JNIEnv*, jclass, jint slot) { beginQuery(TIME_ELAPSED, queries[slot]); }

JNIEXPORT void JNICALL Java_io_github_slebed_gdxvulkan_demoapp_GlesTimer_nativeEnd(JNIEnv*, jclass) { endQuery(TIME_ELAPSED); }

JNIEXPORT jboolean JNICALL Java_io_github_slebed_gdxvulkan_demoapp_GlesTimer_nativeAvailable(JNIEnv*, jclass, jint slot) {
    GLuint available = 0;
    getQueryObjectuiv(queries[slot], RESULT_AVAILABLE, &available);
    return available != 0;
}

JNIEXPORT jlong JNICALL Java_io_github_slebed_gdxvulkan_demoapp_GlesTimer_nativeResult(JNIEnv*, jclass, jint slot) {
    unsigned long long nanos = 0;
    getQueryObjectui64v(queries[slot], RESULT, &nanos);
    return (jlong)nanos;
}
}
