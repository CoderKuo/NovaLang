package com.novalang.runtime.host;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

public final class HostBindingJsonWriter {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private HostBindingJsonWriter() {}

    public static void write(HostBindingRegistry registry, Path path) throws IOException {
        if (registry == null) {
            throw new IllegalArgumentException("registry must not be null");
        }
        if (path == null) {
            throw new IllegalArgumentException("path must not be null");
        }

        Path parent = path.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }

        try (Writer writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
            GSON.toJson(toJsonElement(registry), writer);
        }
    }

    public static String toJson(HostBindingRegistry registry) {
        if (registry == null) {
            throw new IllegalArgumentException("registry must not be null");
        }
        return GSON.toJson(toJsonElement(registry));
    }

    private static JsonObject toJsonElement(HostBindingRegistry registry) {
        JsonObject root = new JsonObject();
        root.addProperty("version", 1);
        root.add("globals", toSymbolsArray(registry.globals()));

        JsonObject namespaces = new JsonObject();
        for (Map.Entry<String, HostNamespaceDescriptor> entry : registry.namespaces().entrySet()) {
            HostNamespaceDescriptor namespace = entry.getValue();
            JsonObject namespaceObj = new JsonObject();

            JsonArray extendsArray = new JsonArray();
            for (String parent : namespace.getExtendsNamespaces()) {
                extendsArray.add(parent);
            }
            if (extendsArray.size() > 0) {
                namespaceObj.add("extends", extendsArray);
            }
            namespaceObj.add("globals", toSymbolsArray(namespace.getGlobals()));
            namespaces.add(entry.getKey(), namespaceObj);
        }
        root.add("namespaces", namespaces);
        return root;
    }

    private static JsonArray toSymbolsArray(Iterable<HostSymbolDescriptor> symbols) {
        JsonArray array = new JsonArray();
        for (HostSymbolDescriptor symbol : symbols) {
            array.add(toSymbolObject(symbol));
        }
        return array;
    }

    private static JsonObject toSymbolObject(HostSymbolDescriptor symbol) {
        JsonObject object = new JsonObject();
        object.addProperty("name", symbol.getName());
        object.addProperty("kind", symbol.getKind().name().toLowerCase());

        if (symbol.getDocumentation() != null) {
            object.addProperty("documentation", symbol.getDocumentation());
        }
        if (symbol.getDeprecatedMessage() != null) {
            object.addProperty("deprecated", symbol.getDeprecatedMessage());
        }
        if (!symbol.getExamples().isEmpty()) {
            JsonArray examples = new JsonArray();
            for (String example : symbol.getExamples()) {
                examples.add(example);
            }
            object.add("examples", examples);
        }

        if (symbol instanceof HostVariableDescriptor) {
            HostVariableDescriptor variable = (HostVariableDescriptor) symbol;
            object.addProperty("type", variable.getType().displayName());
            object.addProperty("mutable", variable.isMutable());
        } else if (symbol instanceof HostPropertyDescriptor) {
            HostPropertyDescriptor property = (HostPropertyDescriptor) symbol;
            object.addProperty("type", property.getType().displayName());
            object.addProperty("mutable", property.isMutable());
        } else if (symbol instanceof HostFunctionDescriptor) {
            HostFunctionDescriptor function = (HostFunctionDescriptor) symbol;
            JsonArray params = new JsonArray();
            for (HostParameterDescriptor parameter : function.getParameters()) {
                JsonObject param = new JsonObject();
                param.addProperty("name", parameter.getName());
                if (parameter.getType() != null) {
                    param.addProperty("type", parameter.getType().displayName());
                }
                if (parameter.isVararg()) {
                    param.addProperty("vararg", true);
                }
                params.add(param);
            }
            object.add("parameters", params);
            object.addProperty("returnType", function.getReturnType().displayName());
        } else if (symbol instanceof HostObjectDescriptor) {
            HostObjectDescriptor hostObject = (HostObjectDescriptor) symbol;
            object.addProperty("type", hostObject.getType().displayName());
            object.add("members", toSymbolsArray(hostObject.getMembers()));
        }

        return object;
    }
}
