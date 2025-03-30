package com.nvp.orchestrator.service.util;

import com.nvp.orchestrator.enums.ParameterType;
import com.nvp.orchestrator.logs.OutputRedirect;
import com.nvp.orchestrator.enums.MethodAnnotation;
import com.nvp.orchestrator.exceptions.LibSLParsingException;
import io.swagger.v3.oas.models.*;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.*;
import io.swagger.v3.oas.models.parameters.Parameter;
import io.swagger.v3.oas.models.parameters.RequestBody;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.research.libsl.LibSL;
import org.jetbrains.research.libsl.context.LslGlobalContext;
import org.jetbrains.research.libsl.nodes.AnnotationUsage;
import org.jetbrains.research.libsl.nodes.Automaton;
import org.jetbrains.research.libsl.nodes.Function;
import org.jetbrains.research.libsl.nodes.FunctionArgument;
import org.jetbrains.research.libsl.nodes.Library;
import org.jetbrains.research.libsl.nodes.references.TypeReference;
import org.jetbrains.research.libsl.type.*;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

@Service
@Slf4j
@RequiredArgsConstructor
public final class LibSLParserServiceImpl {

    private final OutputRedirect outputRedirect;

    private static final String PATH = "path";
    private static final String PATH_DELIMITER = "/";
    private static final String APPLICATION_JSON = "application/json";

    public Library parseLibSL(Path filePath) {
        Library library = new LibSL("", new LslGlobalContext("")).loadByPath(filePath);

        List<String> capturedOutput = outputRedirect.getCapturingErr().getCapturedOutput();
        if (!capturedOutput.isEmpty()) {
            throw new LibSLParsingException("Failed to parse LibSL file:\n" + capturedOutput);
        }

        log.debug("Library: {}", library);
        return library;
    }

    public static OpenAPI generateOpenAPI(Library library) {
        OpenAPI openAPI = new OpenAPI().info(new Info().title("Generated API").version("1.0.0"));

        Paths paths = new Paths();
        Components components = new Components();

        library.getAutomata().forEach(automaton -> {
            automaton.getFunctions().forEach(function -> {
                PathItem pathItem = generatePathItem(function, components);
                if (pathItem != null) {
                    paths.addPathItem(generatePathName(automaton, function, pathItem), pathItem);
                }
            });
        });

        library.getResolvedTypes().stream().filter(type -> type instanceof StructuredType)
                .filter(type -> components.getSchemas().get(type.getName()) == null)
                .forEach(type -> {
                    Schema<?> schema = generateArgumentSchema(Objects.requireNonNull(type));
                    components.addSchemas(type.getName(), schema);
                });

        openAPI.paths(paths);
        openAPI.components(components);
        return openAPI;
    }

    private static String generatePathName(Automaton automaton, Function function, PathItem pathItem) {
        AtomicReference<String> pathName = new AtomicReference<>(PATH_DELIMITER + automaton.getName() + PATH_DELIMITER + function.getName());
        Stream.of(pathItem.getGet(), pathItem.getPost(), pathItem.getPut(), pathItem.getDelete())
                .filter(Objects::nonNull)
                .forEach(operation -> {
                    if (operation.getParameters() != null) {
                        operation.getParameters().forEach(parameter -> {
                            // add path parameter to path name
                            if (parameter.getIn().equals(PATH)) {
                                pathName.set(pathName + PATH_DELIMITER + "{" + parameter.getName() + "}");
                            }
                        });
                    }
                });
        return pathName.get();
    }

    private static PathItem generatePathItem(Function function, Components components) {
        AnnotationUsage methodAnnotation = function.getAnnotationUsages()
                .stream()
                .filter(a -> MethodAnnotation.isMethodAnnotation(a.getAnnotationReference().getName()))
                .findFirst()
                .orElse(null);

        if (methodAnnotation == null) {
            return null;
        }


        int numberOfRequestBodies = getNumberOfRequestBodies(function);

        if (numberOfRequestBodies > 1) {
            throw new LibSLParsingException("Multiple request bodies are not supported");
        }

        Operation operation = new Operation();
        function.getArgs().forEach(arg -> {
            if (isArgumentWithAnnotation(arg, ParameterType.REQUEST_BODE)) {
                operation.requestBody(generateRequestBody(arg, components));
                operation.addExtension("x-codegen-request-body-name", arg.getName());
            } else if (isArgumentWithAnnotation(arg, ParameterType.PATH)) {
                operation.addParametersItem(generatePathParameter(arg, components));
            } else if (isArgumentWithAnnotation(arg, ParameterType.QUERY)) {
                operation.addParametersItem(generateQueryParameter(arg, components));
            }
        });

        generateResponses(function, operation, components);

        PathItem pathItem = new PathItem();
        switch (MethodAnnotation.valueOf(methodAnnotation.getAnnotationReference().getName())) {
            case GET -> pathItem.get(operation);
            case POST -> pathItem.post(operation);
            case PUT -> pathItem.put(operation);
            case DELETE -> pathItem.delete(operation);
            case PATCH -> pathItem.patch(operation);
        }

        return pathItem;
    }

    private static void generateResponses(Function function, Operation operation, Components components) {
        ApiResponse response = new ApiResponse();
        response.description("Successful operation");

        if (function.getReturnType() != null) {
            addResponse(function, response, components);
        }

        operation.responses(new ApiResponses().addApiResponse("200", response));
    }

