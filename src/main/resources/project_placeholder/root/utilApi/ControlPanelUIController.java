package org.openapitools.utilApi;

import lombok.RequiredArgsConstructor;
import org.openapitools.service.ControlPanelService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.openapitools.util.RequestLoggingInterceptor.EXCLUDED_PATTERNS;


@Controller
@RequiredArgsConstructor
public class ControlPanelUIController {

    private final RequestMappingHandlerMapping handlerMapping;
    private final ControlPanelService controlPanelService;
    private final AntPathMatcher pathMatcher = new AntPathMatcher();

    @GetMapping("/ui")
    public String showControlPanel(Model model) {
        List<String> endpoints = handlerMapping.getHandlerMethods().keySet().stream()
                .flatMap(info -> {
                    if (info.getPathPatternsCondition() != null) {
                        return info.getPathPatternsCondition().getPatternValues().stream();
                    } else if (info.getPatternsCondition() != null) {
                        return info.getPatternsCondition().getPatterns().stream();
                    } else {
                        return Stream.empty();
                    }
                })
                .filter(endpoint -> EXCLUDED_PATTERNS.stream().noneMatch(pattern -> pathMatcher.match(pattern, endpoint)))
                .distinct()
                .sorted()
                .collect(Collectors.toList());

        model.addAttribute("endpoints", endpoints);
        model.addAttribute("configs", controlPanelService.getAllConfigs());
        return "control-ui";
    }

}