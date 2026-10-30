#pragma once
/* El minero en la GPU con Vulkan (minero.comp → minero_spv.h). Dos buffers visibles desde la CPU: los
 * parámetros de la tanda y el resultado. Cada tanda se prepara, se lanza y se espera; quien llama ajusta su
 * tamaño para que dure ~0,1 s (Android corta los trabajos de GPU que tardan segundos). Requiere minero.h. */
#include <vulkan/vulkan.h>
#include <string>
#include <vector>
#include "minero_gpu_params.h"

struct GpuMin {
    VkInstance inst=VK_NULL_HANDLE; VkPhysicalDevice pd=VK_NULL_HANDLE; VkDevice dev=VK_NULL_HANDLE;
    VkQueue q=VK_NULL_HANDLE; uint32_t fam=0;
    VkBuffer buf[2]={}; VkDeviceMemory mem[2]={}; void *map[2]={};
    VkShaderModule mod=VK_NULL_HANDLE; VkDescriptorSetLayout dsl=VK_NULL_HANDLE; VkPipelineLayout pl=VK_NULL_HANDLE;
    VkPipeline pipe=VK_NULL_HANDLE; VkDescriptorPool dp=VK_NULL_HANDLE; VkDescriptorSet ds=VK_NULL_HANDLE;
    VkCommandPool cp=VK_NULL_HANDLE; VkCommandBuffer cb=VK_NULL_HANDLE; VkFence fence=VK_NULL_HANDLE;
    std::string nombre;
};

static const uint32_t GM_ITER=32;         /* nonces por invocación */
static const uint32_t GM_LOCAL=64;        /* invocaciones por grupo (local_size_x del shader) */

static void gpu_min_destruir(GpuMin *g){
    if(!g) return;
    if(g->dev){
        vkDeviceWaitIdle(g->dev);
        if(g->fence) vkDestroyFence(g->dev,g->fence,NULL);
        if(g->cp) vkDestroyCommandPool(g->dev,g->cp,NULL);
        if(g->dp) vkDestroyDescriptorPool(g->dev,g->dp,NULL);
        if(g->pipe) vkDestroyPipeline(g->dev,g->pipe,NULL);
        if(g->pl) vkDestroyPipelineLayout(g->dev,g->pl,NULL);
        if(g->dsl) vkDestroyDescriptorSetLayout(g->dev,g->dsl,NULL);
        if(g->mod) vkDestroyShaderModule(g->dev,g->mod,NULL);
        for(int i=0;i<2;i++){ if(g->map[i]) vkUnmapMemory(g->dev,g->mem[i]);
            if(g->buf[i]) vkDestroyBuffer(g->dev,g->buf[i],NULL);
            if(g->mem[i]) vkFreeMemory(g->dev,g->mem[i],NULL); }
        vkDestroyDevice(g->dev,NULL);
    }
    if(g->inst) vkDestroyInstance(g->inst,NULL);
    delete g;
}

static bool gm_buffer(GpuMin *g,int i,VkDeviceSize bytes,std::string &err){
    VkBufferCreateInfo bci{VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO};
    bci.size=bytes; bci.usage=VK_BUFFER_USAGE_STORAGE_BUFFER_BIT; bci.sharingMode=VK_SHARING_MODE_EXCLUSIVE;
    if(vkCreateBuffer(g->dev,&bci,NULL,&g->buf[i])!=VK_SUCCESS){ err="buffer"; return false; }
    VkMemoryRequirements mr; vkGetBufferMemoryRequirements(g->dev,g->buf[i],&mr);
    VkPhysicalDeviceMemoryProperties mp; vkGetPhysicalDeviceMemoryProperties(g->pd,&mp);
    const VkMemoryPropertyFlags want=VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT|VK_MEMORY_PROPERTY_HOST_COHERENT_BIT;
    int tipo=-1;
    for(uint32_t t=0;t<mp.memoryTypeCount;t++)
        if((mr.memoryTypeBits&(1u<<t)) && (mp.memoryTypes[t].propertyFlags&want)==want){ tipo=(int)t; break; }
    if(tipo<0){ err="no host-visible memory"; return false; }
    VkMemoryAllocateInfo mai{VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO}; mai.allocationSize=mr.size; mai.memoryTypeIndex=(uint32_t)tipo;
    if(vkAllocateMemory(g->dev,&mai,NULL,&g->mem[i])!=VK_SUCCESS){ err="memory"; return false; }
    vkBindBufferMemory(g->dev,g->buf[i],g->mem[i],0);
    if(vkMapMemory(g->dev,g->mem[i],0,bytes,0,&g->map[i])!=VK_SUCCESS){ err="map"; return false; }
    return true;
}

