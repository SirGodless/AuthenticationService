package net.anfall.auth.login;

import org.springframework.http.ResponseCookie;

/** Ergebnis eines Anmeldeschritts: Cookie für die Antwort und ob noch ein 2FA-Code fehlt. */
public record LoginResult(boolean totpRequired, ResponseCookie cookie) {
}
