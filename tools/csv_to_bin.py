#!/usr/bin/env python3
# Convierte el dataset de direcciones (CSV) al formato .bin que carga el
# motor: se carga en memoria de golpe (mmap) en vez de leer el CSV linea a
# linea, que con decenas de millones de direcciones tarda mucho mas.
# Pensado para correr en Termux, en el propio movil: las rutas de abajo son
# las de la carpeta de descargas.
import sys, struct, hashlib, time

CSV = "/data/data/com.termux/files/home/storage/downloads/utxos_clean.csv"
BIN = "/data/data/com.termux/files/home/storage/downloads/utxos_clean.bin"

B58 = b"123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"
B32 = b'qpzry9x8gf2tvdw0s3jn54khce6mua7l'

def b58decode(s):
    n = 0
    for c in s.encode():
        n = n * 58 + B58.index(c)
    return n.to_bytes(25, 'big')

def segwit_decode(addr):
    addr = addr.lower()
    pos = addr.rfind('1')
    data = [B32.index(ord(x)) for x in addr[pos+1:]]
    if data[0] != 0: return None
    acc, bits, result = 0, 0, []
    for v in data[1:-6]:
        acc = (acc << 5) | v
        bits += 5
        while bits >= 8:
            bits -= 8
            result.append((acc >> bits) & 0xff)
    return bytes(result) if len(result) == 20 else None

def addr_to_h160(addr):
    addr = addr.strip()
    if addr.lower().startswith('bc1q'):
        return segwit_decode(addr)
    elif addr and addr[0] in '13':
        try:
            d = b58decode(addr)
            chk = hashlib.sha256(hashlib.sha256(d[:21]).digest()).digest()[:4]
            return d[1:21] if chk == d[21:] else None
        except: return None
    return None

print(f"Reading: {CSV}")
t0 = time.time()
entries = []
errors = 0
rows = 0
with open(CSV, 'r', buffering=1<<20) as f:
    f.readline()
    for line in f:
        rows += 1
        addr = line.split(',')[0].strip()
        h = addr_to_h160(addr)
        if h: entries.append(h)
        else: errors += 1
        if rows % 500000 == 0:
            print(f"  {rows/1e6:.1f}M | ok={len(entries)/1e6:.1f}M | {time.time()-t0:.0f}s", flush=True)

print(f"Parsed {rows:,} -> {len(entries):,} ok | {errors:,} errors | {time.time()-t0:.1f}s")
print("Sorting...")
t1 = time.time()
entries.sort()
print(f"Sorted {time.time()-t1:.1f}s")
n = len(entries)
with open(BIN, 'wb') as f:
    f.write(struct.pack('<Q', n))
    for h in entries: f.write(h)
print(f"Written {n:,} entries -> {(8+n*20)/1024**2:.1f} MB")
print(f"Total: {time.time()-t0:.1f}s")
