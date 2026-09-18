/* El nodo de Tailscale (tsnet) empotrado, en su PROPIA libreria.
 *
 * POR QUE APARTE, Y NO DENTRO DE libhunter_jni.so
 *
 * Estaba dentro, y rompio la app entera. libtailscale.so trae consigo el
 * runtime de Go, que arranca al CARGAR la libreria. Al enlazarla dentro de
 * libhunter_jni.so, el runtime de Go arrancaba al abrir la app —antes de que
 * nadie hubiera pedido nada de Tailscale— y System.loadLibrary fallaba.
 *
 * Y el fallo no se veia. La pantalla principal tiene esto en la pestana de
 * escaneo:
 *
 *     try { HunterEngine.setBip39Paths(pathMask) } catch (e: Throwable) {}
 *
 * que se tragaba el UnsatisfiedLinkError sin decir nada. Luego la pestana de
 * puzzle llamaba a setBatchSize sin proteger, y ahi el Error subia hasta un
 * catch (e: Exception) —que NO atrapa Error— y mataba la app. Lo que se veia
 * era una pantalla negra con un "Building Puzzle..." colgado, sin un solo
 * mensaje.
 *
 * Asi que tsnet vive aqui, en libtsbridge.so, y se carga SOLO cuando alguien
 * enciende el nodo. Si Go no arranca, lo unico que pasa es que el boton dice
 * que no se puede — la app sigue funcionando entera.
 *
 * Es ademas lo correcto aunque no hubiera fallado: 14 MB de runtime de Go no
 * tienen por que iniciarse para buscar claves en local.
 */
#include <jni.h>
#include <string>
#include <mutex>
#include <cstdio>
#include <fcntl.h>      /* open, para el fichero de registro de tsnet */
#include <unistd.h>

