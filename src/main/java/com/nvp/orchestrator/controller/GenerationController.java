package com.nvp.orchestrator.controller;

import com.nvp.orchestrator.service.GeneratorService;
import com.nvp.orchestrator.service.impl.LibSLParserServiceImpl;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;


@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class GenerationController {

    private final GeneratorService generatorService;

    @PostMapping(value = "/generate-service", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public String generateService(@RequestParam("file") MultipartFile openapiFile) throws Exception {
        return generatorService.generate(openapiFile);
    }

    @PostMapping(value = "/generate-service-libsl", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public String parseSignatures(@RequestParam("file") MultipartFile libSLFile) throws Exception{
        return generatorService.generateLibSL(libSLFile);
    }

}
