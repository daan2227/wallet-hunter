# Genera una jerarquía DNSSEC de prueba (raíz propia) y las respuestas DNS en binario, para Bip353Test.
import base64, json, sys
import dns.dnssec, dns.message, dns.name, dns.rdata, dns.rdataclass, dns.rdatatype, dns.rrset
from dns.dnssec import Algorithm
from cryptography.hazmat.primitives.asymmetric import rsa, ec, ed25519

INI, FIN, AHORA = 1_700_000_000, 1_900_000_000, 1_800_000_000
TTL = 3600

def clave(alg):
    if alg in (Algorithm.RSASHA256, Algorithm.RSASHA512): return rsa.generate_private_key(65537, 2048)
    if alg == Algorithm.ECDSAP256SHA256: return ec.generate_private_key(ec.SECP256R1())
    if alg == Algorithm.ECDSAP384SHA384: return ec.generate_private_key(ec.SECP384R1())
    if alg == Algorithm.ED25519: return ed25519.Ed25519PrivateKey.generate()

class Zona:
    def __init__(self, nombre, alg):
        self.n = dns.name.from_text(nombre); self.alg = alg
        self.kp = clave(alg); self.zp = clave(alg)
        self.ksk = dns.dnssec.make_dnskey(self.kp.public_key(), alg, flags=257)
        self.zsk = dns.dnssec.make_dnskey(self.zp.public_key(), alg, flags=256)
    def firmar(self, rrset, con_ksk=False, ini=INI, fin=FIN):
        p, k = (self.kp, self.ksk) if con_ksk else (self.zp, self.zsk)
        return dns.dnssec.sign(rrset, p, self.n, k, inception=ini, expiration=fin)
    def dnskey(self):
        rs = dns.rrset.from_rdata(self.n, TTL, self.ksk, self.zsk)
        return rs, [self.firmar(rs, con_ksk=True)]
    def ds(self, digest='SHA256'):
        return dns.dnssec.make_ds(self.n, self.ksk, digest, policy=dns.dnssec.allow_all_policy)

respuestas = {}
def guardar(qname, qtype, secciones):
    q = dns.message.make_query(qname, qtype, want_dnssec=True)
    r = dns.message.make_response(q)
    for rs in secciones: r.answer.append(rs)
    respuestas[f"{dns.name.from_text(qname).to_text().lower()}|{qtype}"] = base64.b64encode(r.to_wire()).decode()

def sig_rrset(rrsig_rdata, owner):
    return dns.rrset.from_rdata(owner, TTL, rrsig_rdata)

raiz = Zona(".", Algorithm.RSASHA256)
tld = Zona("tld.", Algorithm.ECDSAP256SHA256)
ej = Zona("example.tld.", Algorithm.ED25519)
p384 = Zona("p384.tld.", Algorithm.ECDSAP384SHA384)
r512 = Zona("rsa512.tld.", Algorithm.RSASHA512)
sha1 = Zona("sha1.tld.", Algorithm.ECDSAP256SHA256)
falso = Zona("falso.tld.", Algorithm.ECDSAP256SHA256)   # su DS en tld. es de OTRA clave

# DNSKEY de cada zona, autofirmado por su KSK.
for z in (raiz, tld, ej, p384, r512, sha1, falso):
    rs, sigs = z.dnskey()
    guardar(z.n.to_text(), "DNSKEY", [rs] + [sig_rrset(s, z.n) for s in sigs])

# DS de cada delegación, firmado por la ZSK del padre.
def delegar(padre, hijo, digest='SHA256', ds=None):
    rd = ds or hijo.ds(digest)
    rs = dns.rrset.from_rdata(hijo.n, TTL, rd)
    guardar(hijo.n.to_text(), "DS", [rs, sig_rrset(padre.firmar(rs), hijo.n)])
delegar(raiz, tld)
for z in (ej, p384, r512): delegar(tld, z)
delegar(tld, sha1, 'SHA1')
delegar(tld, falso, ds=Zona("falso.tld.", Algorithm.ECDSAP256SHA256).ds())

