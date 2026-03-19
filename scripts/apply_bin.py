#!/usr/bin/env python3
import sys

JNI = "/data/data/com.termux/files/home/wallet-hunter/app/src/main/cpp/hunter_jni.cpp"
src = open(JNI).read()

# 1. includes
if '#include <sys/mman.h>' not in src:
    src = src.replace('#include <sys/stat.h>', '#include <sys/stat.h>\n#include <sys/mman.h>\n#include <fcntl.h>')
    print("1. includes added")
else:
    print("1. includes ok")

# 2. load_bin_fn — insertar antes de la funcion load_fn existente
load_bin = r"""
static void* load_bin_fn(void*) {
    g_loading.store(true); g_csv_loaded.store(false);
    snprintf(g_load_status,sizeof(g_load_status),"Loading .bin...");
    int fd=open(g_csv_path,O_RDONLY);
    if(fd<0){snprintf(g_load_status,sizeof(g_load_status),"Error: cannot open .bin");g_loading.store(false);return nullptr;}
    struct stat st; fstat(fd,&st); size_t fsz=(size_t)st.st_size;
    if(fsz<8){snprintf(g_load_status,sizeof(g_load_status),"Error: .bin too small");close(fd);g_loading.store(false);return nullptr;}
    void* mapped=mmap(nullptr,fsz,PROT_READ,MAP_PRIVATE,fd,0); close(fd);
    if(mapped==MAP_FAILED){snprintf(g_load_status,sizeof(g_load_status),"Error: mmap failed");g_loading.store(false);return nullptr;}
    madvise(mapped,fsz,MADV_SEQUENTIAL);
    uint64_t n; memcpy(&n,mapped,8);
    if(fsz<8+n*HASH160_BYTES){snprintf(g_load_status,sizeof(g_load_status),"Error: .bin truncated");munmap(mapped,fsz);g_loading.store(false);return nullptr;}
    if(g_h160){free(g_h160);g_h160=nullptr;}g_total=0;
    if(g_xonly){free(g_xonly);g_xonly=nullptr;}g_total_tr=0;
    if(g_bloom.bits)bloom_free(&g_bloom);
    if(g_bloom_tr.bits)bloom_free(&g_bloom_tr);
    g_h160=(uint8_t*)malloc(n*HASH160_BYTES);
    if(!g_h160){snprintf(g_load_status,sizeof(g_load_status),"Error: OOM");munmap(mapped,fsz);g_loading.store(false);return nullptr;}
    memcpy(g_h160,(uint8_t*)mapped+8,n*HASH160_BYTES);
    munmap(mapped,fsz); g_total=n;
    snprintf(g_load_status,sizeof(g_load_status),"Building bloom...");
    g_bloom=bloom_create(g_total);
    for(uint64_t i=0;i<g_total;i++) bloom_set(&g_bloom,g_h160+i*HASH160_BYTES);
    add_log("Bloom: "+std::to_string(g_bloom.nbits/8/1024/1024)+"MB");
    snprintf(g_load_status,sizeof(g_load_status),"Ready: %.1fM | %.0fMB",(double)n/1e6,(double)(n*HASH160_BYTES)/1e6);
    g_csv_loaded.store(true); g_loading.store(false);
    add_log(std::string("BIN loaded: ")+g_load_status);
    return nullptr;
}

"""

# Encontrar inicio de load_fn
marker = "    FILE *f=fopen(g_csv_path,\"r\");"
pos = src.find(marker)
if pos == -1:
    print("ERROR: marker not found"); sys.exit(1)
fn_pos = src.rfind('static void*', 0, pos)
src = src[:fn_pos] + load_bin + src[fn_pos:]
print("2. load_bin_fn inserted")

# 3. Deteccion .bin al inicio de load_fn
old = (
    "    FILE *f=fopen(g_csv_path,\"r\");\n"
    "    if(!f){snprintf(g_load_status,sizeof(g_load_status),\"Error: could not open file\");g_loading.store(false);return nullptr;}"
)
new = (
    "    {size_t pl=strlen(g_csv_path);if(pl>4&&strcmp(g_csv_path+pl-4,\".bin\")==0)return load_bin_fn(nullptr);}\n"
    "    FILE *f=fopen(g_csv_path,\"r\");\n"
    "    if(!f){snprintf(g_load_status,sizeof(g_load_status),\"Error: could not open file\");g_loading.store(false);return nullptr;}"
)
if old in src:
    src = src.replace(old, new)
    print("3. .bin detection added")
else:
    print("ERROR: fopen pattern not found"); sys.exit(1)

open(JNI,'w').write(src)
print("DONE")
