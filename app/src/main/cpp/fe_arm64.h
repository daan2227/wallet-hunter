#pragma once
/* Retirado: el producto en ensamblador ARM64 ahora ES fe_mul en ARM64 (ver
 * jac_batch.h), y la version de C sigue como fe_mul_c. Se deja el nombre
 * fe_mul_asm para la prueba de rendimiento de Debug. */
#include "jac_batch.h"
#if defined(FE_ARM64)
static inline void fe_mul_asm(fe_t r,const fe_t a,const fe_t b){ fe_mul(r,a,b); }
#endif
