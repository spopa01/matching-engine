package com.matching.agent;

import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.matcher.ElementMatchers;

import java.lang.instrument.Instrumentation;

public class MatchingAgent {

    public static void premain(String agentArgs, Instrumentation inst) {
        System.out.println("=== Matching Engine Agent Starting ===");

        new AgentBuilder.Default()
                .type(ElementMatchers.nameStartsWith("com.matching.engine"))
                .transform((builder, typeDescription, classLoader, module, protectionDomain) -> {
                    System.out.println("Instrumenting class: " + typeDescription.getName());
                    return builder
                            .visit(Advice.to(MethodInterceptor.class)
                                    .on(ElementMatchers.isAnnotatedWith(
                                            ElementMatchers.named("com.matching.annotation.FunctionMetadata"))));
                })
                .with(new AgentBuilder.Listener.Adapter() {
                    @Override
                    public void onError(String typeName, ClassLoader classLoader,
                                        net.bytebuddy.utility.JavaModule module,
                                        boolean loaded, Throwable throwable) {
                        System.err.println("Error instrumenting: " + typeName);
                        throwable.printStackTrace();
                    }
                })
                .installOn(inst);

        System.out.println("=== Matching Engine Agent Started ===");
    }
}
