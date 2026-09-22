/* Los puzzles ya resueltos: clave, publica y direccion tienen que cuadrar.
 *
 * Estas 83 entradas son objetivos con RESPUESTA CONOCIDA, y por eso sirven de
 * prueba de punta a punta en el movil: si el motor encuentra la clave, se puede
 * comparar con la que ya se sabia. Pero solo valen si los datos son correctos —
 * una direccion mal copiada da una busqueda que no puede terminar nunca y que
 * por fuera se ve igual que una que aun no ha terminado.
 *
 * La tabla de puzzles de este proyecto YA se equivoco una vez asi: doce
 * direcciones que ni siquiera eran direcciones de Bitcoin, y las validas en el
 * numero equivocado. Por eso esto no se revisa a ojo: de cada clave privada se
 * deriva la publica y de ahi la direccion, con el codigo del propio motor, y se
 * comparan las tres. Un digito cambiado salta aqui.
 *
 * Ademas se comprueba que cada clave cae en SU rango: el puzzle N va de
 * 2^(N-1) a 2^N-1, o sea que la clave tiene que tener exactamente N bits.
 */
#include <stdio.h>
#include <string.h>
#include <ctype.h>
#include <stdlib.h>
#include <pthread.h>
#include "../../app/src/main/cpp/kangaroo.h"
#include "../../app/src/main/cpp/addr_encode.h"
#include "../../app/src/main/cpp/sha256_ripemd160.h"

