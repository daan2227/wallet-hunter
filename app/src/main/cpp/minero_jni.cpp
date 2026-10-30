/* JNI del minero (minero.h): una tanda de nonces sobre una cabecera de 80 bytes. */
#include <jni.h>
#include "minero.h"

/* [salida] recibe {nonce que cumple o -1, ceros del mejor hash, nonce del mejor}. Devuelve los hashes hechos. */
extern "C" JNIEXPORT jlong JNICALL
Java_com_hunter_btc_HunterEngine_minar(JNIEnv *env, jobject, jbyteArray jcab, jint desde, jint cuantos, jbyteArray jobj, jlongArray jsal){
    if(!jcab || !jobj || !jsal || env->GetArrayLength(jcab) < 80 || env->GetArrayLength(jobj) != 32 || env->GetArrayLength(jsal) < 3 || cuantos <= 0) return -1;
    uint8_t cab[80], o[32];
    env->GetByteArrayRegion(jcab, 0, 80, (jbyte*)cab);
    env->GetByteArrayRegion(jobj, 0, 32, (jbyte*)o);
    uint32_t obj[8];
    for(int i=0;i<8;i++) obj[i]=((uint32_t)o[4*i]<<24)|((uint32_t)o[4*i+1]<<16)|((uint32_t)o[4*i+2]<<8)|o[4*i+3];
    MinResultado r;
    uint64_t h=min_tanda_auto(cab,(uint32_t)desde,(uint32_t)cuantos,obj,&r);
    jlong s[3]={(jlong)r.encontrado,(jlong)r.mejor_ceros,(jlong)r.mejor_nonce};
    env->SetLongArrayRegion(jsal,0,3,s);
    return (jlong)h;
}

/* ¿Usa las instrucciones SHA-256 del procesador? */
extern "C" JNIEXPORT jboolean JNICALL
Java_com_hunter_btc_HunterEngine_minarConHw(JNIEnv *, jobject){
#ifdef SHA256_HW
    return sha256_tiene_hw() ? JNI_TRUE : JNI_FALSE;
#else
    return JNI_FALSE;
#endif
}
