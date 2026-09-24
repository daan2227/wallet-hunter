#pragma once
/* Prueba de rendimiento de la GPU con Vulkan: cuantas multiplicaciones de
 * cuerpo (256 bits, modulo p) hace por segundo, comprobando antes que dan lo
 * mismo que fe_mul en la CPU.
 *
 * Es un primer paso para decidir si merece la pena llevar Kangaroo a la GPU
 * del movil. No busca nada: solo mide.
 *
 * El shader es campo.comp, compilado a SPIR-V y metido en campo_spv.h. */
#include <vulkan/vulkan.h>
#include <string>
#include <sstream>
#include <vector>
#include <chrono>
#include <string.h>
#include "../jac_batch.h"

static std::string gpu_bench(const uint32_t *spv, size_t spv_bytes,
                             const char *etiqueta="", bool con_nombre=true){
    std::ostringstream o;
    VkInstance inst=VK_NULL_HANDLE; VkDevice dev=VK_NULL_HANDLE;
    VkBuffer buf=VK_NULL_HANDLE; VkDeviceMemory mem=VK_NULL_HANDLE;
    VkShaderModule mod=VK_NULL_HANDLE; VkDescriptorSetLayout dsl=VK_NULL_HANDLE;
    VkPipelineLayout pl=VK_NULL_HANDLE; VkPipeline pipe=VK_NULL_HANDLE;
    VkDescriptorPool dp=VK_NULL_HANDLE; VkCommandPool cp=VK_NULL_HANDLE;
    VkFence fence=VK_NULL_HANDLE;
    auto fin=[&](const char *msg)->std::string{
        if(msg) o<<msg<<"\n";
        if(dev){
            if(fence) vkDestroyFence(dev,fence,NULL);
            if(cp) vkDestroyCommandPool(dev,cp,NULL);
            if(dp) vkDestroyDescriptorPool(dev,dp,NULL);
            if(pipe) vkDestroyPipeline(dev,pipe,NULL);
            if(pl) vkDestroyPipelineLayout(dev,pl,NULL);
            if(dsl) vkDestroyDescriptorSetLayout(dev,dsl,NULL);
            if(mod) vkDestroyShaderModule(dev,mod,NULL);
            if(buf) vkDestroyBuffer(dev,buf,NULL);
            if(mem) vkFreeMemory(dev,mem,NULL);
            vkDestroyDevice(dev,NULL);
        }
        if(inst) vkDestroyInstance(inst,NULL);
        return o.str();
    };

    VkApplicationInfo app{VK_STRUCTURE_TYPE_APPLICATION_INFO};
    app.pApplicationName="WalletHunter"; app.apiVersion=VK_API_VERSION_1_0;
    VkInstanceCreateInfo ici{VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO}; ici.pApplicationInfo=&app;
    if(vkCreateInstance(&ici,NULL,&inst)!=VK_SUCCESS) return fin("GPU: no Vulkan on this phone");

    uint32_t nd=0; vkEnumeratePhysicalDevices(inst,&nd,NULL);
    if(!nd) return fin("GPU: no Vulkan device");
    std::vector<VkPhysicalDevice> pds(nd); vkEnumeratePhysicalDevices(inst,&nd,pds.data());
    VkPhysicalDevice pd=pds[0];
    VkPhysicalDeviceProperties props; vkGetPhysicalDeviceProperties(pd,&props);
    if(con_nombre) o<<"GPU: "<<props.deviceName<<"\n";

    uint32_t nq=0; vkGetPhysicalDeviceQueueFamilyProperties(pd,&nq,NULL);
    std::vector<VkQueueFamilyProperties> qf(nq); vkGetPhysicalDeviceQueueFamilyProperties(pd,&nq,qf.data());
    int fam=-1; for(uint32_t i=0;i<nq;i++) if(qf[i].queueFlags&VK_QUEUE_COMPUTE_BIT){ fam=(int)i; break; }
    if(fam<0) return fin("GPU: no compute queue");

    float prio=1.0f;
    VkDeviceQueueCreateInfo qci{VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO};
    qci.queueFamilyIndex=(uint32_t)fam; qci.queueCount=1; qci.pQueuePriorities=&prio;
    VkDeviceCreateInfo dci{VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO}; dci.queueCreateInfoCount=1; dci.pQueueCreateInfos=&qci;
    if(vkCreateDevice(pd,&dci,NULL,&dev)!=VK_SUCCESS) return fin("GPU: could not open the device");
    VkQueue q; vkGetDeviceQueue(dev,(uint32_t)fam,0,&q);

    /* Cuantas invocaciones y cuantas multiplicaciones cada una. */
    const uint32_t INV=65536, N=256;
    const VkDeviceSize bytes=(VkDeviceSize)INV*8*4;
    VkBufferCreateInfo bci{VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO};
    bci.size=bytes; bci.usage=VK_BUFFER_USAGE_STORAGE_BUFFER_BIT; bci.sharingMode=VK_SHARING_MODE_EXCLUSIVE;
    if(vkCreateBuffer(dev,&bci,NULL,&buf)!=VK_SUCCESS) return fin("GPU: buffer");
    VkMemoryRequirements mr; vkGetBufferMemoryRequirements(dev,buf,&mr);
    VkPhysicalDeviceMemoryProperties mp; vkGetPhysicalDeviceMemoryProperties(pd,&mp);
    int tipo=-1;
    for(uint32_t i=0;i<mp.memoryTypeCount;i++)
        if((mr.memoryTypeBits&(1u<<i)) && (mp.memoryTypes[i].propertyFlags&(VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT|VK_MEMORY_PROPERTY_HOST_COHERENT_BIT))==(VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT|VK_MEMORY_PROPERTY_HOST_COHERENT_BIT)){ tipo=(int)i; break; }
    if(tipo<0) return fin("GPU: no host-visible memory");
    VkMemoryAllocateInfo mai{VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO}; mai.allocationSize=mr.size; mai.memoryTypeIndex=(uint32_t)tipo;
    if(vkAllocateMemory(dev,&mai,NULL,&mem)!=VK_SUCCESS) return fin("GPU: memory");
    vkBindBufferMemory(dev,buf,mem,0);

    /* Datos de partida: un valor al azar (< p) por invocacion. */
    uint32_t *d=NULL; vkMapMemory(dev,mem,0,bytes,0,(void**)&d);
    std::vector<uint32_t> ini(INV*8);
    uint64_t s=0x9E3779B97F4A7C15ULL;
    auto rnd=[&]{ s^=s<<13; s^=s>>7; s^=s<<17; return s; };
    for(uint32_t i=0;i<INV;i++){ for(int w=0;w<8;w++) ini[i*8+w]=(uint32_t)rnd(); ini[i*8+7]&=0x7FFFFFFF; }
    /* Las primeras, valores limite: 0, 1, p-1, p-2 y numeros con todo a unos
       por arriba. Es donde falla una reduccion mal hecha. */
    {
        const uint32_t P[8]={0xFFFFFC2Fu,0xFFFFFFFEu,0xFFFFFFFFu,0xFFFFFFFFu,0xFFFFFFFFu,0xFFFFFFFFu,0xFFFFFFFFu,0xFFFFFFFFu};
        for(int w=0;w<8;w++){ ini[0*8+w]=0; ini[1*8+w]=(w==0); ini[2*8+w]=P[w]-(w==0); ini[3*8+w]=P[w]-(w==0?2:0);
                              ini[4*8+w]=(w>=4)?0xFFFFFFFFu:0; ini[5*8+w]=P[w]-(w==0?0x1000u:0); }
    }
    memcpy(d,ini.data(),bytes);
    uint32_t b8[8]; for(int w=0;w<8;w++) b8[w]=(uint32_t)rnd(); b8[7]&=0x7FFFFFFF;

    VkShaderModuleCreateInfo smi{VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO}; smi.codeSize=spv_bytes; smi.pCode=spv;
    if(vkCreateShaderModule(dev,&smi,NULL,&mod)!=VK_SUCCESS) return fin("GPU: shader");
    VkDescriptorSetLayoutBinding lb{}; lb.binding=0; lb.descriptorType=VK_DESCRIPTOR_TYPE_STORAGE_BUFFER; lb.descriptorCount=1; lb.stageFlags=VK_SHADER_STAGE_COMPUTE_BIT;
    VkDescriptorSetLayoutCreateInfo dli{VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO}; dli.bindingCount=1; dli.pBindings=&lb;
    vkCreateDescriptorSetLayout(dev,&dli,NULL,&dsl);
    VkPushConstantRange pcr{VK_SHADER_STAGE_COMPUTE_BIT,0,9*4};
    VkPipelineLayoutCreateInfo pli{VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO}; pli.setLayoutCount=1; pli.pSetLayouts=&dsl; pli.pushConstantRangeCount=1; pli.pPushConstantRanges=&pcr;
    vkCreatePipelineLayout(dev,&pli,NULL,&pl);
    VkComputePipelineCreateInfo cpi{VK_STRUCTURE_TYPE_COMPUTE_PIPELINE_CREATE_INFO};
    cpi.stage.sType=VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO; cpi.stage.stage=VK_SHADER_STAGE_COMPUTE_BIT; cpi.stage.module=mod; cpi.stage.pName="main";
    cpi.layout=pl;
    if(vkCreateComputePipelines(dev,VK_NULL_HANDLE,1,&cpi,NULL,&pipe)!=VK_SUCCESS) return fin("GPU: pipeline");
    VkDescriptorPoolSize ps{VK_DESCRIPTOR_TYPE_STORAGE_BUFFER,1};
    VkDescriptorPoolCreateInfo dpi{VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO}; dpi.maxSets=1; dpi.poolSizeCount=1; dpi.pPoolSizes=&ps;
    vkCreateDescriptorPool(dev,&dpi,NULL,&dp);
    VkDescriptorSetAllocateInfo dsa{VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO}; dsa.descriptorPool=dp; dsa.descriptorSetCount=1; dsa.pSetLayouts=&dsl;
    VkDescriptorSet ds; vkAllocateDescriptorSets(dev,&dsa,&ds);
    VkDescriptorBufferInfo dbi{buf,0,bytes};
    VkWriteDescriptorSet wds{VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET}; wds.dstSet=ds; wds.dstBinding=0; wds.descriptorCount=1; wds.descriptorType=VK_DESCRIPTOR_TYPE_STORAGE_BUFFER; wds.pBufferInfo=&dbi;
    vkUpdateDescriptorSets(dev,1,&wds,0,NULL);
    VkCommandPoolCreateInfo cpci{VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO}; cpci.queueFamilyIndex=(uint32_t)fam;
    vkCreateCommandPool(dev,&cpci,NULL,&cp);
    VkFenceCreateInfo fci{VK_STRUCTURE_TYPE_FENCE_CREATE_INFO}; vkCreateFence(dev,&fci,NULL,&fence);

    auto lanzar=[&](uint32_t n,uint32_t invs)->double{
        VkCommandBufferAllocateInfo cba{VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO}; cba.commandPool=cp; cba.level=VK_COMMAND_BUFFER_LEVEL_PRIMARY; cba.commandBufferCount=1;
        VkCommandBuffer cb; vkAllocateCommandBuffers(dev,&cba,&cb);
        VkCommandBufferBeginInfo bi{VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO}; bi.flags=VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
        vkBeginCommandBuffer(cb,&bi);
        vkCmdBindPipeline(cb,VK_PIPELINE_BIND_POINT_COMPUTE,pipe);
        vkCmdBindDescriptorSets(cb,VK_PIPELINE_BIND_POINT_COMPUTE,pl,0,1,&ds,0,NULL);
        uint32_t pcv[9]={n,b8[0],b8[1],b8[2],b8[3],b8[4],b8[5],b8[6],b8[7]};
        vkCmdPushConstants(cb,pl,VK_SHADER_STAGE_COMPUTE_BIT,0,sizeof(pcv),pcv);
        vkCmdDispatch(cb,invs/64,1,1);
        vkEndCommandBuffer(cb);
        VkSubmitInfo si{VK_STRUCTURE_TYPE_SUBMIT_INFO}; si.commandBufferCount=1; si.pCommandBuffers=&cb;
        vkResetFences(dev,1,&fence);
        auto t0=std::chrono::steady_clock::now();
        vkQueueSubmit(q,1,&si,fence);
        vkWaitForFences(dev,1,&fence,VK_TRUE,60ULL*1000000000ULL);
        double seg=std::chrono::duration<double>(std::chrono::steady_clock::now()-t0).count();
        vkFreeCommandBuffers(dev,cp,1,&cb);
        return seg;
    };

    /* 1) Que de lo mismo que la CPU: 7 multiplicaciones encadenadas. */
    lanzar(7,INV);
    fe_t b; for(int w=0;w<4;w++) b[w]=(uint64_t)b8[2*w]|((uint64_t)b8[2*w+1]<<32);
    int mal=0;
    for(uint32_t i=0;i<INV;i++){
        fe_t a; for(int w=0;w<4;w++) a[w]=(uint64_t)ini[i*8+2*w]|((uint64_t)ini[i*8+2*w+1]<<32);
        for(int k=0;k<7;k++) fe_mul(a,a,b);
        for(int w=0;w<4;w++) if(d[i*8+2*w]!=(uint32_t)a[w] || d[i*8+2*w+1]!=(uint32_t)(a[w]>>32)) { mal++; break; }
    }
    o<<etiqueta<<"GPU vs CPU: "<<(mal?"DIFFERENT RESULTS ":"same results ")<<"("<<mal<<" of "<<INV<<")\n";
    if(mal) return fin(NULL);

    /* 2) Ritmo: se calienta y se mide. */
    lanzar(16,INV);
    double seg=lanzar(N,INV);
    double mps=(double)INV*N/seg/1e6;
    o<<etiqueta<<"GPU fe_mul: "<<(int)mps<<" M/s  ("<<INV<<" x "<<N<<" in "<<(int)(seg*1000)<<" ms)\n";
    vkUnmapMemory(dev,mem);
    return fin(NULL);
}
