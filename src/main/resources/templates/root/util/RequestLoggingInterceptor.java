package org.openapitools.util;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.openapitools.service.RequestCounterService;
import org.openapitools.utilApi.StatsController;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;

import java.util.List;

@Component
@RequiredArgsConstructor
@Slf4j
public class RequestLoggingInterceptor implements HandlerInterceptor {

    private static final List<String> EXCLUDED_PATTERNS = List.of(
            "/swagger-ui*/**",
            "/v3/api-docs*/**"
    );

    private final RequestCounterService counterService;
    private final AntPathMatcher pathMatcher = new AntPathMatcher();

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        if (handler instanceof HandlerMethod handlerMethod) {
            // Исключаем запросы к контроллеру статистики
            if (handlerMethod.getBean() instanceof StatsController) {
                log.info("Incoming request: {} {}", request.getMethod(), request.getRequestURI());
                return true;
            }
        }

        String pathTemplate = (String) request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        if (pathTemplate == null) {
            pathTemplate = request.getRequestURI();
        }

        for (String pattern : EXCLUDED_PATTERNS) {
            if (pathMatcher.match(pattern, pathTemplate)) {
                log.info("Incoming request: {} {}", request.getMethod(), request.getRequestURI());
                return true;
            }
        }

        log.info("Incoming request: {} {}", request.getMethod(), pathTemplate);
        counterService.increment(pathTemplate);
        return true;
    }
}