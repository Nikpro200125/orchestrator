package com.nvp.orchestrator.service;

import org.springframework.web.multipart.MultipartFile;

public interface GeneratorService {

    String generate(MultipartFile openapiFile) throws Exception;

    String generateLibSL(MultipartFile libSLFile) throws Exception;

}
