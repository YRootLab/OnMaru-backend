package com.yrootlab.onmaru.security.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.CacheControl;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

@Component
public final class PrivateResponseInterceptor implements HandlerInterceptor {

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (handler instanceof HandlerMethod method && isPrivate(method)) {
            response.setHeader("Cache-Control", CacheControl.noStore().getHeaderValue());
            response.setHeader("Pragma", "no-cache");
        }
        return true;
    }

    private boolean isPrivate(HandlerMethod method) {
        return method.hasMethodAnnotation(PrivateResponse.class)
                || method.getBeanType().isAnnotationPresent(PrivateResponse.class);
    }
}
