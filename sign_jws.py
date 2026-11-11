#!/usr/bin/env python3
"""Detached JWS with x5c, as droidtop verifies it (docs/SPEC.md 12a). Usage:
sign_jws.py <typ> <content file> <key.pem> <leaf.pem> [more chain .pem ...] > out.jws
typ: droidtop-plugin+jws (manifest.json -> manifest.jws) or droidtop-catalog+jws (catalog.json / index.json)."""
import base64, json, sys
from cryptography import x509
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec, rsa, padding, utils

def b64u(data: bytes) -> bytes:
    return base64.urlsafe_b64encode(data).rstrip(b"=")

typ, content_path, key_path, *chain_paths = sys.argv[1:]
key = serialization.load_pem_private_key(open(key_path, "rb").read(), None)
chain = [x509.load_pem_x509_certificate(open(p, "rb").read()) for p in chain_paths]
x5c = [base64.b64encode(c.public_bytes(serialization.Encoding.DER)).decode() for c in chain]
alg = "ES256" if isinstance(key, ec.EllipticCurvePrivateKey) else "RS256"
header = b64u(json.dumps({"alg": alg, "typ": typ, "x5c": x5c}, separators=(",", ":")).encode())
signing_input = header + b"." + b64u(open(content_path, "rb").read())
if alg == "ES256":
    r, s = utils.decode_dss_signature(key.sign(signing_input, ec.ECDSA(hashes.SHA256())))
    signature = r.to_bytes(32, "big") + s.to_bytes(32, "big")
else:
    signature = key.sign(signing_input, padding.PKCS1v15(), hashes.SHA256())
# Check our own signature against the leaf's public key before anything is packaged.
if alg == "ES256":
    chain[0].public_key().verify(utils.encode_dss_signature(int.from_bytes(signature[:32], "big"), int.from_bytes(signature[32:], "big")),
                                 signing_input, ec.ECDSA(hashes.SHA256()))
else:
    chain[0].public_key().verify(signature, signing_input, padding.PKCS1v15(), hashes.SHA256())
sys.stdout.write((header + b".." + b64u(signature)).decode())