typedef struct { int num; const char *addr, *priv, *pub; } Resuelto;
static const Resuelto TABLA[] = {
    {  1, "1BgGZ9tcN4rm9KBzDn7KprQz87SZ26SAMH", "1", "0279be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798"},
    {  2, "1CUNEBjYrCn2y1SdiUMohaKUi4wpP326Lb", "3", "02f9308a019258c31049344f85f89d5229b531c845836f99b08601f113bce036f9"},
    {  3, "19ZewH8Kk1PDbSNdJ97FP4EiCjTRaZMZQA", "7", "025cbdf0646e5db4eaa398f365f2ea7a0e3d419b7e0330e39ce92bddedcac4f9bc"},
    {  4, "1EhqbyUMvvs7BfL8goY6qcPbD6YKfPqb7e", "8", "022f01e5e15cca351daff3843fb70f3c2f0a1bdd05e5af888a67784ef3e10a2a01"},
    {  5, "1E6NuFjCi27W5zoXg8TRdcSRq84zJeBW3k", "15", "02352bbf4a4cdd12564f93fa332ce333301d9ad40271f8107181340aef25be59d5"},
    {  6, "1PitScNLyp2HCygzadCh7FveTnfmpPbfp8", "31", "03f2dac991cc4ce4b9ea44887e5c7c0bce58c80074ab9d4dbaeb28531b7739f530"},
    {  7, "1McVt1vMtCC7yn5b9wgX1833yCcLXzueeC", "4c", "0296516a8f65774275278d0d7420a88df0ac44bd64c7bae07c3fe397c5b3300b23"},
    {  8, "1M92tSqNmQLYw33fuBvjmeadirh1ysMBxK", "e0", "0308bc89c2f919ed158885c35600844d49890905c79b357322609c45706ce6b514"},
    {  9, "1CQFwcjw1dwhtkVWBttNLDtqL7ivBonGPV", "1d3", "0243601d61c836387485e9514ab5c8924dd2cfd466af34ac95002727e1659d60f7"},
    { 10, "1LeBZP5QCwwgXRtmVUvTVrraqPUokyLHqe", "202", "03a7a4c30291ac1db24b4ab00c442aa832f7794b5a0959bec6e8d7fee802289dcd"},
    { 11, "1PgQVLmst3Z314JrQn5TNiys8Hc38TcXJu", "483", "038b05b0603abd75b0c57489e451f811e1afe54a8715045cdf4888333f3ebc6e8b"},
    { 12, "1DBaumZxUkM4qMQRt2LVWyFJq5kDtSZQot", "a7b", "038b00fcbfc1a203f44bf123fc7f4c91c10a85c8eae9187f9d22242b4600ce781c"},
    { 13, "1Pie8JkxBT6MGPz9Nvi3fsPkr2D8q3GBc1", "1460", "03aadaaab1db8d5d450b511789c37e7cfeb0eb8b3e61a57a34166c5edc9a4b869d"},
    { 14, "1ErZWg5cFCe4Vw5BzgfzB74VNLaXEiEkhk", "2930", "03b4f1de58b8b41afe9fd4e5ffbdafaeab86c5db4769c15d6e6011ae7351e54759"},
    { 15, "1QCbW9HWnwQWiQqVo5exhAnmfqKRrCRsvW", "68f3", "02fea58ffcf49566f6e9e9350cf5bca2861312f422966e8db16094beb14dc3df2c"},
    { 16, "1BDyrQ6WoF8VN3g9SAS1iKZcPzFfnDVieY", "c936", "029d8c5d35231d75eb87fd2c5f05f65281ed9573dc41853288c62ee94eb2590b7a"},
    { 17, "1HduPEXZRdG26SUT5Yk83mLkPyjnZuJ7Bm", "1764f", "033f688bae8321b8e02b7e6c0a55c2515fb25ab97d85fda842449f7bfa04e128c3"},
    { 18, "1GnNTmTVLZiqQfLbAdp9DVdicEnB5GoERE", "3080d", "020ce4a3291b19d2e1a7bf73ee87d30a6bdbc72b20771e7dfff40d0db755cd4af1"},
    { 19, "1NWmZRpHH4XSPwsW6dsS3nrNWfL1yrJj4w", "5749f", "0385663c8b2f90659e1ccab201694f4f8ec24b3749cfe5030c7c3646a709408e19"},
    { 20, "1HsMJxNiV7TLxmoF6uJNkydxPFDog4NQum", "d2c55", "033c4a45cbd643ff97d77f41ea37e843648d50fd894b864b0d52febc62f6454f7c"},
    { 21, "14oFNXucftsHiUMY8uctg6N487riuyXs4h", "1ba534", "031a746c78f72754e0be046186df8a20cdce5c79b2eda76013c647af08d306e49e"},
    { 22, "1CfZWK1QTQE3eS9qn61dQjV89KDjZzfNcv", "2de40f", "023ed96b524db5ff4fe007ce730366052b7c511dc566227d929070b9ce917abb43"},
    { 23, "1L2GM8eE7mJWLdo3HZS6su1832NX2txaac", "556e52", "03f82710361b8b81bdedb16994f30c80db522450a93e8e87eeb07f7903cf28d04b"},
    { 24, "1rSnXMr63jdCuegJFuidJqWxUPV7AtUf7", "dc2a04", "036ea839d22847ee1dce3bfc5b11f6cf785b0682db58c35b63d1342eb221c3490c"},
    { 25, "15JhYXn6Mx3oF4Y7PcTAv2wVVAuCFFQNiP", "1fa5ee5", "03057fbea3a2623382628dde556b2a0698e32428d3cd225f3bd034dca82dd7455a"},
    { 26, "1JVnST957hGztonaWK6FougdtjxzHzRMMg", "340326e", "024e4f50a2a3eccdb368988ae37cd4b611697b26b29696e42e06d71368b4f3840f"},
    { 27, "128z5d7nN7PkCuX5qoA4Ys6pmxUYnEy86k", "6ac3875", "031a864bae3922f351f1b57cfdd827c25b7e093cb9c88a72c1cd893d9f90f44ece"},
    { 28, "12jbtzBb54r97TCwW3G1gCFoumpckRAPdY", "d916ce8", "03e9e661838a96a65331637e2a3e948dc0756e5009e7cb5c36664d9b72dd18c0a7"},
    { 29, "19EEC52krRUK1RkUAEZmQdjTyHT7Gp1TYT", "17e2551e", "026caad634382d34691e3bef43ed4a124d8909a8a3362f91f1d20abaaf7e917b36"},
    { 30, "1LHtnpd8nU5VHEMkG2TMYYNUjjLc992bps", "3d94cd64", "030d282cf2ff536d2c42f105d0b8588821a915dc3f9a05bd98bb23af67a2e92a5b"},
    { 31, "1LhE6sCTuGae42Axu1L1ZB7L96yi9irEBE", "7d4fe747", "0387dc70db1806cd9a9a76637412ec11dd998be666584849b3185f7f9313c8fd28"},
    { 32, "1FRoHA9xewq7DjrZ1psWJVeTer8gHRqEvR", "b862a62e", "0209c58240e50e3ba3f833c82655e8725c037a2294e14cf5d73a5df8d56159de69"},
    { 33, "187swFMjz1G54ycVU56B7jZFHFTNVQFDiu", "1a96ca8d8", "03a355aa5e2e09dd44bb46a4722e9336e9e3ee4ee4e7b7a0cf5785b283bf2ab579"},
    { 34, "1PWABE7oUahG2AFFQhhvViQovnCr4rEv7Q", "34a65911d", "033cdd9d6d97cbfe7c26f902faf6a435780fe652e159ec953650ec7b1004082790"},
    { 35, "1PWCx5fovoEaoBowAvF5k91m2Xat9bMgwb", "4aed21170", "02f6a8148a62320e149cb15c544fe8a25ab483a0095d2280d03b8a00a7feada13d"},
    { 36, "1Be2UF9NLfyLFbtm3TCbmuocc9N1Kduci1", "9de820a7c", "02b3e772216695845fa9dda419fb5daca28154d8aa59ea302f05e916635e47b9f6"},
    { 37, "14iXhn8bGajVWegZHJ18vJLHhntcpL4dex", "1757756a93", "027d2c03c3ef0aec70f2c7e1e75454a5dfdd0e1adea670c1b3a4643c48ad0f1255"},
    { 38, "1HBtApAFA9B2YZw3G2YKSMCtb3dVnjuNe2", "22382facd0", "03c060e1e3771cbeccb38e119c2414702f3f5181a89652538851d2e3886bdd70c6"},
    { 39, "122AJhKLEfkFBaGAd84pLp1kfE7xK3GdT8", "4b5f8303e9", "022d77cd1467019a6bf28f7375d0949ce30e6b5815c2758b98a74c2700bc006543"},
    { 40, "1EeAxcprB2PpCnr34VfZdFrkUWuxyiNEFv", "e9ae4933d6", "03a2efa402fd5268400c77c20e574ba86409ededee7c4020e4b9f0edbee53de0d4"},
    { 41, "1L5sU9qvJeuwQUdt4y1eiLmquFxKjtHr3E", "153869acc5b", "03b357e68437da273dcf995a474a524439faad86fc9effc300183f714b0903468b"},
    { 42, "1E32GPWgDyeyQac4aJxm9HVoLrrEYPnM4N", "2a221c58d8f", "03eec88385be9da803a0d6579798d977a5d0c7f80917dab49cb73c9e3927142cb6"},
    { 43, "1PiFuqGpG8yGM5v6rNHWS3TjsG6awgEGA1", "6bd3b27c591", "02a631f9ba0f28511614904df80d7f97a4f43f02249c8909dac92276ccf0bcdaed"},
    { 44, "1CkR2uS7LmFwc3T2jV8C1BhWb5mQaoxedF", "e02b35a358f", "025e466e97ed0e7910d3d90ceb0332df48ddf67d456b9e7303b50a3d89de357336"},
    { 45, "1NtiLNGegHWE3Mp9g2JPkgx6wUg4TW7bbk", "122fca143c05", "026ecabd2d22fdb737be21975ce9a694e108eb94f3649c586cc7461c8abf5da71a"},
    { 46, "1F3JRMWudBaj48EhwcHDdpeuy2jwACNxjP", "2ec18388d544", "03fd5487722d2576cb6d7081426b66a3e2986c1ce8358d479063fb5f2bb6dd5849"},
    { 47, "1Pd8VvT49sHKsmqrQiP61RsVwmXCZ6ay7Z", "6cd610b53cba", "023a12bd3caf0b0f77bf4eea8e7a40dbe27932bf80b19ac72f5f5a64925a594196"},
    { 48, "1DFYhaB2J9q1LLZJWKTnscPWos9VBqDHzv", "ade6d7ce3b9b", "0291bee5cf4b14c291c650732faa166040e4c18a14731f9a930c1e87d3ec12debb"},
    { 49, "12CiUhYVTTH33w3SPUBqcpMoqnApAV4WCF", "174176b015f4d", "02591d682c3da4a2a698633bf5751738b67c343285ebdc3492645cb44658911484"},
    { 50, "1MEzite4ReNuWaL5Ds17ePKt2dCxWEofwk", "22bd43c2e9354", "03f46f41027bbf44fafd6b059091b900dad41e6845b2241dc3254c7cdd3c5a16c6"},
    { 51, "1NpnQyZ7x24ud82b7WiRNvPm6N8bqGQnaS", "75070a1a009d4", "028c6c67bef9e9eebe6a513272e50c230f0f91ed560c37bc9b033241ff6c3be78f"},
    { 52, "15z9c9sVpu6fwNiK7dMAFgMYSK4GqsGZim", "efae164cb9e3c", "0374c33bd548ef02667d61341892134fcf216640bc2201ae61928cd0874f6314a7"},
    { 53, "15K1YKJMiJ4fpesTVUcByoz334rHmknxmT", "180788e47e326c", "020faaf5f3afe58300a335874c80681cf66933e2a7aeb28387c0d28bb048bc6349"},
    { 54, "1KYUv7nSvXx4642TKeuC2SNdTk326uUpFy", "236fb6d5ad1f43", "034af4b81f8c450c2c870ce1df184aff1297e5fcd54944d98d81e1a545ffb22596"},
    { 55, "1LzhS3k3e9Ub8i2W1V8xQFdB8n2MYCHPCa", "6abe1f9b67e114", "0385a30d8413af4f8f9e6312400f2d194fe14f02e719b24c3f83bf1fd233a8f963"},
    { 56, "17aPYR1m6pVAacXg1PTDDU7XafvK1dxvhi", "9d18b63ac4ffdf", "033f2db2074e3217b3e5ee305301eeebb1160c4fa1e993ee280112f6348637999a"},
    { 57, "15c9mPGLku1HuW9LRtBf4jcHVpBUt8txKz", "1eb25c90795d61c", "02a521a07e98f78b03fc1e039bc3a51408cd73119b5eb116e583fe57dc8db07aea"},
    { 58, "1Dn8NF8qDyyfHMktmuoQLGyjWmZXgvosXf", "2c675b852189a21", "0311569442e870326ceec0de24eb5478c19e146ecd9d15e4666440f2f638875f42"},
    { 59, "1HAX2n9Uruu9YDt4cqRgYcvtGvZj1rbUyt", "7496cbb87cab44f", "0241267d2d7ee1a8e76f8d1546d0d30aefb2892d231cee0dde7776daf9f8021485"},
    { 60, "1Kn5h2qpgw9mWE5jKpk8PP4qvvJ1QVy8su", "fc07a1825367bbe", "0348e843dc5b1bd246e6309b4924b81543d02b16c8083df973a89ce2c7eb89a10d"},
    { 61, "1AVJKwzs9AskraJLGHAZPiaZcrpDr1U6AB", "13c96a3742f64906", "0249a43860d115143c35c09454863d6f82a95e47c1162fb9b2ebe0186eb26f453f"},
    { 62, "1Me6EfpwZK5kQziBwBfvLiHjaPGxCKLoJi", "363d541eb611abee", "03231a67e424caf7d01a00d5cd49b0464942255b8e48766f96602bdfa4ea14fea8"},
    { 63, "1NpYjtLira16LfGbGwZJ5JbDPh3ai9bjf4", "7cce5efdaccf6808", "0365ec2994b8cc0a20d40dd69edfe55ca32a54bcbbaa6b0ddcff36049301a54579"},
    { 64, "16jY7qLJnxb7CHZyqBP8qca9d51gAjyXQN", "f7051f27b09112d4", "03100611c54dfef604163b8358f7b7fac13ce478e02cb224ae16d45526b25d9d4d"},
    { 65, "18ZMbwUFLMHoZBbfpCjUJQTCMCbktshgpe", "1a838b13505b26867", "0230210c23b1a047bc9bdbb13448e67deddc108946de6de639bcc75d47c0216b1b"},
    { 66, "13zb1hQbWVsc2S7ZTZnP2G4undNNpdh5so", "2832ed74f2b5e35ee", "024ee2be2d4e9f92d2f5a4a03058617dc45befe22938feed5b7a6b7282dd74cbdd"},
    { 67, "1BY8GQbnueYofwSuFAT3USAhGjPrkxDdW9", "730fc235c1942c1ae", "0212209f5ec514a1580a2937bd833979d933199fc230e204c6cdc58872b7d46f75"},
    { 68, "1MVDYgVaSN6iKKEsbzRUAYFrYJadLYZvvZ", "bebb3940cd0fc1491", "031fe02f1d740637a7127cdfe8a77a8a0cfc6435f85e7ec3282cb6243c0a93ba1b"},
    { 69, "19vkiEajfhuZ8bs8Zu2jgmC6oqZbWqhxhG", "101d83275fb2bc7e0c", "024babadccc6cfd5f0e5e7fd2a50aa7d677ce0aa16fdce26a0d0882eed03e7ba53"},
    { 70, "19YZECXj3SxEZMoUeJ1yiPsw8xANe7M7QR", "349b84b6431a6c4ef1", "0290e6900a58d33393bc1097b5aed31f2e4e7cbd3e5466af958665bc0121248483"},
    { 75, "1J36UjUByGroXcCvmj13U6uwaVv9caEeAt", "4c5ce114686a1336e07", "03726b574f193e374686d8e12bc6e4142adeb06770e0a2856f5e4ad89f66044755"},
    { 80, "1BCf6rHUW6m3iH2ptsvnjgLruAiPQQepLe", "ea1a5c66dcc11b5ad180", "037e1238f7b1ce757df94faa9a2eb261bf0aeb9f84dbf81212104e78931c2a19dc"},
    { 85, "1Kh22PvXERd2xpTQk3ur6pPEqFeckCJfAr", "11720c4f018d51b8cebba8", "0329c4574a4fd8c810b7e42a4b398882b381bcd85e40c6883712912d167c83e73a"},
    { 90, "1L12FHH2FHjvTviyanuiFVfmzCy46RRATU", "2ce00bb2136a445c71e85bf", "035c38bd9ae4b10e8a250857006f3cfd98ab15a6196d9f4dfd25bc7ecc77d788d5"},
    { 95, "19eVSDuizydXxhohGh8Ki9WY9KsHdSwoQC", "527a792b183c7f64a0e8b1f4", "02967a5905d6f3b420959a02789f96ab4c3223a2c4d2762f817b7895c5bc88a045"},
    {100, "1KCgMv8fo2TPBpddVi9jqmMmcne9uSNJ5F", "af55fc59c335c8ec67ed24826", "03d2063d40402f030d4cc71331468827aa41a8a09bd6fd801ba77fb64f8e67e617"},
    {105, "1CMjscKB3QW7SDyQ4c3C3DEUHiHRhiZVib", "16f14fc2054cd87ee6396b33df3", "03bcf7ce887ffca5e62c9cabbdb7ffa71dc183c52c04ff4ee5ee82e0c55c39d77b"},
    {110, "12JzYkkN76xkwvcPT6AWKZtGX6w2LAgsJg", "35c0d7234df7deb0f20cf7062444", "0309976ba5570966bf889196b7fdf5a0f9a1e9ab340556ec29f8bb60599616167d"},
    {115, "1NLbHuJebVwUZ1XqDjsAyfTRUPwDQbemfv", "60f4d11574f5deee49961d9609ac6", "0248d313b0398d4923cdca73b8cfa6532b91b96703902fc8b32fd438a3b7cd7f55"},
    {120, "17s2b9ksz5y7abUm92cHwG8jEPCzK3dLnT", "b10f22572c497a836ea187f2e1fc23", "02ceb6cbbcdbdf5ef7150682150f4ce2c6f4807b349827dcdbdd1f2efa885a2630"},
    {125, "1PXAyUB8ZoH3WD8n5zoAthYjN15yN5CVq5", "1c533b6bb7f0804e09960225e44877ac", "0233709eb11e0d4439a729f21c2c443dedb727528229713f0065721ba8fa46f00e"},
    {130, "1Fo65aKq8s8iquMt6weF1rku1moWVEd5Ua", "33e7665705359f04f28b88cf897c603c9", "03633cbe3ec02b9401c5effa144c5b4d22f87940259634858fc7e59b1c09937852"},
    {135, "16RGFo6hjq9ym6Pj7N5H7L1NR1rVPJyw2v", "6d9392a16883f90903d5f78da57af07eb2", "02145d2611c823a396ef6712ce0f712f09b9b4f3135e3e0aa3230fb9b6d08d1e16"},
};
static const int N_TABLA = (int)(sizeof(TABLA)/sizeof(TABLA[0]));

