#pragma once
/* La lista de coincidencias encontradas.
 *
 * Vivia suelta en hunter_jni.cpp como un std::vector sin tope y sin mirar
 * repetidos, y eso MATO LA APP:
 *
 * El escaneo secuencial, al llegar al final del rango, vuelve a empezar. Con un
 * rango grande eso no pasa nunca; con uno pequeno —el puzzle #1 tiene UNA sola
 * clave— da la vuelta miles de veces por segundo, encuentra la misma clave cada
 * vez, y cada vez se anadia una entrada mas. El servicio, una vez por segundo,
 * pedia la lista ENTERA concatenada en una sola cadena y le hacia .lines().
 * A los pocos minutos: 244 MB de 256 y fuera.
 *
 * Lo que importa aqui no es el puzzle #1 —eso es un caso concreto— sino que una
 * lista que solo crece acaba en lo mismo por cualquier camino: una direccion
 * repetida en el CSV, un rango que se reescanea, un trabajador que reenvia. Asi
 * que la lista se acota y no admite repetidos.
 *
 * Esta en su propia cabecera para que tools/ec-harness/coincidencias pueda
 * probarla: hunter_jni.cpp no se puede compilar fuera de Android, asi que
 * mientras esto viviera alli no habia forma de tener una prueba de que no
 * vuelve a crecer sin freno.
 */
#include <string>
#include <deque>
#include <mutex>

/* Cuantas se guardan. Es para ENSENAR y para avisar, no un archivo: el archivo
 * es coincidencias.txt, que se escribe aparte y no se toca. 200 entradas son
 * unos 30 KB y de sobra para cualquier pantalla. */
#define COINC_TOPE 200

typedef struct {
    std::deque<std::string> lista;
    std::mutex              mtx;
    /* Cuantas se han encontrado en total, incluidas las repetidas y las que ya
     * han salido por el tope. El contador de la pantalla sale de aqui: si
     * dijera `lista.size()` se quedaria clavado en 200 y pareceria que la
     * busqueda se ha parado. */
    unsigned long long      total;
    /* Cuantas se han descartado por repetidas. Sirve para ver desde fuera que
     * el escaneo esta dando vueltas sobre lo mismo, que es justo lo que pasaba
     * y no se veia por ningun lado. */
    unsigned long long      repetidas;
} Coincidencias;

static void coinc_init(Coincidencias *c){
    c->lista.clear(); c->total=0; c->repetidas=0;
}

/* Anade una si no estaba ya.
 * @return 1 si es nueva (y por tanto hay que avisar y escribirla al fichero),
 *         0 si ya estaba. */
static int coinc_add(Coincidencias *c,const std::string &s){
    std::lock_guard<std::mutex> lk(c->mtx);
    c->total++;
    for(size_t i=0;i<c->lista.size();i++)
        if(c->lista[i]==s){ c->repetidas++; return 0; }
    c->lista.push_back(s);
    while(c->lista.size()>COINC_TOPE) c->lista.pop_front();
    return 1;
}

/* Todas, separadas por saltos de linea. */
static std::string coinc_texto(Coincidencias *c){
    std::lock_guard<std::mutex> lk(c->mtx);
    std::string r;
    for(size_t i=0;i<c->lista.size();i++){ r+=c->lista[i]; r+="\n"; }
    return r;
}

/* Saca la primera, o "" si no queda ninguna. */
static std::string coinc_pop(Coincidencias *c){
    std::lock_guard<std::mutex> lk(c->mtx);
    if(c->lista.empty()) return std::string();
    std::string s=c->lista.front();
    c->lista.pop_front();
    return s;
}

static size_t coinc_cuantas(Coincidencias *c){
    std::lock_guard<std::mutex> lk(c->mtx);
    return c->lista.size();
}
