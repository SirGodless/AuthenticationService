package net.anfall.auth.oidc;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

/**
 * Wer nicht angemeldet am Authorize-Endpunkt ankommt, wird zur Login-Seite geschickt und danach zurück an
 * genau diese Adresse (Parameter "continue"). Die Login-Seite folgt dem Parameter nur für Adressen, die mit
 * /oauth2/authorize beginnen. Alle anderen Endpunkte (Token, UserInfo) antworten mit 401.
 */
@Component
public class LoginRedirectEntryPoint implements AuthenticationEntryPoint {

    static final String AUTHORIZE_PATH = "/oauth2/authorize";

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
                         AuthenticationException exception) throws IOException {
        if (!AUTHORIZE_PATH.equals(request.getRequestURI()) || !"GET".equals(request.getMethod())) {
            response.sendError(HttpStatus.UNAUTHORIZED.value());
            return;
        }
        String back = AUTHORIZE_PATH + (request.getQueryString() == null ? "" : "?" + request.getQueryString());
        response.sendRedirect("/login?continue=" + URLEncoder.encode(back, StandardCharsets.UTF_8));
    }
}
