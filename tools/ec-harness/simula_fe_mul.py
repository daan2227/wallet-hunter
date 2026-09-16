#!/usr/bin/env python3
"""Reproduce paso a paso el fe_mul roto que había en jac_batch.h.

El asm original sólo compila en ARM64, así que no hay forma de ejecutarlo en un
portátil. Esto lo simula en Python, instrucción a instrucción, para poder ver el
fallo sin un móvil delante.
"""
M64 = (1 << 64) - 1
P = 2**256 - 2**32 - 977
C = 0x1000003D1


def fe_mul_roto(a, b):
    t = [0] * 8
    for i in range(4):
        c = 0
        for j in range(4):
            prod = a[i] * b[j]
            lo, hi = prod & M64, prod >> 64
            old = t[i + j]
            # (1) el C suma lo y el acarreo... y tira el acarreo de salida
            t[i + j] = (old + lo + c) & M64
            # (2) la expresión intermedia de c es código muerto: se pisa
            c = hi
            # (3) el asm vuelve a sumar lo, otra vez
            s = t[i + j] + lo
            t[i + j] = s & M64
            c = (c + (1 if s > M64 else 0)) & M64
        t[i + 4] = (t[i + 4] + c) & M64
    carry = 0
    for i in range(4):
        p = t[i + 4] * C + t[i] + carry
        t[i] = p & M64
        carry = p >> 64
    if carry:
        p2 = carry * C + t[0]
        t[0] = p2 & M64
        c2 = p2 >> 64
        for i in range(1, 4):
            if not c2:
                break
            t[i] = (t[i] + c2) & M64
            c2 = 1 if t[i] < c2 else 0
    v = sum(t[i] << (64 * i) for i in range(4))
    return v - P if v >= P else v


def limbs(x):
    return [(x >> (64 * i)) & M64 for i in range(4)]


if __name__ == "__main__":
    fallos = 0
    for a, b in [(2, 3), (7, 1), (0x1234567890ABCDEF, 0xFEDCBA0987654321),
                 (P - 1, P - 2)]:
        got, exp = fe_mul_roto(limbs(a), limbs(b)), (a * b) % P
        ok = got == exp
        fallos += not ok
        print(f"{'OK ' if ok else 'MAL'}  {a:#x} * {b:#x}")
        if not ok:
            print(f"      esperado {exp:#x}")
            print(f"      obtenido {got:#x}")
    print()
    print("El fe_mul de entonces fallaba los cuatro." if fallos == 4
          else f"{fallos} de 4 fallan.")
