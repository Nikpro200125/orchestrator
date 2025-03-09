package org.openapitools.utilApi;

import lombok.RequiredArgsConstructor;
import org.openapitools.model.EndpointOverrideConfig;
import org.openapitools.service.ControlPanelService;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/control")
@RequiredArgsConstructor
public class ControlPanelController {

    private final ControlPanelService controlPanelService;

    @GetMapping("/configs")
    public Map<String, EndpointOverrideConfig> getAllConfigs() {
        return controlPanelService.getAllConfigs();
    }

    @PostMapping("/configs")
    public void setConfig(
            @RequestParam String endpoint,
            @RequestParam(required = false) Long delayMs,
            @RequestParam(required = false) Integer httpCodeOverride) {
        EndpointOverrideConfig config = new EndpointOverrideConfig();
        config.setDelayMs(delayMs);
        config.setHttpCodeOverride(httpCodeOverride);
        controlPanelService.setConfig(endpoint, config);
    }

    @DeleteMapping("/configs")
    public void resetConfig(@RequestParam String endpoint) {
        controlPanelService.resetConfig(endpoint);
    }

    @PostMapping("/configs/resetAll")
    public void resetAllConfigs() {
        controlPanelService.resetAll();
    }
}