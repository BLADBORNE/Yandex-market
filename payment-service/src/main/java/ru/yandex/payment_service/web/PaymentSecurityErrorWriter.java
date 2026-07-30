package ru.yandex.payment_service.web;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.web.server.ServerAuthenticationEntryPoint;
import org.springframework.security.web.server.authorization.ServerAccessDeniedHandler;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import ru.yandex.payment_service.generated.model.ErrorResponse;

@Component
@RequiredArgsConstructor
public final class PaymentSecurityErrorWriter
    implements ServerAuthenticationEntryPoint, ServerAccessDeniedHandler {

    private final ObjectMapper objectMapper;

    @Override
    public Mono<Void> commence(
        ServerWebExchange exchange,
        AuthenticationException exception
    ) {
        String authenticationChallenge = exception instanceof OAuth2AuthenticationException
            ? "Bearer error=\"invalid_token\""
            : "Bearer";
        return write(
            exchange,
            HttpStatus.UNAUTHORIZED,
            "UNAUTHORIZED",
            "Требуется действительный OAuth2-токен",
            authenticationChallenge
        );
    }

    @Override
    public Mono<Void> handle(
        ServerWebExchange exchange,
        org.springframework.security.access.AccessDeniedException exception
    ) {
        return write(
            exchange,
            HttpStatus.FORBIDDEN,
            "FORBIDDEN",
            "OAuth2-токен не предоставляет необходимого разрешения",
            "Bearer error=\"insufficient_scope\""
        );
    }

    private Mono<Void> write(
        ServerWebExchange exchange,
        HttpStatus status,
        String code,
        String message,
        String authenticationChallenge
    ) {
        byte[] body;
        try {
            body = objectMapper.writeValueAsBytes(new ErrorResponse(code, message));
        } catch (JsonProcessingException exception) {
            return Mono.error(exception);
        }

        exchange.getResponse().setStatusCode(status);
        exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
        exchange.getResponse().getHeaders().set(
            HttpHeaders.WWW_AUTHENTICATE,
            authenticationChallenge
        );
        DataBuffer buffer = exchange.getResponse().bufferFactory().wrap(body);
        return exchange.getResponse().writeWith(Mono.just(buffer));
    }
}
