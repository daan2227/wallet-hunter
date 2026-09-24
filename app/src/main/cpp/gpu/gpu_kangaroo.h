#pragma once
/* Kangaroo (Gaudry-Schost con negacion) en la GPU, con Vulkan.
 *
 * La GPU hace los saltos (kangaroo.comp). La CPU, entre tanda y tanda:
 *   - recoge los puntos distinguidos y los mete en la MISMA tabla que usan
 *     los hilos de CPU (c->tabla), asi que una colision entre un canguro de
 *     la GPU y uno de la CPU tambien resuelve;
 *   - suelta de nuevo los canguros aparcados (tras un distinguido, un ciclo
 *     repetido o demasiado tiempo sin distinguido), todos de una vez y en
 *     progresion (gk_soltar_tipo), que cuesta una suma por canguro.
 *
 * Solo con el mapa de negacion (c->negacion), que es lo que usa el motor.
 * Requiere kangaroo.h incluido antes. */
#include <vulkan/vulkan.h>
#include <string>
#include <vector>
#include <chrono>
#include <thread>

struct GpuKg {
    VkInstance inst=VK_NULL_HANDLE; VkPhysicalDevice pd=VK_NULL_HANDLE; VkDevice dev=VK_NULL_HANDLE;
    VkQueue q=VK_NULL_HANDLE; uint32_t fam=0;
    VkBuffer buf[4]={}; VkDeviceMemory mem[4]={}; void *map[4]={}; VkDeviceSize tam[4]={};
    VkShaderModule mod=VK_NULL_HANDLE; VkDescriptorSetLayout dsl=VK_NULL_HANDLE; VkPipelineLayout pl=VK_NULL_HANDLE;
    VkPipeline pipe=VK_NULL_HANDLE; VkDescriptorPool dp=VK_NULL_HANDLE; VkDescriptorSet ds=VK_NULL_HANDLE;
    VkCommandPool cp=VK_NULL_HANDLE; VkFence fence=VK_NULL_HANDLE;
    uint32_t n_inv=0, kpi=0, total=0, maxdp=0;
    std::string nombre;
    uint64_t rng=0x9E3779B97F4A7C15ULL;
    long long saltos=0;          /* los de esta GPU, para enseñarlos aparte */
};

static const uint32_t GK_ST=72, GK_REC=13;

static void gpu_kg_destruir(GpuKg *g){
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
        for(int i=0;i<4;i++){ if(g->map[i]) vkUnmapMemory(g->dev,g->mem[i]);
            if(g->buf[i]) vkDestroyBuffer(g->dev,g->buf[i],NULL);
            if(g->mem[i]) vkFreeMemory(g->dev,g->mem[i],NULL); }
        vkDestroyDevice(g->dev,NULL);
    }
    if(g->inst) vkDestroyInstance(g->inst,NULL);
    delete g;
}

/* Un valor al azar en [0, lim). */
static void gk_azar(GpuKg *g,sc_t out,const sc_t lim){
    int lb=sc_bits(lim);
    for(int it=0;it<128;it++){
        for(int j=0;j<4;j++){ g->rng^=g->rng<<13; g->rng^=g->rng>>7; g->rng^=g->rng<<17; out[j]=g->rng; }
        int top=(lb-1)/64, sh=(lb-1)%64;
        for(int j=3;j>top;j--) out[j]=0;
        if(sh<63) out[top]&=((1ULL<<(sh+1))-1);
        if(sc_cmp(out,lim)<0) return;
    }
    sc_zero(out); out[0]=1;
}

static void gk_put(uint32_t *d,const uint64_t *v){ for(int i=0;i<4;i++){ d[2*i]=(uint32_t)v[i]; d[2*i+1]=(uint32_t)(v[i]>>32); } }
static void gk_get(uint64_t *v,const uint32_t *d){ for(int i=0;i<4;i++) v[i]=(uint64_t)d[2*i]|((uint64_t)d[2*i+1]<<32); }

