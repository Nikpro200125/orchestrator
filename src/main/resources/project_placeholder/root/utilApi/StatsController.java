package org.openapitools.utilApi;

import lombok.RequiredArgsConstructor;
import org.openapitools.service.RequestCounterService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequiredArgsConstructor
public class StatsController {
    private final RequestCounterService counterService;

    @GetMapping("/stats")
    public Map<String, Long> getStats() {
        return counterService.getCounts();
    }

    @PostMapping("/stats/reset")
    public void resetStats() {
        counterService.reset();
    }
}