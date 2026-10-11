exec(open('/tmp/lat/proto2.py').read().split('random.seed')[0])  # reuse secp+lll+attack defs
import random
random.seed(7)
SWEEP=[64,80,96,110,120,128,140,152,168,184,200,216,232,244]
for true in [96,110,128,160]:
    d=random.randrange(1,n); PUB=mul(d)
    sigs=[]
    for _ in range(4):
        k=random.randrange(1,1<<true); z=random.randrange(1,n); r,s=sign(d,z,k); sigs.append((r,s,z))
    hits=[bb for bb in SWEEP if attack(sigs,bb,PUB)==d]
    print(f"true={true}b  recover@B={hits}  (auto picks first = {hits[0] if hits else None})")
