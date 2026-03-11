package com.novalang.bench;

import java.util.function.IntSupplier;

final class ScriptScenario {

    private final String name;
    private final String description;
    private final String novaSource;
    private final String jsSource;
    private final IntSupplier javaNative;

    ScriptScenario(String name, String description, String novaSource, String jsSource, IntSupplier javaNative) {
        this.name = name;
        this.description = description;
        this.novaSource = novaSource;
        this.jsSource = jsSource;
        this.javaNative = javaNative;
    }

    String getName() {
        return name;
    }

    String getDescription() {
        return description;
    }

    String getNovaSource() {
        return novaSource;
    }

    String getJsSource() {
        return jsSource;
    }

    int runJavaNative() {
        return javaNative.getAsInt();
    }
}
