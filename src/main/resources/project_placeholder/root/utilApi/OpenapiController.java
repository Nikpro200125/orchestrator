package org.openapitools.utilApi;

import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class OpenapiController {

    @GetMapping("/openapi")
    public Resource showOpenApiSpec() {
        Resource resource = new ClassPathResource("static/openapi.yaml");

        if (!resource.exists()) {
            throw new RuntimeException("File not found");
        }

        return resource;
    }

}