    private static void addResponse(Function function, ApiResponse response, Components components) {
        Content content = generateContentByTypeReference(Objects.requireNonNull(function.getReturnType()), components);

        response.content(content);
    }

    private static RequestBody generateRequestBody(FunctionArgument argument, Components components) {
        Content content = generateContentByTypeReference(Objects.requireNonNull(argument.getTypeReference()), components);

        RequestBody requestBody = new RequestBody();
        requestBody.content(content);

        return requestBody;
    }

    private static Content generateContentByTypeReference(TypeReference typeReference, Components components) {
        String parameterName = typeReference.getName();
        components.addSchemas(parameterName, generateArgumentSchema(Objects.requireNonNull(typeReference.resolve())));

        MediaType mediaType = new MediaType();
        mediaType.schema(new Schema<>().$ref("#/components/schemas/" + parameterName));

        Content content = new Content();
        content.addMediaType(APPLICATION_JSON, mediaType);

        return content;
    }

    private static Parameter generatePathParameter(FunctionArgument argument, Components components) {
        String parameterName = argument.getTypeReference().getName();
        components.addSchemas(parameterName, generateArgumentSchema(Objects.requireNonNull(argument.getTypeReference().resolve())));
        return new Parameter()
                .name(argument.getName())
                .in(PATH)
                .required(true)
                .schema(new Schema<>().$ref("#/components/schemas/" + parameterName));
    }

    private static Parameter generateQueryParameter(FunctionArgument argument, Components components) {
        String parameterName = argument.getTypeReference().getName();
        components.addSchemas(parameterName, generateArgumentSchema(Objects.requireNonNull(argument.getTypeReference().resolve())));
        return new Parameter()
                .name(argument.getName())
                .in("query")
                .schema(new Schema<>().$ref("#/components/schemas/" + parameterName));
    }

    private static int getNumberOfRequestBodies(Function function) {
        return (int) function.getArgs()
                .stream()
                .filter(arg ->
                        arg.getAnnotationUsages()
                                .stream()
                                .anyMatch(a -> a.getAnnotationReference().getName().equals(ParameterType.REQUEST_BODE.getValue())))
                .count();
    }

    private static boolean isArgumentWithAnnotation(FunctionArgument argument, ParameterType parameterType) {
        return argument.getAnnotationUsages()
                .stream()
                .anyMatch(a -> a.getAnnotationReference().getName().equals(parameterType.getValue()));
    }

    private static Schema<?> generateArgumentSchema(Type argumentType) {
        try {
            switch (argumentType) {
                case StructuredType structuredType -> {
                    ObjectSchema objectSchema = new ObjectSchema();
                    structuredType.getVariables().forEach(variable -> {
                        Schema<?> schema = generateArgumentSchema(Objects.requireNonNull(variable.getTypeReference().resolve()));
                        objectSchema.addProperty(variable.getName(), schema);
                    });
                    objectSchema.types(null);
                    return objectSchema;
                }
                case ArrayType arrayType -> {
                    Schema<?> schema = generateArgumentSchema(Objects.requireNonNull(arrayType.getGenerics().getFirst().resolve()));
                    schema.types(null);
                    ArraySchema arraySchema = new ArraySchema();
                    arraySchema.items(schema);
                    arraySchema.types(null);
                    return arraySchema;
                }
                case PrimitiveType primitiveType -> {
                    Schema<?> schema = resolveTypeByStringName(primitiveType.getName());
                    schema.types(null);
                    return schema;
                }
                case SimpleType simpleType -> {
                    Schema<?> schema = resolveTypeByStringName(simpleType.getRealType().getFullName());

                    schema.types(null);
                    return schema;
                }
                case RealType realType -> {
                    Schema<?> schema = resolveTypeByStringName(realType.getFullName());

                    schema.types(null);
                    return schema;
                }
                case MapType mapType -> {
                    if (mapType.getGenerics().size() != 2) {
                        throw new LibSLParsingException("Map type should have 2 generics");
                    }

                    Schema<?> valueSchema = generateArgumentSchema(Objects.requireNonNull(mapType.getGenerics().getLast().resolve()));
                    valueSchema.types(null);

                    Schema<?> schema = new MapSchema();
                    schema.additionalProperties(valueSchema);

                    schema.types(null);
                    return schema;
                }
                case TypeAlias typeAlias -> {
                    return generateArgumentSchema(Objects.requireNonNull(typeAlias.getOriginalType().resolve()));
                }
                default -> {
                }
            }
        } catch (Exception e) {
            throw new LibSLParsingException("Failed to generate schema for argument type: " + argumentType);
        }

        throw new LibSLParsingException("Unexpected value: " + argumentType);
    }

    private static Schema<?> resolveTypeByStringName(String typeName) {
        return switch (typeName) {
            case "int", "int8", "int16", "int32" -> new IntegerSchema().format("int32");
            case "long" -> new IntegerSchema().format("int64");
            case "float32" -> new NumberSchema().format("float");
            case "float64" -> new NumberSchema().format("double");
            case "boolean", "Boolean" -> new BooleanSchema();
            case "string" -> new StringSchema();
            case "java.time.LocalDate" -> new DateSchema();
            case "java.time.LocalDateTime" -> new DateTimeSchema();
            default -> throw new LibSLParsingException("Unexpected value: " + typeName);
        };
    }
}
