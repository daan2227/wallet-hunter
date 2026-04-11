#include <iostream>
#include <fstream>
#include <vector>
#include <string>
#include <unordered_set>
#include <thread>
#include <atomic>
#include <chrono>
#include <iomanip>
#include <cstring>
#include <secp256k1.h>
#include <openssl/sha.h>
#include <openssl/ripemd.h>

using namespace std;

struct Hash160 {
    unsigned char data[20];
    bool operator==(const Hash160& other) const {
        return memcmp(data, other.data, 20) == 0;
    }
};

struct Hash160Hasher {
    size_t operator()(const Hash160& h) const {
        size_t res;
        memcpy(&res, h.data, sizeof(size_t));
        return res;
    }
};

unordered_set<Hash160, Hash160Hasher> db;
atomic<long long> keys_checked(0);

// Función de hashing rápida
void get_hash160(const unsigned char* pubkey, size_t len, unsigned char* out) {
    unsigned char sha256_res[32];
    SHA256(pubkey, len, sha256_res);
    RIPEMD160(sha256_res, 32, out);
}

void search_worker(secp256k1_context* ctx) {
    unsigned char privkey[32];
    unsigned char pubkey[33];
    unsigned char h160_res[20];
    size_t pubkey_len = 33;
    secp256k1_pubkey pubkey_obj;

    // Semilla aleatoria inicial única para este hilo
    for (int i = 0; i < 32; i++) privkey[i] = rand() % 256;

    while (true) {
        // Optimización: Sumar 1 a la llave privada en lugar de usar rand()
        for (int i = 31; i >= 0; i--) {
            if (++privkey[i] != 0) break;
        }

        if (secp256k1_ec_pubkey_create(ctx, &pubkey_obj, privkey)) {
            secp256k1_ec_pubkey_serialize(ctx, pubkey, &pubkey_len, &pubkey_obj, SECP256K1_EC_COMPRESSED);
            get_hash160(pubkey, pubkey_len, h160_res);

            Hash160 h;
            memcpy(h.data, h160_res, 20);
            
            if (db.count(h)) {
                ofstream found("found.txt", ios::app);
                found << "PRIVKEY: ";
                for(int i=0; i<32; i++) found << hex << setfill('0') << setw(2) << (int)privkey[i];
                found << "\n";
                found.close();
                cout << "\n\a [!!!] MATCH ENCONTRADO! Revisar found.txt [!!!]\n" << endl;
            }
        }
        keys_checked++;
    }
}

int main() {
    ifstream file("utxos_legacy_segwit.bin", ios::binary);
    if (!file) {
        cerr << "Error: No se encontro utxos_legacy_segwit.bin" << endl;
        return 1;
    }

    Hash160 h;
    while (file.read((char*)h.data, 20)) {
        db.insert(h);
    }
    file.close();

    int threads_count = thread::hardware_concurrency();
    vector<thread> threads;
    for (int i = 0; i < threads_count; i++) {
        secp256k1_context* ctx = secp256k1_context_create(SECP256K1_CONTEXT_SIGN);
        threads.emplace_back(search_worker, ctx);
    }

    auto start = chrono::steady_clock::now();
    while (true) {
        this_thread::sleep_for(chrono::seconds(5));
        auto end = chrono::steady_clock::now();
        double diff = chrono::duration<double>(end - start).count();
        if (diff > 0) {
            cout << "\r[>] Speed: " << fixed << setprecision(0) << (keys_checked / diff) << " K/s | Total: " << keys_checked << flush;
        }
    }
    return 0;
}
