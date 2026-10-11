# Validacion del ataque de reticula a nonces ECDSA cortos/sesgados (HNP + LLL)
# que implementa app/src/main/java/com/hunter/btc/Lattice.kt. Fabrica firmas con
# nonces de pocos bits para una clave conocida y comprueba que la reticula la
# recupera. Ejecutar:  python3 tools/ec-harness/lattice_hnp.py   (18/18 esperado)
from fractions import Fraction as Fr
import random
p=0xFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFEFFFFFC2F
n=0xFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFEBAAEDCE6AF48A03BBFD25E8CD0364141
Gx=0x79BE667EF9DCBBAC55A06295CE870B07029BFCDB2DCE28D959F2815B16F81798
Gy=0x483ADA7726A3C4655DA4FBFC0E1108A8FD17B448A68554199C47D08FFB10D4B8
def inv(a,m): return pow(a%m,m-2,m)
def add(P,Q):
    if P is None: return Q
    if Q is None: return P
    x1,y1=P; x2,y2=Q
    if x1==x2 and (y1+y2)%p==0: return None
    l=((3*x1*x1)*inv(2*y1,p) if P==Q else (y2-y1)*inv(x2-x1,p))%p
    x3=(l*l-x1-x2)%p; return (x3,(l*(x1-x3)-y1)%p)
def mul(k,P=(Gx,Gy)):
    R=None
    while k:
        if k&1: R=add(R,P)
        P=add(P,P); k>>=1
    return R
def sign(d,z,k):
    r=mul(k)[0]%n; return r,inv(k,n)*(z+r*d)%n

# LLL incremental (Cohen 2.6.3), base entera, GSO con Fraction
def lll(basis):
    B=[[int(x) for x in r] for r in basis]
    nn=len(B)
    def dot(u,v): return sum(a*b for a,b in zip(u,v))
    mu=[[Fr(0)]*nn for _ in range(nn)]
    Bn=[Fr(0)]*nn
    Bstar=[[Fr(0)]*len(B[0]) for _ in range(nn)]
    def gso_init():
        for i in range(nn):
            Bstar[i]=[Fr(x) for x in B[i]]
            for j in range(i):
                mu[i][j]=Fr(dot(B[i],[0]*0) if False else sum(Fr(B[i][t])*Bstar[j][t] for t in range(len(B[i]))))/Bn[j]
                Bstar[i]=[Bstar[i][t]-mu[i][j]*Bstar[j][t] for t in range(len(B[i]))]
            Bn[i]=sum(x*x for x in Bstar[i])
    gso_init()
    def red(k,l):
        if abs(mu[k][l])<=Fr(1,2): return
        q=int((mu[k][l]+Fr(1,2))//1) if mu[k][l]>0 else -int((-mu[k][l]+Fr(1,2))//1)
        B[k]=[B[k][t]-q*B[l][t] for t in range(len(B[k]))]
        for j in range(l): mu[k][j]-=q*mu[l][j]
        mu[k][l]-=q
    k=1; delta=Fr(99,100)
    while k<nn:
        red(k,k-1)
        if Bn[k] >= (delta-mu[k][k-1]**2)*Bn[k-1]:
            for l in range(k-2,-1,-1): red(k,l)
            k+=1
        else:
            m_=mu[k][k-1]; Bb=Bn[k]+m_*m_*Bn[k-1]
            mu[k][k-1]=m_*Bn[k-1]/Bb; Bn[k]=Bn[k-1]*Bn[k]/Bb; Bn[k-1]=Bb
            B[k],B[k-1]=B[k-1],B[k]
            for j in range(k-1): mu[k][j],mu[k-1][j]=mu[k-1][j],mu[k][j]
            for i in range(k+1,nn):
                t=mu[i][k]; mu[i][k]=mu[i][k-1]-m_*t; mu[i][k-1]=t+mu[k][k-1]*mu[i][k]
            k=max(k-1,1)
    return B

def attack(sigs,Bbits,PUB):
    m=len(sigs); Bnd=1<<Bbits
    t=[];u=[]
    for (r,s,z) in sigs:
        si=inv(s,n); t.append(r*si%n); u.append(z*si%n)
    dim=m+2
    M=[[0]*dim for _ in range(dim)]
    for i in range(m): M[i][i]=n*n
    for i in range(m): M[m][i]=t[i]*n
    M[m][m]=Bnd
    for i in range(m): M[m+1][i]=u[i]*n
    M[m+1][m+1]=Bnd*n
    for row in lll(M):
        col=row[m]
        if col==0: continue
        for cand in (col//Bnd,(-col)//Bnd):
            d=cand%n
            if d and mul(d)==PUB: return d
    return None

random.seed(1); ok=0; tot=0
for trial in range(6):
    d=random.randrange(1,n); PUB=mul(d)
    for (m,Bbits) in [(3,110),(4,128),(5,160)]:
        tot+=1; sigs=[]
        for _ in range(m):
            k=random.randrange(1,1<<Bbits); z=random.randrange(1,n)
            sigs.append((*sign(d,z,k),z)) if False else sigs.append((sign(d,z,k)[0],sign(d,z,k)[1],z))
        # fix: compute sign once
        sigs=[]
        for _ in range(m):
            k=random.randrange(1,1<<Bbits); z=random.randrange(1,n); r,s=sign(d,z,k); sigs.append((r,s,z))
        got=attack(sigs,Bbits,PUB); ok+=(got==d)
        print(f"  trial {trial} m={m} Bbits={Bbits}: {'OK' if got==d else '--'}")
print(f"RECOVERED {ok}/{tot}")
