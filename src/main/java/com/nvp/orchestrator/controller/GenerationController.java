package com.nvp.orchestrator.controller;

import com.nvp.orchestrator.service.GenerationService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;


@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class GenerationController {

    private final GenerationService generationService;

    @PostMapping(value = "/generate-service", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public String generateService(@RequestParam("file") MultipartFile openapiFile) throws Exception {
        return generationService.generate(openapiFile);
    }

}
