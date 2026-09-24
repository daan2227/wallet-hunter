/* JNI de la prueba de la GPU. Aparte de hunter_jni.cpp para que todo lo de
 * Vulkan quede en su carpeta. */
#include <jni.h>
#include <stdint.h>
#include <sstream>
#include <vector>
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

/* Que GPU tiene este movil, sin medir nada: nombre, fabricante, tipo, version
 * de Vulkan y del driver, y cuanta memoria. Rapido: se llama al abrir ajustes. */
extern "C" JNIEXPORT jstring JNICALL
Java_com_hunter_btc_HunterEngine_gpuInfo(JNIEnv *env,jobject){
    VkApplicationInfo app{VK_STRUCTURE_TYPE_APPLICATION_INFO}; app.pApplicationName="WalletHunter"; app.apiVersion=VK_API_VERSION_1_0;
    VkInstanceCreateInfo ici{VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO}; ici.pApplicationInfo=&app;
    VkInstance inst;
    if(vkCreateInstance(&ici,NULL,&inst)!=VK_SUCCESS) return env->NewStringUTF("");
    std::ostringstream o;
    uint32_t nd=0; vkEnumeratePhysicalDevices(inst,&nd,NULL);
    if(nd){
        std::vector<VkPhysicalDevice> pds(nd); vkEnumeratePhysicalDevices(inst,&nd,pds.data());
        VkPhysicalDeviceProperties p; vkGetPhysicalDeviceProperties(pds[0],&p);
        const char *fab="";
        switch(p.vendorID){
            case 0x13B5: fab="ARM (Mali)"; break;
            case 0x5143: fab="Qualcomm (Adreno)"; break;
            case 0x144D: fab="Samsung (Xclipse)"; break;
            case 0x1010: fab="Imagination (PowerVR)"; break;
            case 0x1002: fab="AMD"; break;
            case 0x10DE: fab="NVIDIA"; break;
            case 0x8086: fab="Intel"; break;
            case 0x10005: fab="Mesa (software)"; break;
            default: fab="other"; break;
        }
        VkPhysicalDeviceMemoryProperties mp; vkGetPhysicalDeviceMemoryProperties(pds[0],&mp);
        unsigned long long mb=0;
        for(uint32_t i=0;i<mp.memoryHeapCount;i++) if(mp.memoryHeaps[i].flags&VK_MEMORY_HEAP_DEVICE_LOCAL_BIT) mb+=mp.memoryHeaps[i].size>>20;
        o<<p.deviceName<<"|"<<fab<<"|"
         <<VK_VERSION_MAJOR(p.apiVersion)<<"."<<VK_VERSION_MINOR(p.apiVersion)<<"."<<VK_VERSION_PATCH(p.apiVersion)<<"|"
         <<p.driverVersion<<"|"<<mb<<"|"<<p.limits.maxComputeWorkGroupInvocations;
    }
    vkDestroyInstance(inst,NULL);
    return env->NewStringUTF(o.str().c_str());
}
