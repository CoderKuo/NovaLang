package com.novalang.bench;

import java.util.function.IntSupplier;

final class ScriptScenario {

    private final String name;
    private final String description;
    private final String novaSource;
    private final String jsSource;
    private final String groovySource;
    private final String jexlSource;
    private final IntSupplier javaNative;

    ScriptScenario(String name, String description, String novaSource, String jsSource,
                   String groovySource, String jexlSource, IntSupplier javaNative) {
        this.name = name;
        this.description = description;
        this.novaSource = novaSource;
        this.jsSource = jsSource;
        this.groovySource = groovySource;
        this.jexlSource = jexlSource;
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

    String getGroovySource() {
        return groovySource;
    }

    String getJexlSource() {
        return jexlSource;
    }

    int runJavaNative() {
        return javaNative.getAsInt();
    }
}
