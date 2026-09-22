package com.quaxt.codingagent.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.quaxt.codingagent.ai.json.Json;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

/** Typed parameters whose declarations supply both JSON schemas and runtime validation. */
public final class ToolParameters {
    public static final class Parameter<T> {
        private final String name;
        private final ObjectNode schema;
        private final boolean required;
        private final T fallback;
        private final Function<JsonNode, T> decoder;

        private Parameter(String name, ObjectNode schema, boolean required, T fallback, Function<JsonNode, T> decoder) {
            this.name = name;
            this.schema = schema;
            this.required = required;
            this.fallback = fallback;
            this.decoder = decoder;
        }

        private T decode(JsonNode value) {
            if (value == null || value.isNull()) {
                if (required) throw new IllegalArgumentException(name + " is required");
                return fallback;
            }
            try {
                return decoder.apply(value);
            } catch (IllegalArgumentException error) {
                throw new IllegalArgumentException(name + ": " + error.getMessage(), error);
            }
        }
    }

    public static final class Arguments {
        private final Map<Parameter<?>, Object> values = new IdentityHashMap<>();

        @SuppressWarnings("unchecked")
        public <T> T get(Parameter<T> parameter) {
            if (!values.containsKey(parameter)) throw new IllegalArgumentException("Parameter is not part of this tool: " + parameter.name);
            return (T) values.get(parameter);
        }
    }

    private final Map<String, Parameter<?>> parameters = new LinkedHashMap<>();
    private final ObjectNode advertisedSchema;

    public ToolParameters(Parameter<?>... parameters) {
        this(null, parameters);
    }

    private ToolParameters(ObjectNode advertisedSchema, Parameter<?>... parameters) {
        for (Parameter<?> parameter : parameters) {
            if (this.parameters.putIfAbsent(parameter.name, parameter) != null) {
                throw new IllegalArgumentException("Duplicate parameter: " + parameter.name);
            }
        }
        this.advertisedSchema = advertisedSchema == null ? null : advertisedSchema.deepCopy();
    }

    /**
     * Returns the same typed parameters with a more precise model-facing schema.
     * Runtime decoding continues to use the parameter declarations above.
     */
    public ToolParameters withSchema(ObjectNode schema) {
        Objects.requireNonNull(schema, "schema");
        if (!schema.path("type").asText().equals("object")) {
            throw new IllegalArgumentException("Tool parameter schema must have type object");
        }
        return new ToolParameters(schema, parameters.values().toArray(Parameter<?>[]::new));
    }

    public ObjectNode schema() {
        if (advertisedSchema != null) return advertisedSchema.deepCopy();
        ObjectNode schema = Json.MAPPER.createObjectNode().put("type", "object").put("additionalProperties", false);
        ObjectNode properties = schema.putObject("properties");
        var required = schema.putArray("required");
        for (Parameter<?> parameter : parameters.values()) {
            properties.set(parameter.name, parameter.schema.deepCopy());
            if (parameter.required) required.add(parameter.name);
        }
        return schema;
    }

    public Arguments parse(ObjectNode input) {
        ObjectNode source = input == null ? Json.MAPPER.createObjectNode() : input;
        var names = source.fieldNames();
        while (names.hasNext()) {
            String name = names.next();
            if (!parameters.containsKey(name)) throw new IllegalArgumentException("Unknown argument: " + name);
        }
        var parsed = new Arguments();
        for (Parameter<?> parameter : parameters.values()) parsed.values.put(parameter, parameter.decode(source.get(parameter.name)));
        return parsed;
    }

    public static Parameter<String> text(String name, String description) {
        return new Parameter<>(name, type("string", description), true, null, ToolParameters::decodeText);
    }

    public static Parameter<String> optionalText(String name, String description, String fallback) {
        return new Parameter<>(name, type("string", description), false, fallback, ToolParameters::decodeText);
    }

    public static Parameter<List<String>> stringList(String name, String description) {
        ObjectNode schema = type("array", description);
        schema.set("items", type("string", "One argument"));
        return new Parameter<>(name, schema, true, null, value -> decodeStringList(value));
    }

    public static Parameter<List<String>> optionalStringList(String name, String description) {
        ObjectNode schema = type("array", description);
        schema.set("items", type("string", "One value"));
        return new Parameter<>(name, schema, false, List.of(), ToolParameters::decodeStringList);
    }

