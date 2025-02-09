package com.nvp.orchestrator.service.generator;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nvp.orchestrator.exceptions.GenerationServiceException;
import com.nvp.orchestrator.service.implementation.generator.ContractsApiImplementationGenerator;
import com.nvp.orchestrator.service.util.LibSLParserServiceImpl;
import io.swagger.v3.oas.models.OpenAPI;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.research.libsl.nodes.Library;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.nio.file.Path;

@Service
@Slf4j
@RequiredArgsConstructor
public final class ContractsServiceGenerator extends ServiceGenerator {

    private static final String LIB_SL_FILE_NAME = "libsl.lsl";

    private final LibSLParserServiceImpl libSLParserService;
    private final ObjectMapper mapper;

    @Override
    public String generateImplementation(MultipartFile libSLFile) {
        validateFile(libSLFile);

        Path workingDirectory = generateWorkingDirectory();

        Path libSLFileSaved = saveFileToWorkingDir(workingDirectory, LIB_SL_FILE_NAME, libSLFile);

        Library lib = libSLParserService.parseLibSL(libSLFileSaved);

        Path openApiSpecPath = generateOpenApiSpec(workingDirectory, lib);

        generateServiceFromOpenApi(workingDirectory, openApiSpecPath);

        generateApi(workingDirectory, lib);

        return buildAndDeployService(workingDirectory);
    }

    private Path generateOpenApiSpec(Path workingDir, Library lib) {
        OpenAPI openApiSpec = libSLParserService.generateOpenAPI(lib);

        Path openApiSpecPath = workingDir.resolve(OPENAPI_SPEC_FILE_NAME);

        try {
            mapper.writeValue(openApiSpecPath.toFile(), openApiSpec);
        } catch (Exception e) {
            log.error("Failed to write OpenAPI spec to file", e);
            throw new GenerationServiceException("Failed to write OpenAPI spec to file");
        }

        log.info("OpenAPI spec generated successfully, saved to file: {}", openApiSpecPath);
        return openApiSpecPath;
    }

    private void generateApi(Path tempDir, Library library) {
        try (ContractsApiImplementationGenerator generator = new ContractsApiImplementationGenerator(tempDir, library)) {
            generator.generate();
        } catch (Exception e) {
            log.error("Failed to generate API implementations", e);
            throw new GenerationServiceException("Failed to generate API implementations");
        }
    }
}
