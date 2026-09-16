import java.security.MessageDigest;
public class R {
  static final int[] RL={0,1,2,3,4,5,6,7,8,9,10,11,12,13,14,15,
    7,4,13,1,10,6,15,3,12,0,9,5,2,14,11,8, 3,10,14,4,9,15,8,1,2,7,0,6,13,11,5,12,
    1,9,11,10,0,8,12,4,13,3,7,15,14,5,6,2, 4,0,5,9,7,12,2,10,14,1,3,8,11,6,15,13};
  static final int[] RR={5,14,7,0,9,2,11,4,13,6,15,8,1,10,3,12,
    6,11,3,7,0,13,5,10,14,15,8,12,4,9,1,2, 15,5,1,3,7,14,6,9,11,8,12,2,10,0,4,13,
    8,6,4,1,3,11,15,0,5,12,2,13,9,7,10,14, 12,15,10,4,1,5,8,7,6,2,13,14,0,3,9,11};
  static final int[] SL={11,14,15,12,5,8,7,9,11,13,14,15,6,7,9,8,
    7,6,8,13,11,9,7,15,7,12,15,9,11,7,13,12, 11,13,6,7,14,9,13,15,14,8,13,6,5,12,7,5,
    11,12,14,15,14,15,9,8,9,14,5,6,8,6,5,12, 9,15,5,11,6,8,13,12,5,12,13,14,11,8,5,6};
  static final int[] SR={8,9,9,11,13,15,15,5,7,7,8,11,14,14,12,6,
    9,13,15,7,12,8,9,11,7,7,12,7,6,15,13,11, 9,7,15,11,8,6,6,14,12,13,5,14,13,13,7,5,
    15,5,8,11,14,14,6,14,6,9,12,9,12,5,15,8, 8,5,12,9,12,5,14,6,8,13,6,5,15,13,11,11};
  static final int[] KL={0x00000000,0x5A827999,0x6ED9EBA1,0x8F1BBCDC,0xA953FD4E};
  static final int[] KR={0x50A28BE6,0x5C4DD124,0x6D703EF3,0x7A6D76E9,0x00000000};
  static int f(int j,int x,int y,int z){
    if(j<16) return x^y^z;
    if(j<32) return (x&y)|(~x&z);
    if(j<48) return (x|~y)^z;
    if(j<64) return (x&z)|(y&~z);
    return x^(y|~z);
  }
  static int rol(int x,int n){ return (x<<n)|(x>>>(32-n)); }
  static byte[] ripemd160(byte[] msg){
    int len=msg.length;
    int padLen=((len+8)/64+1)*64;
    byte[] m=new byte[padLen];
    System.arraycopy(msg,0,m,0,len);
    m[len]=(byte)0x80;
    long bits=(long)len*8;
    for(int i=0;i<8;i++) m[padLen-8+i]=(byte)(bits>>>(8*i));
    int h0=0x67452301,h1=0xEFCDAB89,h2=0x98BADCFE,h3=0x10325476,h4=0xC3D2E1F0;
    int[] X=new int[16];
    for(int b=0;b<padLen;b+=64){
      for(int i=0;i<16;i++)
        X[i]=(m[b+i*4]&0xff)|((m[b+i*4+1]&0xff)<<8)|((m[b+i*4+2]&0xff)<<16)|((m[b+i*4+3]&0xff)<<24);
      int al=h0,bl=h1,cl=h2,dl=h3,el=h4;
      int ar=h0,br=h1,cr=h2,dr=h3,er=h4;
      for(int j=0;j<80;j++){
        int t=rol(al+f(j,bl,cl,dl)+X[RL[j]]+KL[j/16],SL[j])+el;
        al=el;el=dl;dl=rol(cl,10);cl=bl;bl=t;
        t=rol(ar+f(79-j,br,cr,dr)+X[RR[j]]+KR[j/16],SR[j])+er;
        ar=er;er=dr;dr=rol(cr,10);cr=br;br=t;
      }
      int t=h1+cl+dr; h1=h2+dl+er; h2=h3+el+ar; h3=h4+al+br; h4=h0+bl+cr; h0=t;
    }
    int[] hs={h0,h1,h2,h3,h4};
    byte[] out=new byte[20];
    for(int i=0;i<5;i++) for(int k=0;k<4;k++) out[i*4+k]=(byte)(hs[i]>>>(8*k));
    return out;
  }
  static String hex(byte[] b){ StringBuilder s=new StringBuilder();
    for(byte x:b) s.append(String.format("%02x",x)); return s.toString(); }
  public static void main(String[] a) throws Exception {
    // Vectores estándar de RIPEMD-160
    String[][] v={
      {"", "9c1185a5c5e9fc54612808977ee8f548b2258d31"},
      {"abc","8eb208f7e05d987a9b044a8e98c6b087f15a0bfc"},
      {"message digest","5d0689ef49d2fae572b881b123a85ffa21595f36"}};
    boolean ok=true;
    for(String[] t:v){ String g=hex(ripemd160(t[0].getBytes("UTF-8")));
      boolean m=g.equals(t[1]); ok&=m;
      System.out.println((m?"OK  ":"MAL ")+"\""+t[0]+"\" -> "+g); }
    // hash160 de la pubkey de k=1
    byte[] pub=new byte[33]; String ph=
      "0279BE667EF9DCBBAC55A06295CE870B07029BFCDB2DCE28D959F2815B16F81798";
    for(int i=0;i<33;i++) pub[i]=(byte)Integer.parseInt(ph.substring(i*2,i*2+2),16);
    byte[] sha=MessageDigest.getInstance("SHA-256").digest(pub);
    String h160=hex(ripemd160(sha));
    boolean m=h160.equals("751e76e8199196d454941c45d1b3a323f1433bd6"); ok&=m;
    System.out.println((m?"OK  ":"MAL ")+"hash160(pub de k=1) -> "+h160);
    System.out.println(ok?"\nTODO CORRECTO":"\nHAY FALLOS");
  }
}
