"""Beispiel: Ein Dienst nutzt die Schnittstellen des Hubs (siehe docs/INTEGRATION.md).

Zeigt: Token des Dienstes holen, Rechte und Profile von Nutzern abfragen, Farbe und Status melden, ein Token eines Nutzers prüfen
und ein Logout-Token (Back-Channel-Logout) prüfen.
Braucht:  pip install requests pyjwt cryptography
Aufruf:   HUB_ISSUER=https://anfall.net CLIENT_ID=media CLIENT_SECRET=... python service_client.py <sub-des-nutzers>
"""
import os
import sys
import time

import jwt  # PyJWT
import requests

ISSUER = os.environ["HUB_ISSUER"]                       # der feste Aussteller, so wie er in den Tokens steht
BASE_URL = os.environ.get("HUB_BASE_URL", ISSUER)       # Adresse, unter der der Hub erreichbar ist (meist gleich)
CLIENT_ID = os.environ["CLIENT_ID"]
CLIENT_SECRET = os.environ["CLIENT_SECRET"]

_token = {"value": None, "expires": 0.0}
_jwks = jwt.PyJWKClient(f"{BASE_URL}/oauth2/jwks", cache_keys=True)


def service_token() -> str:
    """Token des Dienstes (client_credentials). Wird bis kurz vor Ablauf wiederverwendet."""
    if _token["value"] and time.time() < _token["expires"] - 30:
        return _token["value"]
    r = requests.post(
        f"{BASE_URL}/oauth2/token",
        auth=(CLIENT_ID, CLIENT_SECRET),
        data={"grant_type": "client_credentials", "scope": "service"},
        timeout=10,
    )
    r.raise_for_status()
    body = r.json()
    _token["value"], _token["expires"] = body["access_token"], time.time() + body["expires_in"]
    return _token["value"]


def _headers() -> dict:
    return {"Authorization": f"Bearer {service_token()}"}


def user_permissions(sub: str, prefix: str | None = None) -> dict:
    """Rechte eines Nutzers aus dem eigenen Namensraum. {'access': bool, 'permissions': [...]}"""
    r = requests.get(
        f"{BASE_URL}/api/service/users/{sub}/permissions",
        params={"prefix": prefix} if prefix else None,
        headers=_headers(),
        timeout=10,
    )
    r.raise_for_status()
    return r.json()


def users(subs: list[str] | None = None) -> list[dict]:
    """Profile (sub, username, email, emailVerified, locale, banned, access). Ohne subs: alle Nutzer mit Zugang zum Dienst."""
    r = requests.get(f"{BASE_URL}/api/service/users", params={"sub": subs} if subs else None, headers=_headers(), timeout=10)
    r.raise_for_status()
    return r.json()["users"]


def report(color: str | None = None, state: str | None = None, ttl_seconds: int = 180) -> None:
    """Farbe (#rrggbb, "" löscht) und/oder Status (up oder down) melden."""
    body = {}
    if color is not None:
        body["accentColor"] = color
    if state is not None:
        body["status"] = {"state": state, "ttlSeconds": ttl_seconds}
    requests.put(f"{BASE_URL}/api/service/self", json=body, headers=_headers(), timeout=10).raise_for_status()


def verify_user_token(token: str) -> dict:
    """Prüft ein Token eines Nutzers (ID-Token oder Access-Token): Signatur, Aussteller, Empfänger und Ablauf."""
    key = _jwks.get_signing_key_from_jwt(token).key
    return jwt.decode(token, key, algorithms=["RS256"], audience=CLIENT_ID, issuer=ISSUER)


LOGOUT_EVENT = "http://schemas.openid.net/event/backchannel-logout"


def verify_logout_token(token: str) -> dict:
    """Prüft ein Logout-Token des Hubs (Abschnitt 5.6 der Anleitung). Gibt die Claims zurück, sonst eine Ausnahme.

    Danach beendet der Dienst die Sitzungen: bei vorhandenem claims["sid"] nur die mit dieser sid, sonst alle Sitzungen von claims["sub"].
    Zusätzlich sollte der Dienst das jti kurz merken und ein zweites Mal ablehnen.
    """
    if jwt.get_unverified_header(token).get("typ") != "logout+jwt":
        raise jwt.InvalidTokenError("typ ist nicht logout+jwt")
    key = _jwks.get_signing_key_from_jwt(token).key
    claims = jwt.decode(token, key, algorithms=["RS256"], audience=CLIENT_ID, issuer=ISSUER,
                        options={"require": ["iss", "aud", "iat", "exp", "jti", "events"]})
    if LOGOUT_EVENT not in claims["events"]:
        raise jwt.InvalidTokenError("events enthält das Logout-Ereignis nicht")
    if "nonce" in claims:
        raise jwt.InvalidTokenError("ein Logout-Token darf kein nonce haben")
    if "sub" not in claims and "sid" not in claims:
        raise jwt.InvalidTokenError("weder sub noch sid")
    return claims


if __name__ == "__main__":
    sub = sys.argv[1]
    info = user_permissions(sub)
    print("Zugang:", info["access"])
    for p in info["permissions"]:
        print(" -", p["key"], p.get("value", "unbegrenzt" if p.get("unlimited") else "ja"))
    print("Nutzer mit Zugang:", [u["username"] for u in users()])
    report(state="up")
    print("Status gemeldet. Das Token des Dienstes selbst ist gültig bis:", time.strftime("%H:%M:%S", time.localtime(_token["expires"])))
    claims = verify_user_token(service_token())
    print("Token geprüft, sub =", claims["sub"], "token_use =", claims.get("token_use"))
