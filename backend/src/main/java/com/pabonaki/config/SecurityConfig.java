package com.pabonaki.config;

import com.pabonaki.security.JwtFilter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import java.util.List;

@Configuration
public class SecurityConfig {
    @Value("${app.frontend-origin}") String origin;
    @Bean SecurityFilterChain chain(HttpSecurity h, JwtFilter f) throws Exception {
        h.csrf(c->c.disable()).cors(Customizer.withDefaults()).sessionManagement(s->s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
          .authorizeHttpRequests(a->a.requestMatchers("/","/index.html","/admin.html","/customer.html","/advanced.html","/css/**","/js/**","/media/**","/img/**","/error","/favicon.ico","/robots.txt").permitAll()
            .requestMatchers(HttpMethod.OPTIONS,"/**").permitAll()
            .requestMatchers(HttpMethod.GET,"/api/v1/recommendations/for-you").authenticated()
            .requestMatchers(HttpMethod.GET,"/api/v1/search/capabilities","/api/v1/search/semantic","/api/v1/recommendations/discover","/api/v1/recommendations/trending","/api/v1/recommendations/because-you-viewed","/api/v1/recommendations/similar/*","/api/v1/recommendations/frequently-bought-together/*").permitAll()
            .requestMatchers(HttpMethod.POST,"/api/v1/search/visual","/api/v1/activity").permitAll()
            .requestMatchers(HttpMethod.POST,"/api/v1/tryon/generate").permitAll()
            .requestMatchers("/api/v1/payments/sslcommerz/success","/api/v1/payments/sslcommerz/fail","/api/v1/payments/sslcommerz/cancel","/api/v1/payments/sslcommerz/ipn").permitAll()
            .requestMatchers(HttpMethod.POST,"/api/v1/auth/register","/api/v1/auth/register/request-otp","/api/v1/auth/register/verify-otp","/api/v1/auth/login","/api/v1/auth/google","/api/v1/auth/forgot-password/request-otp","/api/v1/auth/forgot-password/verify-otp","/api/v1/auth/forgot-password/reset","/api/v1/setup/initialize","/api/v1/ai/ask").permitAll()
            .requestMatchers(HttpMethod.GET,"/api/v1/products/**","/api/v1/categories","/api/v1/health","/api/v1/config/public","/api/v1/store/settings").permitAll()
            .requestMatchers("/api/v1/admin/**").hasRole("ADMIN")
            .requestMatchers("/api/v1/orders/**","/api/v1/payments/**").authenticated().anyRequest().authenticated())
          .exceptionHandling(e->e.authenticationEntryPoint((req,res,ex)->{res.setStatus(401);res.setContentType("application/json;charset=UTF-8");res.getWriter().write("{\"message\":\"Please log in to continue.\"}");}).accessDeniedHandler((req,res,ex)->{res.setStatus(403);res.setContentType("application/json;charset=UTF-8");res.getWriter().write("{\"message\":\"You do not have permission to do that.\"}");}))
          .addFilterBefore(f,UsernamePasswordAuthenticationFilter.class); return h.build();
    }
    @Bean PasswordEncoder encoder(){return new BCryptPasswordEncoder();}
    @Bean CorsConfigurationSource cors(){CorsConfiguration c=new CorsConfiguration();c.setAllowedOrigins(List.of(origin,"http://localhost:8080","http://127.0.0.1:8080"));c.setAllowedMethods(List.of("GET","POST","PUT","PATCH","DELETE","OPTIONS"));c.setAllowedHeaders(List.of("Authorization","Content-Type"));UrlBasedCorsConfigurationSource s=new UrlBasedCorsConfigurationSource();s.registerCorsConfiguration("/**",c);return s;}
}
