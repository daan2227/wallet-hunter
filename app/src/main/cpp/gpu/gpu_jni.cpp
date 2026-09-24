/* JNI de la prueba de la GPU. Aparte de hunter_jni.cpp para que todo lo de
 * Vulkan quede en su carpeta. */
#include <jni.h>
#include <stdint.h>
#include "gpu_bench.h"
#include "campo_spv.h"

extern "C" JNIEXPORT jstring JNICALL
Java_com_hunter_btc_HunterEngine_benchGpu(JNIEnv *env,jobject){
    std::string r=gpu_bench(CAMPO_SPV,sizeof(CAMPO_SPV));
    return env->NewStringUTF(r.c_str());
}