/* Suelta un canguro: lo mismo que soltar() de kg_run con negacion. */
static void gk_soltar(GpuKg *g,KangarooCtx *c,uint32_t kid,int manso){
    uint32_t *st=(uint32_t*)g->map[0]+(size_t)kid*GK_ST;
    sc_t d;
    if(manso) gk_azar(g,d,c->medio);
    else{
        sc_t octavo,dieci,u; sc_shr(octavo,c->medio,2);
        if(sc_bits(octavo)==0) sc_set_u64(octavo,2);
        sc_shr(dieci,octavo,1);
        gk_azar(g,u,octavo); sc_sub_n(d,u,dieci);
    }
    int cero=1; for(int j=0;j<4;j++) if(d[j]) cero=0;
    if(cero) sc_set_u64(d,1);
    fe_t x,y;
    JP dG; kg_scalar_mul(&dG,d,FIELD_GX,FIELD_GY);
    if(manso) kg_normalize(&dG,x,y);
    else{
        fe_t dx,dy; kg_normalize(&dG,dx,dy);
        JP w2; jp_add_affine(&w2,&c->objetivo,dx,dy);
        int inf=1; for(int j=0;j<4;j++) if(w2.z[j]) inf=0;
        if(inf){ memcpy(x,c->obj_x,32); memcpy(y,c->obj_y,32); sc_zero(d); }
        else kg_normalize(&w2,x,y);
    }
    uint32_t fl=manso?2u:0u;
    if(y[0]&1){ fe_t z; memset(z,0,32); fe_sub(y,z,y); fl|=1u; }
    memset(st,0,GK_ST*4);
    gk_put(st,x); gk_put(st+8,y); gk_put(st+16,d);
    st[24]=fl;
}

/* a / m, con m pequeno. */
static void gk_div(sc_t r,const sc_t a,uint32_t m){
    __uint128_t resto=0;
    for(int i=3;i>=0;i--){ __uint128_t t=(resto<<64)|a[i]; r[i]=(uint64_t)(t/m); resto=t%m; }
}

/* El punto de salida de la distancia d, en Jacobiano: d*G (manso) u
 * objetivo + d*G (salvaje). Devuelve 0 si sale el infinito. */
static int gk_punto(KangarooCtx *c,const sc_t d,int manso,JP *R){
    int cero=1; for(int j=0;j<4;j++) if(d[j]) cero=0;
    if(cero){ if(manso) return 0; *R=c->objetivo; return 1; }
    JP dG; kg_scalar_mul(&dG,d,FIELD_GX,FIELD_GY);
    if(!manso){ fe_t dx,dy; kg_normalize(&dG,dx,dy); jp_add_affine(R,&c->objetivo,dx,dy); }
    else *R=dG;
    int inf=1; for(int j=0;j<4;j++) if(R->z[j]) inf=0;
    return !inf;
}

/* Suelta n canguros del mismo tipo de una vez. En vez de una multiplicacion
 * escalar por canguro (unas 256 dobladas y sumas, mas una inversion), las
 * salidas van en progresion: d_i = d_0 + i*D, con D = ancho/n y d_0 al azar
 * en [inicio, inicio+D). Cada punto es el anterior mas D*G (una suma mixta), y
 * una sola inversion (el truco de Montgomery) los pasa todos a afin. De paso
 * quedan repartidos por igual por su zona, que para Gaudry-Schost es tan
 * bueno como al azar. */
