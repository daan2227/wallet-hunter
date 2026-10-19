/* El scriptPubKey de un OP_RETURN: 6a + push de los datos. Contra la forma
 * estándar de Bitcoin Core, casos de borde incluidos (75 y 76 bytes, que cambian
 * de push directo a OP_PUSHDATA1; 80 bytes, el tope estándar; y 81, que se
 * rechaza).
 */
#include "addr_encode.h"
#include <stdio.h>
#include <string>

static int fallos = 0;

static void esperado(const char *nombre, const std::string &got, const std::string &want) {
    bool ok = got == want;
    printf("%s  %s\n", ok ? "OK  " : "MAL ", nombre);
    if (!ok) { printf("      esperado: %s\n      salida:   %s\n", want.c_str(), got.c_str()); fallos++; }
}

static std::string hex_de(const std::string &b) {
    std::string h; char t[3];
    for (unsigned char c : b) { snprintf(t, 3, "%02x", c); h += t; }
    return h;
}

int main() {
    /* 4 bytes: 6a 04 deadbeef */
    esperado("datos cortos", hex_de(op_return_spk("deadbeef")), "6a04deadbeef");
    /* 75 bytes: push directo (4b) */
    std::string d75(75 * 2, 'a');
    esperado("75 bytes: push directo", hex_de(op_return_spk(d75)).substr(0, 6), "6a4baa");
    /* 76 bytes: OP_PUSHDATA1 (4c 4c) */
    std::string d76(76 * 2, 'a');
    esperado("76 bytes: OP_PUSHDATA1", hex_de(op_return_spk(d76)).substr(0, 8), "6a4c4caa");
    /* 80 bytes: el máximo estándar */
    std::string d80(80 * 2, 'a');
    esperado("80 bytes: aceptado", op_return_spk(d80).empty() ? "rechazado" : "aceptado", "aceptado");
    /* 81 bytes: se rechaza */
    std::string d81(81 * 2, 'a');
    esperado("81 bytes: rechazado", op_return_spk(d81).empty() ? "rechazado" : "aceptado", "rechazado");
    /* hex mal formado o impar */
    esperado("hex impar: rechazado", op_return_spk("abc").empty() ? "rechazado" : "aceptado", "rechazado");
    esperado("no hex: rechazado", op_return_spk("zz").empty() ? "rechazado" : "aceptado", "rechazado");
    esperado("vacio: rechazado", op_return_spk("").empty() ? "rechazado" : "aceptado", "rechazado");

    printf(fallos ? "\nHAY FALLOS: %d\n" : "\nTodas las pruebas correctas.\n", fallos);
    return fallos ? 1 : 0;
}