static int hexval(char c){
    if(c>='0'&&c<='9') return c-'0';
    if(c>='a'&&c<='f') return c-'a'+10;
    if(c>='A'&&c<='F') return c-'A'+10;
    return -1;
}
static int a_escalar(const char *h,sc_t k){
    sc_zero(k);
    for(int i=0;h[i];i++){
        int v=hexval(h[i]); if(v<0) return 0;
        uint64_t acarreo=(uint64_t)v;
        for(int w=0;w<4;w++){
            unsigned __int128 t=((unsigned __int128)k[w]<<4)|acarreo;
            k[w]=(uint64_t)t; acarreo=(uint64_t)(t>>64);
        }
    }
    return 1;
}

int main(){
    setvbuf(stdout,NULL,_IONBF,0);
    printf("Puzzles resueltos: clave -> publica -> direccion\n\n");
    int mal_pub=0, mal_addr=0, mal_rango=0, mal_hex=0;
    for(int i=0;i<N_TABLA;i++){
        const Resuelto *r=&TABLA[i];
        sc_t k;
        if(!a_escalar(r->priv,k)){ printf("  MAL #%-3d la clave no es hex\n",r->num); mal_hex++; continue; }

        /* El puzzle N va de 2^(N-1) a 2^N-1: la clave tiene que tener N bits. */
        int b=sc_bits(k);
        if(b!=r->num){
            printf("  MAL #%-3d la clave tiene %d bits y el rango pide %d\n",r->num,b,r->num);
            mal_rango++;
        }

        JP P; kg_scalar_mul(&P,k,FIELD_GX,FIELD_GY);
        fe_t x,y; kg_normalize(&P,x,y);
        uint8_t p33[33];
        p33[0]=(y[0]&1)?0x03:0x02;
        for(int w=0;w<4;w++) for(int j=0;j<8;j++)
            p33[1+(3-w)*8+(7-j)]=(uint8_t)(x[w]>>(j*8));
        char phex[70];
        for(int j=0;j<33;j++) sprintf(phex+j*2,"%02x",p33[j]);
        phex[66]=0;
        if(strcmp(phex,r->pub)!=0){
            printf("  MAL #%-3d publica\n     da:  %s\n     esp: %s\n",r->num,phex,r->pub);
            mal_pub++;
        }

        uint8_t h160[20]; hash160_inline(p33,h160);
        char a[64]; h160_to_addr(h160,a,false);
        if(strcmp(a,r->addr)!=0){
            printf("  MAL #%-3d direccion\n     da:  %s\n     esp: %s\n",r->num,a,r->addr);
            mal_addr++;
        }
    }
    /* ---- Y lo que de verdad va a pasar en el movil: RESOLVERLOS ----
     *
     * Comprobar que los datos cuadran no dice que el motor los encuentre. Estos
     * son objetivos con respuesta conocida y rango pequeno, o sea que aqui se
     * pueden resolver de verdad en segundos — que es justo lo que se le va a
     * pedir al movil.
     *
     * dbits sale de la MISMA formula que usa kangarooStart (bits/4+4, minimo 6),
     * para que esto pruebe la configuracion real y no una de laboratorio. */
    printf("\n  Resolviendolos de verdad, con el dbits que usa la app:\n");
    /* ESTA ES LA PRUEBA QUE FALTABA.
     *
     * `constante` mide el coste con dbits=5, y con ese valor el mapa de
     * negacion salia 1,38 veces mejor. Con el dbits que usa la app —bits/4+4,
     * o sea 14 en el #40 y 28 en el #140— NO ENCUENTRA LA CLAVE. Se envio
     * encendido y el motor no podia dar con nada.
     *
     * La leccion no es "el mapa de negacion es malo": es que una prueba que
     * mide el algoritmo en una configuracion distinta de la que corre no esta
     * probando el motor, esta probando otro motor parecido. Por eso esto usa la
     * misma formula que kangarooStart y resuelve de verdad. */
    int mal_resuelto=0;
    const int cuales[]={20,25,30,35,40};
    for(size_t q=0;q<sizeof(cuales)/sizeof(cuales[0]);q++){
        const Resuelto *r=NULL;
        for(int i=0;i<N_TABLA;i++) if(TABLA[i].num==cuales[q]){ r=&TABLA[i]; break; }
        if(!r) continue;
        sc_t k; a_escalar(r->priv,k);

        uint8_t ini[32],fin[32],pub33[33];
        memset(ini,0,32); memset(fin,0,32);
        int n=r->num;
        ini[31-(n-1)/8] = (uint8_t)(1u<<((n-1)%8));
        for(int b=0;b<n;b++) fin[31-b/8] |= (uint8_t)(1u<<(b%8));
        JP P; kg_scalar_mul(&P,k,FIELD_GX,FIELD_GY);
        fe_t x,y; kg_normalize(&P,x,y);
        pub33[0]=(y[0]&1)?0x03:0x02;
        for(int w=0;w<4;w++) for(int j=0;j<8;j++)
            pub33[1+(3-w)*8+(7-j)]=(uint8_t)(x[w]>>(j*8));

        int dbits=n/4+4; if(dbits<6) dbits=6; if(dbits>28) dbits=28;
        KangarooCtx c;
        if(!kg_setup(&c,pub33,ini,fin,dbits,18)){
            printf("    MAL #%-3d kg_setup no acepta el rango\n",n); mal_resuelto++; continue;
        }
        kg_run(&c,256,0xC0FFEEULL + (uint64_t)n);
        int ok = c.encontrado.load() && sc_cmp(c.k,k)==0;
        printf("    %s #%-3d dbits %2d  %lld saltos%s\n", ok?"OK ":"MAL", n, dbits,
               (long long)c.saltos.load(),
               ok?"":"   <-- no la encontro o la encontro mal");
        if(!ok) mal_resuelto++;
        kg_free(&c);
    }

    int fallos=mal_hex+mal_pub+mal_addr+mal_rango+mal_resuelto;
    printf("  %s  %d entradas: %d claves no hex, %d publicas mal, %d direcciones mal,\n"
           "       %d fuera de su rango\n",
           fallos?"MAL":"OK ",N_TABLA,mal_hex,mal_pub,mal_addr,mal_rango);
    /* Si la tabla se quedara vacia, todo lo de arriba saldria a cero y esto
       diria que va bien sin haber comprobado nada. */
    if(N_TABLA<80){ printf("  MAL  la tabla tiene %d entradas, se esperaban 83\n",N_TABLA); fallos++; }

    printf("\n%s\n", fallos?"HAY FALLOS":"TODO CORRECTO");
    return fallos?1:0;
}