static GpuMin *gpu_min_crear(const uint32_t *spv,size_t spv_bytes,std::string &err){
    GpuMin *g=new GpuMin();
    VkApplicationInfo app{VK_STRUCTURE_TYPE_APPLICATION_INFO}; app.pApplicationName="WalletHunter"; app.apiVersion=VK_API_VERSION_1_0;
    VkInstanceCreateInfo ici{VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO}; ici.pApplicationInfo=&app;
    if(vkCreateInstance(&ici,NULL,&g->inst)!=VK_SUCCESS){ err="no Vulkan"; gpu_min_destruir(g); return NULL; }
    uint32_t nd=0; vkEnumeratePhysicalDevices(g->inst,&nd,NULL);
    if(!nd){ err="no Vulkan device"; gpu_min_destruir(g); return NULL; }
    std::vector<VkPhysicalDevice> pds(nd); vkEnumeratePhysicalDevices(g->inst,&nd,pds.data()); g->pd=pds[0];
    VkPhysicalDeviceProperties props; vkGetPhysicalDeviceProperties(g->pd,&props); g->nombre=props.deviceName;
    uint32_t nq=0; vkGetPhysicalDeviceQueueFamilyProperties(g->pd,&nq,NULL);
    std::vector<VkQueueFamilyProperties> qf(nq); vkGetPhysicalDeviceQueueFamilyProperties(g->pd,&nq,qf.data());
    int fam=-1; for(uint32_t i=0;i<nq;i++) if(qf[i].queueFlags&VK_QUEUE_COMPUTE_BIT){ fam=(int)i; break; }
    if(fam<0){ err="no compute queue"; gpu_min_destruir(g); return NULL; }
    g->fam=(uint32_t)fam;
    float prio=1.0f;
    VkDeviceQueueCreateInfo qci{VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO}; qci.queueFamilyIndex=g->fam; qci.queueCount=1; qci.pQueuePriorities=&prio;
    VkDeviceCreateInfo dci{VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO}; dci.queueCreateInfoCount=1; dci.pQueueCreateInfos=&qci;
    if(vkCreateDevice(g->pd,&dci,NULL,&g->dev)!=VK_SUCCESS){ err="device"; gpu_min_destruir(g); return NULL; }
    vkGetDeviceQueue(g->dev,g->fam,0,&g->q);
    if(!gm_buffer(g,0,sizeof(MinGpuParams),err) || !gm_buffer(g,1,sizeof(MinGpuRes),err)){ gpu_min_destruir(g); return NULL; }

    VkShaderModuleCreateInfo smi{VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO}; smi.codeSize=spv_bytes; smi.pCode=spv;
    if(vkCreateShaderModule(g->dev,&smi,NULL,&g->mod)!=VK_SUCCESS){ err="shader"; gpu_min_destruir(g); return NULL; }
    VkDescriptorSetLayoutBinding lb[2];
    for(int i=0;i<2;i++){ lb[i]={}; lb[i].binding=(uint32_t)i; lb[i].descriptorType=VK_DESCRIPTOR_TYPE_STORAGE_BUFFER; lb[i].descriptorCount=1; lb[i].stageFlags=VK_SHADER_STAGE_COMPUTE_BIT; }
    VkDescriptorSetLayoutCreateInfo dli{VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO}; dli.bindingCount=2; dli.pBindings=lb;
    if(vkCreateDescriptorSetLayout(g->dev,&dli,NULL,&g->dsl)!=VK_SUCCESS){ err="layout"; gpu_min_destruir(g); return NULL; }
    VkPipelineLayoutCreateInfo pli{VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO}; pli.setLayoutCount=1; pli.pSetLayouts=&g->dsl;
    if(vkCreatePipelineLayout(g->dev,&pli,NULL,&g->pl)!=VK_SUCCESS){ err="pipeline layout"; gpu_min_destruir(g); return NULL; }
    VkComputePipelineCreateInfo cpi{VK_STRUCTURE_TYPE_COMPUTE_PIPELINE_CREATE_INFO};
    cpi.stage.sType=VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO; cpi.stage.stage=VK_SHADER_STAGE_COMPUTE_BIT; cpi.stage.module=g->mod; cpi.stage.pName="main"; cpi.layout=g->pl;
    if(vkCreateComputePipelines(g->dev,VK_NULL_HANDLE,1,&cpi,NULL,&g->pipe)!=VK_SUCCESS){ err="pipeline"; gpu_min_destruir(g); return NULL; }
    VkDescriptorPoolSize ps{VK_DESCRIPTOR_TYPE_STORAGE_BUFFER,2};
    VkDescriptorPoolCreateInfo dpi{VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO}; dpi.maxSets=1; dpi.poolSizeCount=1; dpi.pPoolSizes=&ps;
    if(vkCreateDescriptorPool(g->dev,&dpi,NULL,&g->dp)!=VK_SUCCESS){ err="descriptor pool"; gpu_min_destruir(g); return NULL; }
    VkDescriptorSetAllocateInfo dsa{VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO}; dsa.descriptorPool=g->dp; dsa.descriptorSetCount=1; dsa.pSetLayouts=&g->dsl;
    if(vkAllocateDescriptorSets(g->dev,&dsa,&g->ds)!=VK_SUCCESS){ err="descriptor set"; gpu_min_destruir(g); return NULL; }
    VkDescriptorBufferInfo dbi[2]={{g->buf[0],0,sizeof(MinGpuParams)},{g->buf[1],0,sizeof(MinGpuRes)}};
    VkWriteDescriptorSet wds[2];
    for(int i=0;i<2;i++){ wds[i]={VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET}; wds[i].dstSet=g->ds; wds[i].dstBinding=(uint32_t)i; wds[i].descriptorCount=1; wds[i].descriptorType=VK_DESCRIPTOR_TYPE_STORAGE_BUFFER; wds[i].pBufferInfo=&dbi[i]; }
    vkUpdateDescriptorSets(g->dev,2,wds,0,NULL);
    VkCommandPoolCreateInfo cpci{VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO}; cpci.queueFamilyIndex=g->fam; cpci.flags=VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT;
    if(vkCreateCommandPool(g->dev,&cpci,NULL,&g->cp)!=VK_SUCCESS){ err="command pool"; gpu_min_destruir(g); return NULL; }
    VkCommandBufferAllocateInfo cba{VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO}; cba.commandPool=g->cp; cba.level=VK_COMMAND_BUFFER_LEVEL_PRIMARY; cba.commandBufferCount=1;
    if(vkAllocateCommandBuffers(g->dev,&cba,&g->cb)!=VK_SUCCESS){ err="command buffer"; gpu_min_destruir(g); return NULL; }
    VkFenceCreateInfo fci{VK_STRUCTURE_TYPE_FENCE_CREATE_INFO};
    if(vkCreateFence(g->dev,&fci,NULL,&g->fence)!=VK_SUCCESS){ err="fence"; gpu_min_destruir(g); return NULL; }
    return g;
}

