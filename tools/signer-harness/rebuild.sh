#!/bin/bash
python3 - <<'PY'
import re
src=open('/home/user/wallet-hunter/app/src/main/cpp/hunter_jni.cpp').read()
end=src.index('extern "C" JNIEXPORT jstring JNICALL\nJava_com_hunter_btc_HunterEngine_buildAndSignTx')
b=src[:end]
for bad in ['#include <jni.h>','#include <android/log.h>','#include <sys/mman.h>','#include <sys/syscall.h>','#include <sched.h>']:
    b=b.replace(bad,'')
b=b.replace('#include <secp256k1.h>','#include "shim.h"\n#include <secp256k1.h>')
out=[];i=0
while True:
    m=re.search(r'Java_com_hunter_btc_\w+\s*\(', b[i:])
    if not m: out.append(b[i:]); break
    st=i+m.start(); z=max(b.rfind(';',0,st),b.rfind('}',0,st),b.rfind('\n\n',0,st))
    out.append(b[i:z+1])
    p=b.index('{', i+m.end()-1); d=0
    while True:
        if b[p]=='{': d+=1
        elif b[p]=='}': d-=1
        if d==0: break
        p+=1
    i=p+1
b=''.join(out).replace('extern "C" {','extern "C" { }',1)
open('sign_core.cpp','w').write(b+'\n#include <iostream>\nint main(){std::string r,l;while(std::getline(std::cin,l))r+=l;std::string o=build_and_sign_tx(r);std::cout<<o<<std::endl;return o.rfind("ERROR",0)==0?1:0;}\n')
PY
g++ -std=c++17 -O1 -o signer sign_core.cpp secp.o pre1.o pre2.o -Isecp256k1/include -I. -I/home/user/wallet-hunter/app/src/main/cpp -lcrypto -lpthread 2>&1 | grep "error:" | head -3
