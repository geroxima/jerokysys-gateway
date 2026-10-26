package com.jerokysys.apigateway.security;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.server.ServerWebExchange;

import com.fasterxml.jackson.databind.ObjectMapper;

import reactor.core.publisher.Mono;

/**
 * Devuelve los errores de seguridad del gateway (401/403) en JSON, con el mismo
 * envelope que usan los microservicios, y sin trazas ni detalles internos.
 */
public final class SecurityErrors {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private SecurityErrors() {
    }

    /** 401: no hay token, o el token no es valido / esta vencido. */
    public static Mono<Void> unauthorized(ServerWebExchange exchange, AuthenticationException ex) {
        return write(exchange.getResponse(), HttpStatus.UNAUTHORIZED,
                "UNAUTHORIZED", "Authentication required");
    }

    /** 403: el token es valido pero el rol no alcanza para la ruta. */
    public static Mono<Void> forbidden(ServerWebExchange exchange, AccessDeniedException ex) {
        return write(exchange.getResponse(), HttpStatus.FORBIDDEN,
                "FORBIDDEN", "Access denied");
    }

    private static Mono<Void> write(ServerHttpResponse response, HttpStatus status,
                                    String code, String message) {
        if (response.isCommitted()) {
            return Mono.empty();
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("data", null);
        body.put("message", message);
        body.put("code", code);
        body.put("status", status.value());
        body.put("success", false);

        byte[] bytes;
        try {
            bytes = MAPPER.writeValueAsBytes(body);
        } catch (Exception ex) {
            bytes = ("{\"code\":\"" + code + "\",\"status\":" + status.value() + "}").getBytes();
        }

        response.setStatusCode(status);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        DataBuffer buffer = response.bufferFactory().wrap(bytes);
        return response.writeWith(Mono.just(buffer));
    }
}