    public static Parameter<Map<String, String>> optionalStringMap(String name, String description) {
        ObjectNode schema = type("object", description);
        schema.set("additionalProperties", type("string", "Environment value"));
        return new Parameter<>(name, schema, false, Map.of(), value -> {
            if (!(value instanceof ObjectNode object)) throw new IllegalArgumentException("must be an object of strings");
            Map<String, String> result = new LinkedHashMap<>();
            object.fields().forEachRemaining(entry -> result.put(entry.getKey(), decodeText(entry.getValue())));
            return Map.copyOf(result);
        });
    }

    private static List<String> decodeStringList(JsonNode value) {
        if (!value.isArray()) throw new IllegalArgumentException("must be an array of strings");
        List<String> result = new ArrayList<>();
        for (JsonNode item : value) result.add(decodeText(item));
        return List.copyOf(result);
    }

    public static Parameter<String> optionalText(String name, String description, String fallback, int maximumLength) {
        if (maximumLength < 0 || fallback != null && fallback.codePointCount(0, fallback.length()) > maximumLength) {
            throw new IllegalArgumentException("Invalid text length/default");
        }
        return new Parameter<>(name, type("string", description).put("maxLength", maximumLength), false, fallback, value -> {
            String text = decodeText(value);
            if (text.codePointCount(0, text.length()) > maximumLength) throw new IllegalArgumentException("must contain at most " + maximumLength + " characters");
            return text;
        });
    }

    public static Parameter<Integer> integer(String name, String description, int minimum, int maximum, int fallback) {
        if (minimum > maximum || fallback < minimum || fallback > maximum) throw new IllegalArgumentException("Invalid integer bounds/default");
        return new Parameter<>(name, type("integer", description).put("minimum", minimum).put("maximum", maximum).put("default", fallback),
                false, fallback, value -> {
                    if (!value.isIntegralNumber() || !value.canConvertToInt() || value.asInt() < minimum || value.asInt() > maximum) {
                        throw new IllegalArgumentException("must be an integer between " + minimum + " and " + maximum);
                    }
                    return value.asInt();
                });
    }

    public static Parameter<Double> optionalPositiveNumber(String name, String description) {
        return new Parameter<>(name, type("number", description).put("exclusiveMinimum", 0), false, null, value -> {
            if (!value.isNumber() || !Double.isFinite(value.asDouble()) || value.asDouble() <= 0) {
                throw new IllegalArgumentException("must be a positive finite number");
            }
            return value.asDouble();
        });
    }

    public static Parameter<Boolean> flag(String name, String description) {
        return new Parameter<>(name, type("boolean", description).put("default", false), false, false, value -> {
            if (!value.isBoolean()) throw new IllegalArgumentException("must be a boolean");
            return value.asBoolean();
        });
    }

    public static Parameter<Boolean> flagWithDefault(String name, String description, boolean fallback) {
        return new Parameter<>(name, type("boolean", description).put("default", fallback), false, fallback, value -> {
            if (!value.isBoolean()) throw new IllegalArgumentException("must be a boolean");
            return value.asBoolean();
        });
    }

    public static <T> Parameter<List<T>> nonEmptyList(String name, String description, ToolParameters itemParameters,
            Function<Arguments, T> decodeItem) {
        ObjectNode schema = type("array", description).put("minItems", 1);
        schema.set("items", itemParameters.schema());
        return new Parameter<>(name, schema, true, null, value -> {
            if (!value.isArray() || value.isEmpty()) throw new IllegalArgumentException("must be a non-empty array");
            List<T> items = new ArrayList<>();
            for (JsonNode item : value) {
                if (!(item instanceof ObjectNode object)) throw new IllegalArgumentException("each item must be an object");
                items.add(decodeItem.apply(itemParameters.parse(object)));
            }
            return List.copyOf(items);
        });
    }

    public static <T> Parameter<List<T>> optionalList(String name, String description, ToolParameters itemParameters,
            Function<Arguments, T> decodeItem) {
        ObjectNode schema = type("array", description);
        schema.set("items", itemParameters.schema());
        return new Parameter<>(name, schema, false, List.of(), value -> {
            if (!value.isArray()) throw new IllegalArgumentException("must be an array");
            List<T> items = new ArrayList<>();
            for (JsonNode item : value) {
                if (!(item instanceof ObjectNode object)) throw new IllegalArgumentException("each item must be an object");
                items.add(decodeItem.apply(itemParameters.parse(object)));
            }
            return List.copyOf(items);
        });
    }

    private static ObjectNode type(String type, String description) {
        return Json.MAPPER.createObjectNode().put("type", type).put("description", description);
    }

    private static String decodeText(JsonNode value) {
        if (!value.isTextual()) throw new IllegalArgumentException("must be a string");
        return value.asText();
    }
}
