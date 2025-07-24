package com.aipaas.anycloud.configuration.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

@Slf4j
@RequiredArgsConstructor
@Component
public class RequestLoggingFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {

        logRequestDetails(request);
        filterChain.doFilter(request, response);
    }

    private void logRequestDetails(HttpServletRequest request) {
        log.info("RestAPI Call Log\n---\nTimestamp: {}\nIP: {}\nMethod: {}\nRequestURI: {}\n---",
                LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss")),
                request.getRemoteAddr(),
                request.getMethod(),
                request.getRequestURI());
    }
}