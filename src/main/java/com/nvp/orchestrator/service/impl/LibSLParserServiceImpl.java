package com.nvp.orchestrator.service.impl;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.Paths;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.*;
import io.swagger.v3.oas.models.parameters.Parameter;
import io.swagger.v3.oas.models.parameters.RequestBody;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.research.libsl.LibSL;
import org.jetbrains.research.libsl.context.LslGlobalContext;
import org.jetbrains.research.libsl.nodes.AnnotationUsage;
import org.jetbrains.research.libsl.nodes.Automaton;
import org.jetbrains.research.libsl.nodes.Function;
import org.jetbrains.research.libsl.nodes.Library;
import org.jetbrains.research.libsl.type.*;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

@Service
@Slf4j
public class LibSLParserServiceImpl {

    private static final String IN_PATH = "InPath";
    private static final String PATH = "path";
    private static final String IN_BODY = "InBody";

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
                    paths.addPathItem(generatePathName(automaton, function, pathItem), pathItem);
                }
            });
        });

        openAPI.paths(paths);
        return openAPI;
    }

    private String generatePathName(Automaton automaton, Function function, PathItem pathItem) {
        AtomicReference<String> pathName = new AtomicReference<>("/" + automaton.getName() + "/" + function.getName());
        Stream.of(pathItem.getGet(), pathItem.getPost(), pathItem.getPut(), pathItem.getDelete())
                .filter(Objects::nonNull)
                .forEach(operation -> {
                    if (operation.getParameters() != null) {
                        operation.getParameters().forEach(parameter -> {
                            // add path parameter to path name
                            if (parameter.getIn().equals(PATH)) {
                                pathName.set(pathName + "/{" + parameter.getName() + "}");
                            }
                        });
                    }
                });
        return pathName.get();
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


        int numberOfRequestBodies = (int) function.getArgs().stream().filter(arg -> arg.getAnnotationUsages().stream().anyMatch(a -> a.getAnnotationReference().getName().equals(IN_BODY))).count();

        if (numberOfRequestBodies > 1) {
            throw new IllegalStateException("Multiple request bodies are not supported");
        }

        function.getArgs().forEach(arg -> {
            // add path parameter with @InPath annotation
            if (arg.getAnnotationUsages().stream().anyMatch(a -> a.getAnnotationReference().getName().equals(IN_PATH))) {
                Parameter p = new Parameter()
                        .name(arg.getName())
                        .in(PATH)
                        .required(true)
                        .schema(generateArgumentSchema(Objects.requireNonNull(arg.getTypeReference().resolve())));
                operation.addParametersItem(p);
            } else if (arg.getAnnotationUsages().stream().anyMatch(a -> a.getAnnotationReference().getName().equals(IN_BODY))) {
                // add request body with @InBody annotation
                operation.requestBody(new RequestBody().content(new Content().addMediaType("application/json", new MediaType().schema(generateArgumentSchema(Objects.requireNonNull(arg.getTypeReference().resolve()))))));
            }
        });

        ApiResponse response = new ApiResponse();
        response.description("Successful operation");

        if (function.getReturnType() != null) {
            response.content(new Content().addMediaType("application/json", new MediaType().schema(generateArgumentSchema(Objects.requireNonNull(Objects.requireNonNull(function.getReturnType()).resolve())))));
            response.description("Successful operation");
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
                    if (schema != null) {
                        schema.types(null);
                    }
                    objectSchema.addProperty(variable.getName(), schema);
                });
                objectSchema.types(null);
                return objectSchema;
            }
        } else if (argumentType.isArray()) {
            if (argumentType instanceof ArrayType arrayType) {
                Schema<?> schema = generateArgumentSchema(Objects.requireNonNull(arrayType.getGenerics().getFirst().resolve()));
                if (schema != null) {
                    schema.types(null);
                }
                return new ArraySchema().items(schema);
            }
        } else {
            switch (argumentType) {
                case SimpleType simpleType -> {
                    Schema<?> schema = resolveTypeByStringName(simpleType.getRealType().getFullName());

                    schema.types(null);
                    return schema;
                }
                case RealType realType -> {
                    Schema<?> schema = resolveTypeByStringName(realType.getFullName());

                    // way around to remove types from schema - idk
                    schema.types(null);
                    return schema;
                }
                case MapType mapType -> {
                    if (mapType.getGenerics().size() != 2) {
                        throw new IllegalStateException("Map type should have 2 generics");
                    }

                    Schema<?> schema = new MapSchema();
                    Schema<?> keySchema = generateArgumentSchema(Objects.requireNonNull(mapType.getGenerics().getFirst().resolve()));
                    keySchema.types(null);
                    Schema<?> valueSchema = generateArgumentSchema(Objects.requireNonNull(mapType.getGenerics().getLast().resolve()));
                    valueSchema.types(null);

                    schema.additionalProperties(keySchema);
                    schema.additionalProperties(valueSchema);

                    schema.types(null);
                    return schema;
                }
                default -> {
                }
            }
        }

        throw new IllegalStateException("Unexpected value: " + argumentType);
    }

    private static Schema<?> resolveTypeByStringName(String typeName) {
        return switch (typeName) {
            case "int", "int8", "int16", "int32" -> new IntegerSchema().format("int32");
            case "long" -> new IntegerSchema().format("int64");
            case "float" -> new NumberSchema().format("float");
            case "double" -> new NumberSchema().format("double");
            case "boolean", "Boolean" -> new BooleanSchema();
            case "string" -> new StringSchema();
            default -> throw new IllegalStateException("Unexpected value: " + typeName);
        };
    }
}
