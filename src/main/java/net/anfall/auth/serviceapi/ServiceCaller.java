package net.anfall.auth.serviceapi;

import java.time.Duration;
import net.anfall.auth.ratelimit.Throttle;
import net.anfall.auth.service.ServiceInfo;
import net.anfall.auth.service.ServiceRepository;
import net.anfall.auth.web.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

/** Findet zu einem Dienst-Token den Dienst. Ein User-Token (ohne das Merkmal token_use=service) wird abgelehnt. */
@Component
class ServiceCaller {

    /** Anfragen je Dienst und Minute. */
    private static final int MAX_PER_MINUTE = 600;

    private final ServiceRepository services;
    private final Throttle throttle;

    ServiceCaller(ServiceRepository services, Throttle throttle) {
        this.services = services;
        this.throttle = throttle;
    }

    ServiceInfo require(Jwt jwt) {
        if (!"service".equals(jwt.getClaimAsString("token_use"))) {
            throw new ApiException(HttpStatus.FORBIDDEN, "not_a_service_token");
        }
        // Bei client_credentials ist sub die Client-ID, und die ist der Schlüssel des Dienstes
        ServiceInfo service = services.findByClientId(jwt.getSubject())
                .orElseThrow(() -> new ApiException(HttpStatus.FORBIDDEN, "service_unknown"));
        if (!throttle.allow("serviceapi", service.key(), MAX_PER_MINUTE, Duration.ofMinutes(1))) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "too_many_requests");
        }
        return service;
    }
}
