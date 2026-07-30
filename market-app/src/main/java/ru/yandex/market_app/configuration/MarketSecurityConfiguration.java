package ru.yandex.market_app.configuration;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.ReactiveAuthenticationManager;
import org.springframework.security.authentication.UserDetailsRepositoryReactiveAuthenticationManager;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.authentication.logout.DelegatingServerLogoutHandler;
import org.springframework.security.web.server.authentication.logout.RedirectServerLogoutSuccessHandler;
import org.springframework.security.web.server.authentication.logout.SecurityContextServerLogoutHandler;
import org.springframework.security.web.server.authentication.logout.WebSessionServerLogoutHandler;
import ru.yandex.market_app.security.DatabaseReactiveUserDetailsService;
import ru.yandex.market_app.security.SessionCookieLogoutHandler;

import java.net.URI;

@Configuration(proxyBeanMethods = false)
@EnableWebFluxSecurity
public class MarketSecurityConfiguration {

    private static final int BCRYPT_STRENGTH = 12;

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(BCRYPT_STRENGTH);
    }

    @Bean
    ReactiveAuthenticationManager reactiveAuthenticationManager(
        DatabaseReactiveUserDetailsService userDetailsService,
        PasswordEncoder passwordEncoder
    ) {
        var manager = new UserDetailsRepositoryReactiveAuthenticationManager(userDetailsService);
        manager.setPasswordEncoder(passwordEncoder);
        return manager;
    }

    @Bean
    SecurityWebFilterChain marketSecurityWebFilterChain(
        ServerHttpSecurity http,
        ReactiveAuthenticationManager authenticationManager,
        SessionCookieLogoutHandler cookieLogoutHandler
    ) {
        var logoutHandler = new DelegatingServerLogoutHandler(
            new SecurityContextServerLogoutHandler(),
            new WebSessionServerLogoutHandler(),
            cookieLogoutHandler
        );
        var logoutSuccessHandler = new RedirectServerLogoutSuccessHandler();
        logoutSuccessHandler.setLogoutSuccessUrl(URI.create("/items"));

        return http
            .authenticationManager(authenticationManager)
            .authorizeExchange(authorize -> authorize
                .pathMatchers("/login", "/error").permitAll()
                .pathMatchers(HttpMethod.GET, "/", "/items", "/items/*", "/images/**").permitAll()
                .pathMatchers(
                    HttpMethod.POST,
                    "/items",
                    "/items/*",
                    "/cart/items",
                    "/buy",
                    "/logout"
                ).authenticated()
                .pathMatchers("/cart/**", "/orders/**").authenticated()
                .anyExchange().denyAll()
            )
            .formLogin(Customizer.withDefaults())
            .logout(logout -> logout
                .logoutUrl("/logout")
                .logoutHandler(logoutHandler)
                .logoutSuccessHandler(logoutSuccessHandler)
            )
            .build();
    }
}
