cmake_minimum_required(VERSION 3.22)
project(gdxvkdemo CXX)
# A few lines of JNI for GL_EXT_disjoint_timer_query, which the Android SDK has no binding for: the GPU time of the stock OpenGL ES demos.
add_library(gdxvkdemo SHARED gltimer.cpp)
find_library(EGL_LIB EGL)
find_library(GLES_LIB GLESv3)
find_library(LOG_LIB log)
target_link_libraries(gdxvkdemo ${EGL_LIB} ${GLES_LIB} ${LOG_LIB})
