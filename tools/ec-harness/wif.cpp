/* Las direcciones de una clave WIF, contra valores de k=1.
 *
 * P2PKH, P2WPKH y P2SH-P2WPKH vienen de los vectores publicados (BIP173 y el
 * ejemplo de k=1 de direcciones.cpp). La P2TR NO sale de ahí: el vector de
 * direcciones.cpp es de x(G) sin tweak, que prueba el codificador bech32m pero
 * no la tweak de BIP341. El valor de k=1 se calculó aparte, en Python, con la
 * fórmula de BIP341 (t = TaggedHash("TapTweak", x(G)), Q = G + t·G) y el
 * codificador bech32m de BIP350, sin mirar el código de aquí.
 *
 * Además se comprueba que una WIF de la otra red o con un carácter cambiado NO
 * da nada.
 */
#include "wif_direcciones.h"
#include <stdio.h>
#include <string>

static int fallos = 0;

static void debe_contener(const char *nombre, const std::string &salida, const char *linea) {
    bool ok = salida.find(linea) != std::string::npos;
    printf("%s  %s\n", ok ? "OK  " : "MAL ", nombre);
    if (!ok) { printf("      esperado: %s\n      salida:\n%s", linea, salida.c_str()); fallos++; }
}

static void debe_estar_vacio(const char *nombre, const std::string &salida) {
    bool ok = salida.empty();
    printf("%s  %s\n", ok ? "OK  " : "MAL ", nombre);
    if (!ok) { printf("      esperaba vacio, salio:\n%s", salida.c_str()); fallos++; }
}