/* Una tanda de [cuantos] nonces desde [desde] (sin pasar de 0xffffffff). -1 si la GPU falla (se pierde el
 * dispositivo, tarda más de 10 s…): entonces hay que destruirla. */
static int64_t gpu_min_tanda(GpuMin *g,const uint8_t *cab,uint32_t desde,uint32_t cuantos,const uint32_t *obj,MinResultado *mr){
    if(cuantos==0) return 0;
    uint64_t resto=0x100000000ULL-desde; if(cuantos>resto) cuantos=(uint32_t)resto;
    MinGpuParams *p=(MinGpuParams*)g->map[0]; MinGpuRes *r=(MinGpuRes*)g->map[1];
    min_gpu_params(p,cab,desde,cuantos,obj,GM_ITER); min_gpu_res_iniciar(r);
    uint32_t grupos=(uint32_t)(((uint64_t)cuantos+GM_ITER*GM_LOCAL-1)/(GM_ITER*GM_LOCAL));
    vkResetCommandBuffer(g->cb,0);
    VkCommandBufferBeginInfo bi{VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO}; bi.flags=VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
    vkBeginCommandBuffer(g->cb,&bi);
    vkCmdBindPipeline(g->cb,VK_PIPELINE_BIND_POINT_COMPUTE,g->pipe);
    vkCmdBindDescriptorSets(g->cb,VK_PIPELINE_BIND_POINT_COMPUTE,g->pl,0,1,&g->ds,0,NULL);
    vkCmdDispatch(g->cb,grupos,1,1);
    /* Que la CPU vea lo que ha escrito el shader al terminar. */
    VkMemoryBarrier mb{VK_STRUCTURE_TYPE_MEMORY_BARRIER}; mb.srcAccessMask=VK_ACCESS_SHADER_WRITE_BIT; mb.dstAccessMask=VK_ACCESS_HOST_READ_BIT;
    vkCmdPipelineBarrier(g->cb,VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,VK_PIPELINE_STAGE_HOST_BIT,0,1,&mb,0,NULL,0,NULL);
    vkEndCommandBuffer(g->cb);
    VkSubmitInfo si{VK_STRUCTURE_TYPE_SUBMIT_INFO}; si.commandBufferCount=1; si.pCommandBuffers=&g->cb;
    vkResetFences(g->dev,1,&g->fence);
    if(vkQueueSubmit(g->q,1,&si,g->fence)!=VK_SUCCESS) return -1;
    if(vkWaitForFences(g->dev,1,&g->fence,VK_TRUE,10ULL*1000000000ULL)!=VK_SUCCESS) return -1;
    return (int64_t)min_gpu_leer(r,desde,cuantos,mr);
}
