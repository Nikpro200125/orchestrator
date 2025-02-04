package com.nvp.orchestrator.service.generator;

import com.nvp.orchestrator.exceptions.GenerationServiceException;
import com.nvp.orchestrator.service.implementation.generator.RandomApiImplementationGenerator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.nio.file.Path;

@Slf4j
@Service
public final class RandomServiceGenerator extends ServiceGenerator {

    @Override
    public String generateImplementation(MultipartFile file) {
        validateFile(file);

        Path workingDirectory = generateWorkingDirectory();

        Path openapiSpec = saveFileToWorkingDir(workingDirectory, OPENAPI_SPEC_FILE_NAME, file);

        generateServiceFromOpenApi(workingDirectory, openapiSpec);

        generateApi(workingDirectory);

        return buildAndDeployService(workingDirectory);
    }

    private static void generateApi(Path tempDir) {
        RandomApiImplementationGenerator generator = new RandomApiImplementationGenerator(tempDir);
        try {
            generator.generate();
        } catch (Exception e) {
            log.error("Failed to generate API implementations", e);
            throw new GenerationServiceException("Failed to generate API implementations");
        }
    }

}
