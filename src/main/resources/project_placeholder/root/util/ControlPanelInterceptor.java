package org.openapitools.util;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.openapitools.model.EndpointOverrideConfig;
import org.openapitools.service.ControlPanelService;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;

@Component
@Slf4j
@RequiredArgsConstructor
public class ControlPanelInterceptor implements HandlerInterceptor {

    private final ControlPanelService controlPanelService;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {

        String endpointTemplate = (String) request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        if (endpointTemplate == null) {
            endpointTemplate = request.getRequestURI();
        }

        EndpointOverrideConfig config = controlPanelService.getConfig(endpointTemplate);
        if (config != null) {

            if (config.getDelayMs() != null && config.getDelayMs() > 0) {
                log.info("Delay {} ms for endpoint {}", config.getDelayMs(), endpointTemplate);
                Thread.sleep(config.getDelayMs());
            }

            if (config.getHttpCodeOverride() != null) {
                log.info("Override response code {} for endpoint {}", config.getHttpCodeOverride(), endpointTemplate);
                response.setStatus(config.getHttpCodeOverride());
                return false;
            }
        }
        return true;
    }
}