static void gk_soltar_tipo(GpuKg *g,KangarooCtx *c,const uint32_t *kids,uint32_t n,int manso){
    sc_t ini,ancho;
    if(manso){ sc_zero(ini); sc_copy(ancho,c->medio); }
    else{
        sc_t dieci; sc_shr(ancho,c->medio,2);
        if(sc_bits(ancho)==0) sc_set_u64(ancho,2);
        sc_shr(dieci,ancho,1); sc_neg_n(ini,dieci);
    }
    sc_t D; gk_div(D,ancho,n?n:1);
    if(n<16 || sc_bits(D)==0){ for(uint32_t i=0;i<n;i++) gk_soltar(g,c,kids[i],manso); return; }
    sc_t off,d; gk_azar(g,off,D); sc_add_n(d,ini,off);
    JP DGj; kg_scalar_mul(&DGj,D,FIELD_GX,FIELD_GY);
    fe_t Dx,Dy; kg_normalize(&DGj,Dx,Dy);

    std::vector<JP> P(n); std::vector<uint64_t> dv((size_t)n*4), pv((size_t)n*4); std::vector<uint8_t> ok(n);
    uint64_t *dist=dv.data(), *pfx=pv.data();
    int valido=gk_punto(c,d,manso,&P[0]);
    for(uint32_t i=0;i<n;i++){
        if(i){
            sc_add_n(d,d,D);
            if(valido){ jp_add_affine(&P[i],&P[i-1],Dx,Dy);
                int inf=1; for(int j=0;j<4;j++) if(P[i].z[j]) inf=0;
                valido=!inf; }
            /* Infinito: o el punto lo es de verdad (el objetivo menos su
               propia clave), o la suma mixta cayo en el doblado. Se rehace
               entera; si sigue siendo el infinito, ese canguro va aparte. */
            if(!valido) valido=gk_punto(c,d,manso,&P[i]);
        }
        sc_copy(dist+4*(size_t)i,d); ok[i]=(uint8_t)valido;
    }
    /* Montgomery: pfx[i] = z_0 * ... * z_i de los validos. */
    fe_t acc; memset(acc,0,32); acc[0]=1;
    for(uint32_t i=0;i<n;i++){ if(ok[i]) fe_mul(acc,acc,P[i].z); memcpy(pfx+4*(size_t)i,acc,32); }
    fe_t inv; fe_inv(inv,acc);
    for(uint32_t k=n;k-- >0;){
        uint32_t *st=(uint32_t*)g->map[0]+(size_t)kids[k]*GK_ST;
        if(!ok[k]){ gk_soltar(g,c,kids[k],manso); continue; }
        fe_t zi,z2,z3,x,y;
        if(k){ fe_mul(zi,inv,pfx+4*(size_t)(k-1)); fe_mul(inv,inv,P[k].z); }
        else memcpy(zi,inv,32);
        fe_sqr(z2,zi); fe_mul(z3,z2,zi);
        fe_mul(x,P[k].x,z2); fe_mul(y,P[k].y,z3);
        uint32_t fl=manso?2u:0u;
        if(y[0]&1){ fe_t z; memset(z,0,32); fe_sub(y,z,y); fl|=1u; }
        memset(st,0,GK_ST*4);
        gk_put(st,x); gk_put(st+8,y); gk_put(st+16,dist+4*(size_t)k);
        st[24]=fl;
    }
}

/* Suelta una lista de canguros, separando mansos y salvajes. */
static void gk_soltar_lote(GpuKg *g,KangarooCtx *c,const uint32_t *kids,const uint8_t *mansos,uint32_t n){
    std::vector<uint32_t> m,s; m.reserve(n); s.reserve(n);
    for(uint32_t i=0;i<n;i++) (mansos[i]?m:s).push_back(kids[i]);
    if(!m.empty()) gk_soltar_tipo(g,c,m.data(),(uint32_t)m.size(),1);
    if(!s.empty()) gk_soltar_tipo(g,c,s.data(),(uint32_t)s.size(),0);
}

