package net.anfall.auth.web;

import org.springframework.http.HttpStatus;

/**
 * Fehler mit festem Code. Die API liefert nur den Code, die Texte (Englisch und Deutsch)
 * stehen in den Sprachdateien der UI. So steht kein Fließtext fest im Backend.
 */
public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    public ApiException(HttpStatus status, String code) {
        super(code);
        this.status = status;
        this.code = code;
    }

    public HttpStatus status() {
        return status;
    }

    public String code() {
        return code;
    }
}
