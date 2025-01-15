package com.nvp.orchestrator.service.impl;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.Paths;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.*;
import io.swagger.v3.oas.models.parameters.Parameter;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.research.libsl.LibSL;
import org.jetbrains.research.libsl.context.LslGlobalContext;
import org.jetbrains.research.libsl.nodes.AnnotationUsage;
import org.jetbrains.research.libsl.nodes.Function;
import org.jetbrains.research.libsl.nodes.FunctionArgument;
import org.jetbrains.research.libsl.nodes.Library;
import org.jetbrains.research.libsl.type.ArrayType;
import org.jetbrains.research.libsl.type.SimpleType;
import org.jetbrains.research.libsl.type.StructuredType;
import org.jetbrains.research.libsl.type.Type;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.Objects;

@Service
@Slf4j
public class LibSLParserServiceImpl {

    private enum MethodAnnotation {
        GET, POST, PUT, DELETE;

        public static boolean isMethodAnnotation(String value) {
            for (MethodAnnotation methodAnnotation : MethodAnnotation.values()) {
                if (methodAnnotation.name().equals(value)) {
                    return true;
                }
            }
            return false;
        }
    }

    public Library parseLibSL(Path filePath) {
        Library library = new LibSL("", new LslGlobalContext("")).loadByPath(filePath);
        log.debug("Library: {}", library);
        return library;
    }

    public OpenAPI generateOpenAPI(Library library) {
        OpenAPI openAPI = new OpenAPI().info(new Info().title("Generated API").version("1.0.0"));

        Paths paths = new Paths();

        library.getAutomata().forEach(automaton -> {
            automaton.getFunctions().forEach(function -> {
                PathItem pathItem = generatePathItem(function);
                if (pathItem != null) {
                    paths.addPathItem("/" + automaton.getName() + "/" + function.getName(), pathItem);
                }
            });
        });

        openAPI.paths(paths);
        return openAPI;
    }

    private PathItem generatePathItem(Function function) {
        AnnotationUsage methodAnnotation = function.getAnnotationUsages()
                .stream()
                .filter(a -> MethodAnnotation.isMethodAnnotation(a.getAnnotationReference().getName()))
                .findFirst()
                .orElse(null);

        if (methodAnnotation == null) {
            return null;
        }

        PathItem pathItem = new PathItem();
        Operation operation = new Operation();
        function.getArgs().forEach(arg -> {
            Parameter p = new Parameter()
                    .name(arg.getName())
                    .in("path")
                    .required(true)
                    .schema(generateArgumentSchema(Objects.requireNonNull(arg.getTypeReference().resolve())));
            operation.addParametersItem(p);
        });

        ApiResponse response = new ApiResponse();

        if (function.getReturnType() == null) {
            response.description("Successful operation");
        } else {
            response.content(new Content().addMediaType("application/json", new MediaType().schema(generateArgumentSchema(Objects.requireNonNull(function.getReturnType()).resolve()))));
        }

        operation.responses(new ApiResponses().addApiResponse("200", response));

        switch (MethodAnnotation.valueOf(methodAnnotation.getAnnotationReference().getName())) {
            case GET -> pathItem.get(operation);
            case POST -> pathItem.post(operation);
            case PUT -> pathItem.put(operation);
            case DELETE -> pathItem.delete(operation);
        }

        return pathItem;
    }

    private Schema<?> generateArgumentSchema(Type argumentType) {

        if (argumentType.isTopLevelType()) {
            ObjectSchema objectSchema = new ObjectSchema();
            if (argumentType instanceof StructuredType structuredType) {
                structuredType.getVariables().forEach(variable -> {
                    Schema<?> schema = generateArgumentSchema(Objects.requireNonNull(variable.getTypeReference().resolve()));
                    objectSchema.addProperty(variable.getName(), schema);
                });
            }
        } else if (argumentType.isArray()) {
            if (argumentType instanceof ArrayType arrayType) {
                Schema<?> schema = generateArgumentSchema(Objects.requireNonNull(arrayType.getGenerics().getFirst().resolve()));
                return new ArraySchema().items(schema);
            }
        } else {
            if (argumentType instanceof SimpleType simpleType) {
                return switch (simpleType.getRealType().getFullName()) {
                    case "int", "int8", "int16", "int32" -> new IntegerSchema();
                    case "long" -> new IntegerSchema().format("int64");
                    case "float" -> new NumberSchema().format("float");
                    case "double" -> new NumberSchema().format("double");
                    case "boolean" -> new BooleanSchema();
                    case "string" -> new StringSchema();
                    default ->
                            throw new IllegalStateException("Unexpected value: " + simpleType.getRealType().getFullName());
                };
            }
        }

        return null;
    }
}