def txt(owner, *cadenas_por_registro):
    rds = [dns.rdata.from_text(dns.rdataclass.IN, dns.rdatatype.TXT, " ".join('"%s"' % c for c in cadenas)) for cadenas in cadenas_por_registro]
    return dns.rrset.from_rdata(dns.name.from_text(owner), TTL, *rds)

B = "user._bitcoin-payment."
# Válidos.
a = txt("alice." + B + "example.tld.", ["bitcoin:bc1qalice?lno=", "lno1alice"], ["v=spf1 -all"])
guardar("alice." + B + "example.tld.", "TXT", [a, sig_rrset(ej.firmar(a), a.name)])
d = txt("dave." + B + "rsa512.tld.", ["bitcoin:bc1qdave"])
guardar("dave." + B + "rsa512.tld.", "TXT", [d, sig_rrset(r512.firmar(d), d.name)])
# CNAME entre zonas: bob en example.tld. → carol en p384.tld.
cn = dns.rrset.from_rdata(dns.name.from_text("bob." + B + "example.tld."), TTL,
                          dns.rdata.from_text(dns.rdataclass.IN, dns.rdatatype.CNAME, "carol." + B + "p384.tld."))
c = txt("carol." + B + "p384.tld.", ["bitcoin:?lno=lno1carol"])
guardar("bob." + B + "example.tld.", "TXT", [cn, sig_rrset(ej.firmar(cn), cn.name), c, sig_rrset(p384.firmar(c), c.name)])
# Inválidos.
dos = txt("two." + B + "example.tld.", ["bitcoin:bc1qone"], ["BITCOIN:bc1qtwo"])
guardar("two." + B + "example.tld.", "TXT", [dos, sig_rrset(ej.firmar(dos), dos.name)])
e = txt("eve." + B + "example.tld.", ["bitcoin:bc1qeve"])
sig_e = ej.firmar(e)
e_mal = txt("eve." + B + "example.tld.", ["bitcoin:bc1qattacker"])          # el contenido cambia, la firma no
guardar("eve." + B + "example.tld.", "TXT", [e_mal, sig_rrset(sig_e, e.name)])
o = txt("old." + B + "example.tld.", ["bitcoin:bc1qold"])
guardar("old." + B + "example.tld.", "TXT", [o, sig_rrset(ej.firmar(o, ini=INI, fin=AHORA - 7200), o.name)])
s1 = txt("x." + B + "sha1.tld.", ["bitcoin:bc1qsha1"])
guardar("x." + B + "sha1.tld.", "TXT", [s1, sig_rrset(sha1.firmar(s1), s1.name)])
f1 = txt("x." + B + "falso.tld.", ["bitcoin:bc1qfalso"])
guardar("x." + B + "falso.tld.", "TXT", [f1, sig_rrset(falso.firmar(f1), f1.name)])
ns = txt("nosig." + B + "example.tld.", ["bitcoin:bc1qnosig"])
guardar("nosig." + B + "example.tld.", "TXT", [ns])
# Comodín: el registro se firmó como *.user._bitcoin-payment.example.tld y se sirve expandido.
w = txt("*." + B + "example.tld.", ["bitcoin:bc1qwild"])
sig_w = ej.firmar(w)
w_exp = txt("wild." + B + "example.tld.", ["bitcoin:bc1qwild"])
guardar("wild." + B + "example.tld.", "TXT", [w_exp, sig_rrset(sig_w, w_exp.name)])

ds_raiz = raiz.ds('SHA256')
json.dump({"ahora": AHORA, "ancla": "%d %d %d %s" % (ds_raiz.key_tag, ds_raiz.algorithm, ds_raiz.digest_type, ds_raiz.digest.hex()),
           "respuestas": respuestas}, open(sys.argv[1], "w"), indent=1)
print("respuestas:", len(respuestas), "ancla:", ds_raiz.key_tag)

# Comprobación cruzada con dnspython: la cadena válida verifica con su propio validador.
dns.dnssec.validate(a, [sig_rrset(ej.firmar(a), a.name)][0], {ej.n: ej.dnskey()[0]})
print("dnspython valida alice: OK")