int main() {
    /* k=1, comprimida, mainnet: todos los tipos. */
    std::string main_k1 = wif_direcciones("KwDiBf89QgGbjEhKnhXJuH7LrciVrZi3qYjgd9M7rFU73sVHnoWn", false);
    debe_contener("mainnet P2PKH de k=1", main_k1, "P2PKH 1BgGZ9tcN4rm9KBzDn7KprQz87SZ26SAMH\n");
    debe_contener("mainnet P2WPKH de k=1", main_k1, "P2WPKH bc1qw508d6qejxtdg4y5r3zarvary0c5xw7kv8f3t4\n");
    debe_contener("mainnet P2SH-P2WPKH de k=1", main_k1, "P2SH-P2WPKH 3JvL6Ymt8MVWiCNHC7oWU6nLeHNJKLZGLN\n");
    debe_contener("mainnet P2TR de k=1", main_k1,
                  "P2TR bc1pmfr3p9j00pfxjh0zmgp99y8zftmd3s5pmedqhyptwy6lm87hf5sspknck9\n");

    /* k=1, comprimida, testnet. */
    std::string test_k1 = wif_direcciones("cMahea7zqjxrtgAbB7LSGbcQUr1uX1ojuat9jZodMN87JcbXMTcA", true);
    debe_contener("testnet P2PKH de k=1", test_k1, "P2PKH mrCDrCybB6J1vRfbwM5hemdJz73FwDBC8r\n");
    debe_contener("testnet P2WPKH de k=1", test_k1, "P2WPKH tb1qw508d6qejxtdg4y5r3zarvary0c5xw7kxpjzsx\n");
    debe_contener("testnet P2SH-P2WPKH de k=1", test_k1, "P2SH-P2WPKH 2NAUYAHhujozruyzpsFRP63mbrdaU5wnEpN\n");
    debe_contener("testnet P2TR de k=1", test_k1,
                  "P2TR tb1pmfr3p9j00pfxjh0zmgp99y8zftmd3s5pmedqhyptwy6lm87hf5ssk79hv2\n");

    /* Sin comprimir (5...): sólo P2PKH, y es otra dirección que la comprimida. */
    std::string sin_comp = wif_direcciones("5HpHagT65TZzG1PH3CSu63k8DbpvD8s5ip4nEB3kEsreAnchuDf", false);
    debe_contener("sin comprimir: P2PKH de k=1", sin_comp, "P2PKH 1EHNa6Q4Jz2uvNExL497mE43ikXhwF6kZm\n");
    bool solo_p2pkh = sin_comp.find("P2WPKH") == std::string::npos && sin_comp.find("P2TR") == std::string::npos;
    printf("%s  sin comprimir solo da P2PKH\n", solo_p2pkh ? "OK  " : "MAL ");
    if (!solo_p2pkh) fallos++;

    /* Red equivocada o checksum roto: nada. */
    debe_estar_vacio("WIF de mainnet en modo testnet", wif_direcciones("KwDiBf89QgGbjEhKnhXJuH7LrciVrZi3qYjgd9M7rFU73sVHnoWn", true));
    debe_estar_vacio("WIF de testnet en modo mainnet", wif_direcciones("cMahea7zqjxrtgAbB7LSGbcQUr1uX1ojuat9jZodMN87JcbXMTcA", false));
    debe_estar_vacio("WIF con un caracter cambiado", wif_direcciones("KwDiBf89QgGbjEhKnhXJuH7LrciVrZi3qYjgd9M7rFU73sVHnoWo", false));
    debe_estar_vacio("cadena que no es WIF", wif_direcciones("1111111111111111111111111111111111111111111111111111", false));

    /* Importar lo que se pega: WIF comprimida, sin comprimir, o hex de 64. */
    const std::string K1_HEX = "0000000000000000000000000000000000000000000000000000000000000001";
    debe_contener("importar WIF comprimida: se queda igual, direccion y flag 1",
        wif_importar("KwDiBf89QgGbjEhKnhXJuH7LrciVrZi3qYjgd9M7rFU73sVHnoWn", false, true),
        "KwDiBf89QgGbjEhKnhXJuH7LrciVrZi3qYjgd9M7rFU73sVHnoWn|1BgGZ9tcN4rm9KBzDn7KprQz87SZ26SAMH|1");
    debe_contener("importar WIF sin comprimir: direccion sin comprimir, flag 0",
        wif_importar("5HpHagT65TZzG1PH3CSu63k8DbpvD8s5ip4nEB3kEsreAnchuDf", false, true),
        "5HpHagT65TZzG1PH3CSu63k8DbpvD8s5ip4nEB3kEsreAnchuDf|1EHNa6Q4Jz2uvNExL497mE43ikXhwF6kZm|0");
    debe_contener("importar hex, comprimida: WIF K y direccion comprimida",
        wif_importar(K1_HEX, false, true),
        "KwDiBf89QgGbjEhKnhXJuH7LrciVrZi3qYjgd9M7rFU73sVHnoWn|1BgGZ9tcN4rm9KBzDn7KprQz87SZ26SAMH|1");
    debe_contener("importar hex, sin comprimir: WIF 5 y direccion sin comprimir",
        wif_importar(K1_HEX, false, false),
        "5HpHagT65TZzG1PH3CSu63k8DbpvD8s5ip4nEB3kEsreAnchuDf|1EHNa6Q4Jz2uvNExL497mE43ikXhwF6kZm|0");
    debe_contener("importar WIF de testnet en modo testnet",
        wif_importar("cMahea7zqjxrtgAbB7LSGbcQUr1uX1ojuat9jZodMN87JcbXMTcA", true, true),
        "cMahea7zqjxrtgAbB7LSGbcQUr1uX1ojuat9jZodMN87JcbXMTcA|mrCDrCybB6J1vRfbwM5hemdJz73FwDBC8r|1");
    debe_contener("importar hex en testnet: WIF c y direccion de testnet",
        wif_importar(K1_HEX, true, true),
        "cMahea7zqjxrtgAbB7LSGbcQUr1uX1ojuat9jZodMN87JcbXMTcA|mrCDrCybB6J1vRfbwM5hemdJz73FwDBC8r|1");
    debe_estar_vacio("importar WIF de mainnet en modo testnet", wif_importar("KwDiBf89QgGbjEhKnhXJuH7LrciVrZi3qYjgd9M7rFU73sVHnoWn", true, true));
    debe_estar_vacio("importar hex no valido (con letra que no es hex)", wif_importar(std::string(63, '0') + "g", false, true));
    debe_estar_vacio("importar hex cero (clave no valida)", wif_importar(std::string(64, '0'), false, true));

    printf(fallos ? "\nHAY FALLOS: %d\n" : "\nTodas las pruebas correctas.\n", fallos);
    return fallos ? 1 : 0;
}
