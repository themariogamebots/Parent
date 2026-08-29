package org.themarioga.telegram.sh.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/**
 * La sesión de los usuarios de Telegram no viaja por HTTP: la monta el interceptor de updates en el
 * contexto de seguridad. Lo único que hay que abrir aquí es el endpoint del webhook y el reto de
 * Let's Encrypt.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity httpSecurity) throws Exception {
        return httpSecurity.csrf(AbstractHttpConfigurer::disable).sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS)).authorizeHttpRequests(authorize -> authorize.requestMatchers(HttpMethod.POST, "/callback/**").permitAll().requestMatchers("/.well-known/acme-challenge/**").permitAll().anyRequest().authenticated()).build();
    }

}
