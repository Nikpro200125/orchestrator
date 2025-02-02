package com.nvp.orchestrator.service.impl;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.nvp.orchestrator.service.GeneratorService;
import io.swagger.v3.oas.models.OpenAPI;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.research.libsl.nodes.Library;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

@Service
@Slf4j
@RequiredArgsConstructor
public class GeneratorServiceImpl implements GeneratorService {

    private final LibSLParserServiceImpl libSLParserService;
    private static final String RESOURCE_ROOT_DIR = "src/main/resources/templates/root";
    private static final String PROJECT_ROOT_DIR = "src/main/java/org/openapitools";

    private Path generateWorkingDirectory() {
        try {
            return Files.createTempDirectory("generated-service-" + System.currentTimeMillis());
        } catch (Exception e) {
            log.error("Failed to create working directory", e);
            throw new RuntimeException("Failed to create working directory", e);
        }
    }

    private void copyFilesToProjectRoot(Path tempDir) throws IOException {
        Files.walk(Path.of(RESOURCE_ROOT_DIR))
                .filter(Files::isRegularFile)
                .forEach(file -> {
                    try {
                        Path relativePath = Path.of(RESOURCE_ROOT_DIR).relativize(file);
                        Path targetPath = tempDir.resolve(PROJECT_ROOT_DIR).resolve(relativePath);
                        Files.createDirectories(targetPath.getParent());
                        Files.copy(file, targetPath, StandardCopyOption.REPLACE_EXISTING);
                        log.info("File copied to {}", targetPath);
                    } catch (IOException e) {
                        log.error("Failed to copy file", e);
                    }
                });
    }

    private void updatePomXML(Path tempDir) throws IOException {
        Path existingPomXMLPath = Path.of("src/main/resources/templates/pom.xml");
        Path pomXMLPath = tempDir.resolve("pom.xml");
        Files.copy(existingPomXMLPath, pomXMLPath, StandardCopyOption.REPLACE_EXISTING);
        log.info("pom.xml copied to {}", pomXMLPath);
    }

    private void copyDockerfile(Path tempDir) throws IOException {
        Path existingDockerfile = Path.of("src/main/resources/templates/Dockerfile");
        Path dockerfilePath = tempDir.resolve("Dockerfile");
        Files.copy(existingDockerfile, dockerfilePath, StandardCopyOption.REPLACE_EXISTING);
        log.info("Dockerfile copied to {}", dockerfilePath);
    }

    @Override
    public String generate(MultipartFile openapiFile) throws Exception {
        if (openapiFile.isEmpty()) {
            throw new IllegalArgumentException("OpenAPI spec file is empty");
        }

        Path tempDir = generateWorkingDirectory();
        Path openapiSpecPath = tempDir.resolve("openapi.yaml");
        Files.copy(openapiFile.getInputStream(), openapiSpecPath, StandardCopyOption.REPLACE_EXISTING);

        return generateServiceFromOpenApi(tempDir, openapiSpecPath, null);
    }

    @NotNull
    private String generateServiceFromOpenApi(Path tempDir, Path openapiSpecPath, Library library) throws IOException, InterruptedException {
        try {
            OpenApiGenerator.generateSpringService(tempDir, openapiSpecPath);
        } catch (Exception e) {
            throw new RuntimeException("Failed to generate service", e);
        }

        copyFilesToProjectRoot(tempDir);
        updatePomXML(tempDir);
        MavenTools.compileGenerated(tempDir);

        generateApi(tempDir, library);

        MavenTools.generateJar(tempDir);
        Thread.sleep(1000); // Ждём, пока Maven закончит работу

        copyDockerfile(tempDir);

        String name = DockerTools.build(tempDir);
        DockerTools.start(name);
        return DockerTools.getUrl(name);
    }

    private static void generateApi(Path tempDir, Library library) {
        ApiImplementationGenerator apiImplementationGenerator = new ApiImplementationGenerator(tempDir);
        try {
            apiImplementationGenerator.generate(library);
        } catch (Exception e) {
            throw new RuntimeException("Failed to generate API implementations", e);
        }
    }

    @Override
    public String generateLibSL(MultipartFile libSLFile) throws IOException, InterruptedException {
        if (libSLFile.isEmpty()) {
            throw new IllegalArgumentException("LibSL spec file is empty");
        }

        Path tempDir = generateWorkingDirectory();
        Path openApiSpecPath;
        Library lib;

        Path file = tempDir.resolve("libsl_" + System.currentTimeMillis() + ".lsl");
        Files.copy(libSLFile.getInputStream(), file, StandardCopyOption.REPLACE_EXISTING);
        lib = libSLParserService.parseLibSL(file);
        OpenAPI openApiSpec = libSLParserService.generateOpenAPI(lib);
        ObjectMapper mapper = new ObjectMapper(new YAMLFactory());

        mapper.setSerializationInclusion(JsonInclude.Include.NON_NULL);
        mapper.setSerializationInclusion(JsonInclude.Include.NON_DEFAULT);

        openApiSpecPath = tempDir.resolve("openApiSpec_" + System.currentTimeMillis() + ".yaml");
        File oasFile = openApiSpecPath.toFile();
        mapper.writeValue(oasFile, openApiSpec);
        log.info("OpenAPI spec generated successfully, saved to file: {}", oasFile.getPath());

        return generateServiceFromOpenApi(tempDir, openApiSpecPath, lib);
    }
}
