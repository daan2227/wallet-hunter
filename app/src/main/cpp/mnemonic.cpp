#include "mnemonic.h"
#include <openssl/evp.h>
#include <openssl/hmac.h>
#include <cstring>
#include <algorithm>

/**
 * PBKDF2-HMAC-SHA512
 * Implementación BIP39 estándar:
 *   password   = mnemonic (UTF-8, NFKD normalizado idealmente)
 *   salt       = "mnemonic" + passphrase
 *   iterations = 2048
 *   keylen     = 64 bytes
 */
bool mnemonic_to_seed(const std::string& mnemonic,
                      const std::string& passphrase,
                      uint8_t seed_out[64])
{
    // Construir salt: "mnemonic" + passphrase
    const std::string salt_prefix = "mnemonic";
    std::string salt = salt_prefix + passphrase;

    int result = PKCS5_PBKDF2_HMAC(
        mnemonic.c_str(),          // password
        (int)mnemonic.size(),      // password length
        (const unsigned char*)salt.c_str(),  // salt
        (int)salt.size(),          // salt length
        2048,                      // iterations (BIP39 estándar)
        EVP_sha512(),              // hash function
        64,                        // key length en bytes
        seed_out                   // output buffer
    );

    return result == 1;
}

int validate_mnemonic_words(const std::vector<std::string>& words,
                            const std::vector<std::string>& wordlist)
{
    for (int i = 0; i < (int)words.size(); i++) {
        auto it = std::find(wordlist.begin(), wordlist.end(), words[i]);
        if (it == wordlist.end()) {
            return i; // índice de palabra inválida
        }
    }
    return -1; // todas válidas
}

std::vector<int> mnemonic_to_indices(const std::vector<std::string>& words,
                                     const std::vector<std::string>& wordlist)
{
    std::vector<int> indices;
    for (const auto& word : words) {
        auto it = std::find(wordlist.begin(), wordlist.end(), word);
        if (it != wordlist.end()) {
            indices.push_back((int)(it - wordlist.begin()));
        } else {
            indices.push_back(-1);
        }
    }
    return indices;
}
