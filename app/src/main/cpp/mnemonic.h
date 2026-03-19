#pragma once
#ifndef WALLET_HUNTER_MNEMONIC_H
#define WALLET_HUNTER_MNEMONIC_H

#include <stdint.h>
#include <string>
#include <vector>

/**
 * Convierte una seed phrase BIP39 a 64 bytes de seed via PBKDF2-HMAC-SHA512.
 *
 * @param mnemonic  Frase de 12/24 palabras separadas por espacios
 * @param passphrase Passphrase adicional (vacía por defecto)
 * @param seed_out  Buffer de salida, debe tener al menos 64 bytes
 * @return true si OK, false si error
 */
bool mnemonic_to_seed(const std::string& mnemonic,
                      const std::string& passphrase,
                      uint8_t seed_out[64]);

/**
 * Valida que todas las palabras de la frase existen en el wordlist BIP39.
 * @param words    Vector de palabras
 * @param wordlist Vector de 2048 palabras BIP39
 * @return índice de la primera palabra inválida, o -1 si todas OK
 */
int validate_mnemonic_words(const std::vector<std::string>& words,
                            const std::vector<std::string>& wordlist);

/**
 * Convierte un mnemonic a índices del wordlist BIP39.
 * Útil para el motor de combinaciones.
 */
std::vector<int> mnemonic_to_indices(const std::vector<std::string>& words,
                                     const std::vector<std::string>& wordlist);

#endif // WALLET_HUNTER_MNEMONIC_H
