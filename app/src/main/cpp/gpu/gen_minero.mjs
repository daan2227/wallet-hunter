// Genera minero_spv.h desde minero.comp (con minero_sha.inc dentro), con glslang en WebAssembly:
//   npm install @webgpu/glslang@0.0.15 && node gen_minero.mjs
import glslangInit from '@webgpu/glslang';
import fs from 'fs';
import path from 'path';
const dir = path.dirname(new URL(import.meta.url).pathname);
const comp = fs.readFileSync(path.join(dir, 'minero.comp'), 'utf8')
    .replace('#include "minero_sha.inc"', fs.readFileSync(path.join(dir, 'minero_sha.inc'), 'utf8'))
    .replace('#include "minero_sha_rapida.inc"', fs.readFileSync(path.join(dir, 'minero_sha_rapida.inc'), 'utf8'));
// node-devel carga síncrono; su .then de Emscripten se resuelve a sí mismo, así que no se espera con await.
const glslang = glslangInit();
const spv = glslang.compileGLSL(comp, 'compute', false, '1.0');
const filas = [];
for (let i = 0; i < spv.length; i += 8) filas.push('\t' + Array.from(spv.slice(i, i + 8), x => '0x' + x.toString(16).padStart(8, '0')).join(','));
fs.writeFileSync(path.join(dir, 'minero_spv.h'),
    '/* SPIR-V de minero.comp (con minero_sha.inc). Generado por gen_minero.mjs: no editar a mano. */\n#pragma once\n#include <stdint.h>\n' +
    'static const uint32_t MINERO_SPV[] = {\n' + filas.join(',\n') + '\n};\n');
console.log('minero_spv.h: ' + spv.length + ' palabras');
