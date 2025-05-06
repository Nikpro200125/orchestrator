package org.openapitools.config;

import lombok.RequiredArgsConstructor;
import org.openapitools.util.ControlPanelInterceptor;
import org.openapitools.util.RequestLoggingInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
@RequiredArgsConstructor
public class WebConfig implements WebMvcConfigurer {

    private final RequestLoggingInterceptor requestLoggingInterceptor;
    private final ControlPanelInterceptor controlPanelInterceptor;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(requestLoggingInterceptor).order(0);
        registry.addInterceptor(controlPanelInterceptor).order(1);
    }
}