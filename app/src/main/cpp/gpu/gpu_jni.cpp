/* JNI de la prueba de la GPU. Aparte de hunter_jni.cpp para que todo lo de
 * Vulkan quede en su carpeta. */
#include <jni.h>
#include <stdint.h>
#include "gpu_bench.h"
#include "campo_spv.h"
#include "campo2_spv.h"

/* Dos shaders: campo (bucles y arrays, la primera version) y campo2
 * (desenrollado, todo en registros). Se miden los dos para saber cuanto
 * pierde la GPU por la forma del codigo y cuanto por ser la que es. */
extern "C" JNIEXPORT jstring JNICALL
Java_com_hunter_btc_HunterEngine_benchGpu(JNIEnv *env,jobject){
    std::string r=gpu_bench(CAMPO_SPV,sizeof(CAMPO_SPV),"v1 ",true);
    r+=gpu_bench(CAMPO2_SPV,sizeof(CAMPO2_SPV),"v2 ",false);
    return env->NewStringUTF(r.c_str());
}
