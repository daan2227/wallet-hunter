/* Sustituye lo que hunter_jni.cpp toma de Android/JNI, para poder compilar
   build_and_sign_tx en el anfitrión y ejecutarlo de verdad. */
#ifndef HARNESS_SHIM_H
#define HARNESS_SHIM_H
#include <sys/mman.h>
#include <unistd.h>
#include <cstdint>
#include <cstddef>
#include <cstring>
#include <string>
typedef int32_t  jint;    typedef int64_t jlong;   typedef uint8_t jboolean;
typedef double   jdouble; typedef void*   jobject; typedef void* jclass;
typedef void*    jstring; typedef void*   jintArray; typedef void* jmethodID;
#define JNIEXPORT
#define JNICALL
#define JNIEnv void

/* Android log -> no-op */
#define ANDROID_LOG_INFO 4
#define ANDROID_LOG_ERROR 6
static inline int __android_log_print(int,const char*,const char*,...){return 0;}
#endif