extern "C" {

/* ---------- tsnet empotrado ----------
 *
 * El nodo de Tailscale DENTRO de la app, en vez de depender de que la app de
 * Tailscale este instalada y encendida enrutando el movil entero.
 *
 * Todo esto va entre #ifdef porque libtailscale.so es OPCIONAL: la produce
 * scripts/build-libtailscale.sh, que mete la cadena de Go y 14 MB, y nada de
 * eso puede ser motivo de que una app que funciona deje de construirse. Sin
 * ella el APK compila igual y estas funciones contestan que no hay tsnet.
 *
 * Por eso existen las dos versiones de cada una: Kotlin declara sus external
 * fun una sola vez y tienen que resolverse SIEMPRE, haya o no libreria. Un
 * external fun sin simbolo detras no falla al compilar, falla al llamarlo, con
 * un UnsatisfiedLinkError en medio de la busqueda.
 */
#ifdef TIENE_TAILSCALE
#include "tailscale.h"

/* El nodo. Uno solo: la app es un aparato del tailnet, no varios. */
static int g_ts = -1;
static std::mutex g_ts_mtx;

/* El ultimo error que dio tsnet, para poder ensenarlo en vez de un numero. */
static std::string ts_error(int sd){
    char buf[512]; buf[0]=0;
    if(tailscale_errmsg(sd,buf,sizeof(buf))==0 && buf[0]) return std::string(buf);
    return std::string("sin detalle");
}
#endif

/* Si se ha podido cargar esta libreria, tsnet esta. El caso de que NO este se
 * resuelve en Kotlin: System.loadLibrary("tsbridge") lanza y TsNet lo trata. */
extern "C" JNIEXPORT jboolean JNICALL
Java_com_hunter_btc_TsNet_disponibleNativo(JNIEnv *, jobject){
    return JNI_TRUE;
}

/* Las interfaces de red, que se las damos nosotros desde Java.
 *
 * Lo exporta nuestro anadido a libtailscale (interfaces_desde_java.go), asi que
 * no esta en tailscale.h y hay que declararlo a mano.
 *
 * Android 11+ le prohibe a Go preguntar por las interfaces con netlink, y sin
 * ellas tailscale_up muere con "netlinkrib: permission denied". java.net si
 * puede enumerarlas, asi que se las pasamos hechas.
 */
#ifdef TIENE_TAILSCALE
extern "C" int tsnet_set_interfaces(char *spec);
#endif

extern "C" JNIEXPORT jint JNICALL
Java_com_hunter_btc_TsNet_ponerInterfaces(JNIEnv *env, jobject, jstring jspec){
#ifndef TIENE_TAILSCALE
    return -1;
#else
    if(!jspec) return -1;
    const char *s = env->GetStringUTFChars(jspec,0);
    if(!s) return -1;
    /* La firma pide char* y no const char*: Go no promete no tocarlo, aunque
       de hecho solo lo lee. Se copia para no darle nunca el buffer de la JVM. */
    std::string copia(s);
    env->ReleaseStringUTFChars(jspec,s);
    return (jint)tsnet_set_interfaces(&copia[0]);
#endif
}

/* Levanta el nodo y espera a que este autenticado.
 *
 * @param jdir  carpeta privada de la app donde tsnet guarda su estado. Tiene
 *   que ser escribible y sobrevivir entre arranques: ahi vive la identidad del
 *   nodo, y perderla obliga a volver a autorizarlo.
 * @return "" si todo bien, o el motivo. Se devuelve el TEXTO y no un codigo
 *   porque el que lo lee es el usuario: "clave caducada" se entiende y "-3" no.
 *
 * BLOQUEA hasta que el nodo esta arriba. Nunca desde el hilo principal.
 */
extern "C" JNIEXPORT jstring JNICALL
Java_com_hunter_btc_TsNet_arrancar(JNIEnv *env, jobject, jstring jclave,
                                   jstring jnombre, jstring jdir){
#ifndef TIENE_TAILSCALE
    return env->NewStringUTF("Esta compilacion no lleva tsnet dentro");
#else
    std::lock_guard<std::mutex> lk(g_ts_mtx);
    if(g_ts >= 0) return env->NewStringUTF("");      /* ya estaba */

    int sd = tailscale_new();
    if(sd < 0) return env->NewStringUTF("No se pudo crear el nodo");

    const char *dir = jdir ? env->GetStringUTFChars(jdir,0) : NULL;
    if(dir){
        tailscale_set_dir(sd,dir);
        /* Los registros de tsnet a un fichero nuestro.
         *
         * Hace falta porque cuando esto falla de verdad, falla DENTRO de Go: un
         * panico en una libreria c-shared llama a abort() y se lleva el proceso
         * entero. No hay excepcion que capturar, no se escribe crash_log.txt, y
         * lo unico que se ve desde fuera es que la app se cierra.
         *
         * Lo que Go haya dicho antes de morir sale por aqui. Es la unica via que
         * hay para leerlo sin un ordenador conectado por adb.
         *
         * O_TRUNC y no O_APPEND: interesa el intento de AHORA. Un fichero que
         * crece con todos los intentos obliga a buscar dentro cual fue el
         * ultimo, que es justo lo que no quieres cuando algo va mal. */
        std::string ruta = std::string(dir) + "/tsnet.log";
        int fd = open(ruta.c_str(), O_WRONLY|O_CREAT|O_TRUNC, 0600);
        if(fd >= 0){
            tailscale_set_logfd(sd, fd);
            /* No se cierra: lo usa Go mientras el nodo viva. Se lo queda el. */
        }
        env->ReleaseStringUTFChars(jdir,dir);
    }
    const char *nom = jnombre ? env->GetStringUTFChars(jnombre,0) : NULL;
    if(nom){ tailscale_set_hostname(sd,nom); env->ReleaseStringUTFChars(jnombre,nom); }
    const char *cla = jclave ? env->GetStringUTFChars(jclave,0) : NULL;
    if(cla){ tailscale_set_authkey(sd,cla); env->ReleaseStringUTFChars(jclave,cla); }

    /* up() y no start(): start deja el nodo corriendo pero sin esperar a que
       este autorizado, y entonces el primer dial falla por una razon que no
       tiene nada que ver con la red. */
    if(tailscale_up(sd) != 0){
        std::string e = ts_error(sd);
        tailscale_close(sd);
        return env->NewStringUTF(e.c_str());
    }
    g_ts = sd;
    return env->NewStringUTF("");
#endif
}

/* Las direcciones del nodo en el tailnet, separadas por coma. "" si no hay. */
extern "C" JNIEXPORT jstring JNICALL
Java_com_hunter_btc_TsNet_direcciones(JNIEnv *env, jobject){
#ifndef TIENE_TAILSCALE
    return env->NewStringUTF("");
#else
    std::lock_guard<std::mutex> lk(g_ts_mtx);
    if(g_ts < 0) return env->NewStringUTF("");
    char buf[256]; buf[0]=0;
    if(tailscale_getips(g_ts,buf,sizeof(buf)) != 0) return env->NewStringUTF("");
    return env->NewStringUTF(buf);
#endif
}

/* Para el nodo. Idempotente. */
extern "C" JNIEXPORT void JNICALL
Java_com_hunter_btc_TsNet_parar(JNIEnv *, jobject){
#ifdef TIENE_TAILSCALE
    std::lock_guard<std::mutex> lk(g_ts_mtx);
    if(g_ts >= 0){ tailscale_close(g_ts); g_ts = -1; }
#endif
}

/* ---------- Conexiones por tsnet ----------
 *
 * Lo que devuelven listen, accept y dial son DESCRIPTORES DE FICHERO
 * corrientes: la cabecera de libtailscale lo dice tal cual —"it is a pipe(2) on
 * which you can use read(2), write(2), and close(2)"—. Eso es lo que hace que
 * empotrar tsnet no obligue a reescribir el protocolo: el reparto del cluster
 * sigue mandando sus mismas lineas JSON, solo que por otro descriptor.
 *
 * Aqui NO se envuelve nada en objetos. Se devuelve el numero y que Kotlin lo
 * meta en un ParcelFileDescriptor, que es lo que sabe hacer Android. Cuanto
 * menos haya en este lado, menos hay que depurar a ciegas.
 *
 * NO hay tailscale_listener_close ni tailscale_conn_close: se cierran con
 * close(2), que es lo que hace ParcelFileDescriptor al cerrarse.
 *
 * accept() y dial() BLOQUEAN. Nunca desde el hilo principal.
 *
 * Convenio de errores: negativo es fallo. -1 es "no hay tsnet en esta
 * compilacion o el nodo no esta arrancado" y -2 es "tsnet ha dicho que no",
 * para que quien llame pueda distinguir "no se puede" de "no ha podido".
 */

/* Escucha en el puerto dado. @return el descriptor del escuchador, o <0. */
extern "C" JNIEXPORT jint JNICALL
Java_com_hunter_btc_TsNet_escuchar(JNIEnv *, jobject, jint puerto){
#ifndef TIENE_TAILSCALE
    return -1;
#else
    std::lock_guard<std::mutex> lk(g_ts_mtx);
    if(g_ts < 0) return -1;
    char dir[32]; snprintf(dir,sizeof(dir),":%d",(int)puerto);
    tailscale_listener l = 0;
    if(tailscale_listen(g_ts,"tcp",dir,&l) != 0) return -2;
    return (jint)l;
#endif
}

/* Espera una conexion entrante. BLOQUEA. @return descriptor, o <0. */
extern "C" JNIEXPORT jint JNICALL
Java_com_hunter_btc_TsNet_aceptar(JNIEnv *, jobject, jint escuchador){
#ifndef TIENE_TAILSCALE
    return -1;
#else
    /* SIN el mutex: accept se queda bloqueado hasta que llegue alguien, y con
       el cerrojo cogido dejaria colgada a toda la app —incluido parar()—. El
       descriptor del escuchador ya es nuestro y no lo toca nadie mas. */
    tailscale_conn c = 0;
    if(tailscale_accept((tailscale_listener)escuchador,&c) != 0) return -2;
    return (jint)c;
#endif
}

/* Abre una conexion. destino es "maquina:puerto". BLOQUEA. @return desc, o <0. */
extern "C" JNIEXPORT jint JNICALL
Java_com_hunter_btc_TsNet_marcar(JNIEnv *env, jobject, jstring jdestino){
#ifndef TIENE_TAILSCALE
    return -1;
#else
    int sd;
    { std::lock_guard<std::mutex> lk(g_ts_mtx); sd = g_ts; }
    if(sd < 0) return -1;
    const char *d = env->GetStringUTFChars(jdestino,0);
    if(!d) return -1;
    tailscale_conn c = 0;
    /* Igual que accept: fuera del cerrojo, porque marcar tarda lo que tarde la
       red y mientras tanto la app tiene que seguir viva. */
    int r = tailscale_dial(sd,"tcp",d,&c);
    env->ReleaseStringUTFChars(jdestino,d);
    return r==0 ? (jint)c : -2;
#endif
}

/* Quien hay al otro lado de una conexion aceptada. "" si no se sabe.
 *
 * Hace falta porque el maestro identifica a cada trabajador por su direccion, y
 * con tsnet no hay Socket del que sacarla. */
extern "C" JNIEXPORT jstring JNICALL
Java_com_hunter_btc_TsNet_remoto(JNIEnv *env, jobject, jint escuchador, jint con){
#ifndef TIENE_TAILSCALE
    return env->NewStringUTF("");
#else
    char buf[128]; buf[0]=0;
    if(tailscale_getremoteaddr((tailscale_listener)escuchador,
                               (tailscale_conn)con,buf,sizeof(buf)) != 0)
        return env->NewStringUTF("");
    return env->NewStringUTF(buf);
#endif
}

/* El ultimo error del nodo, para poder ensenarlo. "" si no hay nodo. */
extern "C" JNIEXPORT jstring JNICALL
Java_com_hunter_btc_TsNet_ultimoFallo(JNIEnv *env, jobject){
#ifndef TIENE_TAILSCALE
    return env->NewStringUTF("");
#else
    std::lock_guard<std::mutex> lk(g_ts_mtx);
    if(g_ts < 0) return env->NewStringUTF("");
    return env->NewStringUTF(ts_error(g_ts).c_str());
#endif
}
}