static bool gk_buffer(GpuKg *g,int i,VkDeviceSize bytes,std::string &err){
    g->tam[i]=bytes;
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

/* Prepara la GPU para el contexto c (ya pasado por kg_setup). */
static GpuKg *gpu_kg_crear(KangarooCtx *c,const uint32_t *spv,size_t spv_bytes,
                           uint32_t n_inv,uint32_t kpi,uint64_t semilla,std::string &err){
    if(!c->negacion){ err="the GPU only runs the negation-map engine"; return NULL; }
    GpuKg *g=new GpuKg(); g->rng=semilla?semilla:0x9E3779B97F4A7C15ULL;
    g->n_inv=n_inv; g->kpi=kpi; g->total=n_inv*kpi; g->maxdp=g->total;
    VkApplicationInfo app{VK_STRUCTURE_TYPE_APPLICATION_INFO}; app.pApplicationName="WalletHunter"; app.apiVersion=VK_API_VERSION_1_0;
    VkInstanceCreateInfo ici{VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO}; ici.pApplicationInfo=&app;
    if(vkCreateInstance(&ici,NULL,&g->inst)!=VK_SUCCESS){ err="no Vulkan"; gpu_kg_destruir(g); return NULL; }
    uint32_t nd=0; vkEnumeratePhysicalDevices(g->inst,&nd,NULL);
    if(!nd){ err="no Vulkan device"; gpu_kg_destruir(g); return NULL; }
    std::vector<VkPhysicalDevice> pds(nd); vkEnumeratePhysicalDevices(g->inst,&nd,pds.data()); g->pd=pds[0];
    VkPhysicalDeviceProperties props; vkGetPhysicalDeviceProperties(g->pd,&props); g->nombre=props.deviceName;
    uint32_t nq=0; vkGetPhysicalDeviceQueueFamilyProperties(g->pd,&nq,NULL);
    std::vector<VkQueueFamilyProperties> qf(nq); vkGetPhysicalDeviceQueueFamilyProperties(g->pd,&nq,qf.data());
    int fam=-1; for(uint32_t i=0;i<nq;i++) if(qf[i].queueFlags&VK_QUEUE_COMPUTE_BIT){ fam=(int)i; break; }
    if(fam<0){ err="no compute queue"; gpu_kg_destruir(g); return NULL; }
    g->fam=(uint32_t)fam;
    float prio=1.0f;
    VkDeviceQueueCreateInfo qci{VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO}; qci.queueFamilyIndex=g->fam; qci.queueCount=1; qci.pQueuePriorities=&prio;
    VkDeviceCreateInfo dci{VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO}; dci.queueCreateInfoCount=1; dci.pQueueCreateInfos=&qci;
    if(vkCreateDevice(g->pd,&dci,NULL,&g->dev)!=VK_SUCCESS){ err="device"; gpu_kg_destruir(g); return NULL; }
    vkGetDeviceQueue(g->dev,g->fam,0,&g->q);

    if(!gk_buffer(g,0,(VkDeviceSize)g->total*GK_ST*4,err) ||
       !gk_buffer(g,1,(VkDeviceSize)KG_MAX_JUMPS*24*4,err) ||
       !gk_buffer(g,2,(VkDeviceSize)(4+g->maxdp*GK_REC+g->total)*4,err) ||
       !gk_buffer(g,3,(VkDeviceSize)g->total*8*4,err)){ gpu_kg_destruir(g); return NULL; }

    /* La tabla de saltos: los 64 normales y los 64 de escape de kg_setup. */
    uint32_t *T=(uint32_t*)g->map[1];
    for(int e=0;e<KG_MAX_JUMPS;e++){ gk_put(T+e*24,c->jx[e]); gk_put(T+e*24+8,c->jy[e]); gk_put(T+e*24+16,c->jlen[e]); }
    if(c->njumps!=64 || c->nesc!=64){ err="unexpected jump table"; gpu_kg_destruir(g); return NULL; }
    /* Soltar cientos de miles de canguros lleva su rato aun en progresion. Si mientras tanto se pide parar (o la CPU ya ha
       encontrado la clave, que en un puzzle pequeno pasa antes de acabar),
       se deja: parar espera a este hilo, y bloquearia la pantalla. */
    {
        const uint32_t TROZO=4096;
        std::vector<uint32_t> kids(TROZO); std::vector<uint8_t> mansos(TROZO);
        for(uint32_t k0=0;k0<g->total;k0+=TROZO){
            if(c->parar.load() || c->encontrado.load()){
                err="stopped before starting"; gpu_kg_destruir(g); return NULL;
            }
            uint32_t n=g->total-k0<TROZO?g->total-k0:TROZO;
            for(uint32_t i=0;i<n;i++){ kids[i]=k0+i; mansos[i]=(uint8_t)((k0+i)&1); }
            gk_soltar_lote(g,c,kids.data(),mansos.data(),n);
        }
    }
    memset(g->map[2],0,(size_t)g->tam[2]);

    VkShaderModuleCreateInfo smi{VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO}; smi.codeSize=spv_bytes; smi.pCode=spv;
    if(vkCreateShaderModule(g->dev,&smi,NULL,&g->mod)!=VK_SUCCESS){ err="shader"; gpu_kg_destruir(g); return NULL; }
    VkDescriptorSetLayoutBinding lb[4];
    for(int i=0;i<4;i++){ lb[i]={}; lb[i].binding=(uint32_t)i; lb[i].descriptorType=VK_DESCRIPTOR_TYPE_STORAGE_BUFFER; lb[i].descriptorCount=1; lb[i].stageFlags=VK_SHADER_STAGE_COMPUTE_BIT; }
    VkDescriptorSetLayoutCreateInfo dli{VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO}; dli.bindingCount=4; dli.pBindings=lb;
    vkCreateDescriptorSetLayout(g->dev,&dli,NULL,&g->dsl);
    VkPushConstantRange pcr{VK_SHADER_STAGE_COMPUTE_BIT,0,7*4};
    VkPipelineLayoutCreateInfo pli{VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO}; pli.setLayoutCount=1; pli.pSetLayouts=&g->dsl; pli.pushConstantRangeCount=1; pli.pPushConstantRanges=&pcr;
    vkCreatePipelineLayout(g->dev,&pli,NULL,&g->pl);
    VkComputePipelineCreateInfo cpi{VK_STRUCTURE_TYPE_COMPUTE_PIPELINE_CREATE_INFO};
    cpi.stage.sType=VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO; cpi.stage.stage=VK_SHADER_STAGE_COMPUTE_BIT; cpi.stage.module=g->mod; cpi.stage.pName="main"; cpi.layout=g->pl;
    if(vkCreateComputePipelines(g->dev,VK_NULL_HANDLE,1,&cpi,NULL,&g->pipe)!=VK_SUCCESS){ err="pipeline"; gpu_kg_destruir(g); return NULL; }
    VkDescriptorPoolSize ps{VK_DESCRIPTOR_TYPE_STORAGE_BUFFER,4};
    VkDescriptorPoolCreateInfo dpi{VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO}; dpi.maxSets=1; dpi.poolSizeCount=1; dpi.pPoolSizes=&ps;
    vkCreateDescriptorPool(g->dev,&dpi,NULL,&g->dp);
    VkDescriptorSetAllocateInfo dsa{VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO}; dsa.descriptorPool=g->dp; dsa.descriptorSetCount=1; dsa.pSetLayouts=&g->dsl;
    vkAllocateDescriptorSets(g->dev,&dsa,&g->ds);
    VkDescriptorBufferInfo dbi[4]; VkWriteDescriptorSet wds[4];
    for(int i=0;i<4;i++){ dbi[i]={g->buf[i],0,g->tam[i]}; wds[i]={VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET}; wds[i].dstSet=g->ds; wds[i].dstBinding=(uint32_t)i; wds[i].descriptorCount=1; wds[i].descriptorType=VK_DESCRIPTOR_TYPE_STORAGE_BUFFER; wds[i].pBufferInfo=&dbi[i]; }
    vkUpdateDescriptorSets(g->dev,4,wds,0,NULL);
    VkCommandPoolCreateInfo cpci{VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO}; cpci.queueFamilyIndex=g->fam; cpci.flags=VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT;
    vkCreateCommandPool(g->dev,&cpci,NULL,&g->cp);
    VkFenceCreateInfo fci{VK_STRUCTURE_TYPE_FENCE_CREATE_INFO}; vkCreateFence(g->dev,&fci,NULL,&g->fence);
    return g;
}

/* Una tanda: pasos saltos por canguro. Devuelve los segundos que ha tardado. */
static double gpu_kg_tanda(GpuKg *g,KangarooCtx *c,uint32_t pasos){
    VkCommandBufferAllocateInfo cba{VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO}; cba.commandPool=g->cp; cba.level=VK_COMMAND_BUFFER_LEVEL_PRIMARY; cba.commandBufferCount=1;
    VkCommandBuffer cb; vkAllocateCommandBuffers(g->dev,&cba,&cb);
    VkCommandBufferBeginInfo bi{VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO}; bi.flags=VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
    vkBeginCommandBuffer(cb,&bi);
    vkCmdBindPipeline(cb,VK_PIPELINE_BIND_POINT_COMPUTE,g->pipe);
    vkCmdBindDescriptorSets(cb,VK_PIPELINE_BIND_POINT_COMPUTE,g->pl,0,1,&g->ds,0,NULL);
    uint64_t tope=(c->dbits<27)?(20ULL<<c->dbits):0xFFFFFFFFULL; if(tope>0xFFFFFFFFULL) tope=0xFFFFFFFFULL;
    uint32_t pcv[7]={g->kpi,pasos,(uint32_t)c->dmask,(uint32_t)(c->dmask>>32),(uint32_t)tope,g->maxdp,g->total};
    vkCmdPushConstants(cb,g->pl,VK_SHADER_STAGE_COMPUTE_BIT,0,sizeof(pcv),pcv);
    vkCmdDispatch(cb,(g->n_inv+63)/64,1,1);
    vkEndCommandBuffer(cb);
    VkSubmitInfo si{VK_STRUCTURE_TYPE_SUBMIT_INFO}; si.commandBufferCount=1; si.pCommandBuffers=&cb;
    vkResetFences(g->dev,1,&g->fence);
    auto t0=std::chrono::steady_clock::now();
    vkQueueSubmit(g->q,1,&si,g->fence);
    vkWaitForFences(g->dev,1,&g->fence,VK_TRUE,120ULL*1000000000ULL);
    double seg=std::chrono::duration<double>(std::chrono::steady_clock::now()-t0).count();
    vkFreeCommandBuffers(g->dev,g->cp,1,&cb);

    /* Recoger: distinguidos a la tabla, aparcados otra vez sueltos. */
    uint32_t *S=(uint32_t*)g->map[2];
    uint32_t ndp=S[0]<g->maxdp?S[0]:g->maxdp, nre=S[1]<g->total?S[1]:g->total;
    c->saltos.fetch_add((long long)S[2]);
    g->saltos+=(long long)S[2];
    for(uint32_t j=0;j<ndp && !c->encontrado.load();j++){
        const uint32_t *o=S+4+(size_t)j*GK_REC;
        uint64_t kx[2]={(uint64_t)o[0]|((uint64_t)o[1]<<32),(uint64_t)o[2]|((uint64_t)o[3]<<32)};
        sc_t d; gk_get(d,o+4);
        int manso=(int)o[12];
        sc_t otro; int om=0, mismo=0;
        if(dp_insert(&c->tabla,kx,d,manso,otro,&om,&mismo)) kg_resolver(c,d,manso,otro);
        else if(mismo) c->pegados.fetch_add(1);
    }
    const uint32_t *R=S+4+(size_t)g->maxdp*GK_REC;
    std::vector<uint32_t> kids; std::vector<uint8_t> mansos; kids.reserve(nre); mansos.reserve(nre);
    for(uint32_t j=0;j<nre;j++){
        uint32_t kid=R[j]; if(kid>=g->total) continue;
        const uint32_t *st=(const uint32_t*)g->map[0]+(size_t)kid*GK_ST;
        kids.push_back(kid); mansos.push_back((st[24]&2u)?1:0);
    }
    gk_soltar_lote(g,c,kids.data(),mansos.data(),(uint32_t)kids.size());
    S[0]=S[1]=S[2]=0;
    return seg;
}

/* Corre hasta que c->parar o c->encontrado. Ajusta la tanda a ~60 ms, para
 * no bloquear la GPU del movil (Android corta las que tardan segundos). */
static void gpu_kg_correr(GpuKg *g,KangarooCtx *c){
    uint32_t pasos=4;
    while(!c->parar.load() && !c->encontrado.load()){
        double seg=gpu_kg_tanda(g,c,pasos);
        if(seg<0.03 && pasos<4096) pasos*=2;
        else if(seg>0.12 && pasos>1) pasos/=2;
        int cpu=c->cpu_limite.load();
        if(cpu>0 && cpu<100){
            double dormir=seg*(100.0-cpu)/cpu;
            if(dormir>0.0005) std::this_thread::sleep_for(std::chrono::microseconds((long long)(dormir*1e6)));
        }
    }
}
