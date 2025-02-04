package com.nvp.orchestrator.controller;

import com.nvp.orchestrator.service.generator.ContractsServiceGenerator;
import com.nvp.orchestrator.service.generator.RandomServiceGenerator;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class GenerationController {

    private final RandomServiceGenerator generatorService;
    private final ContractsServiceGenerator contractsServiceGenerator;

    @PostMapping(value = "/generate-service", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public String generateService(@RequestParam("file") MultipartFile openapiFile) {
        return generatorService.generateImplementation(openapiFile);
    }

    @PostMapping(value = "/generate-service-libsl", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public String parseSignatures(@RequestParam("file") MultipartFile libSLFile) {
        return contractsServiceGenerator.generateImplementation(libSLFile);
    }

}
