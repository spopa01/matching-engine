package com.matching.agent;

import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.annotation.AnnotationDescription;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.method.ParameterDescription;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.implementation.bytecode.assign.Assigner;
import net.bytebuddy.matcher.ElementMatchers;

import java.lang.instrument.Instrumentation;

public class MatchingAgent {

    public static void premain(String agentArgs, Instrumentation inst) {
        System.out.println("=== Matching Engine Agent Starting ===");

        new AgentBuilder.Default()
                .type(ElementMatchers.nameStartsWith("com.matching.engine"))
                .transform((builder, typeDescription, classLoader, module, protectionDomain) -> {
                    System.out.println("Instrumenting class: " + typeDescription.getName());
                    preRegisterUuids(typeDescription);
                    return builder
                            .visit(Advice.withCustomMapping()
                                    .bind(new UuidOffsetMappingFactory())
                                    .to(MethodInterceptor.class)
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

    private static void preRegisterUuids(TypeDescription typeDescription) {
        try {
            String className = typeDescription.getName();
            for (MethodDescription.InDefinedShape method : typeDescription.getDeclaredMethods()) {
                for (AnnotationDescription annotDesc : method.getDeclaredAnnotations()) {
                    if (annotDesc.getAnnotationType().getName().equals("com.matching.annotation.FunctionMetadata")) {
                        String uuid = (String) annotDesc.getValue("uuid").resolve();
                        String methodKey = className + "." + method.getName();
                        MethodInterceptor.functionUuidByKey.put(methodKey, uuid.intern());
                    }
                }
            }
        } catch (Throwable e) {
            System.err.println("Failed to pre-register UUIDs for " + typeDescription.getName() + ": " + e.getMessage());
        }
    }

    /**
     * Resolves @ResolvedUuid parameters at instrumentation time.
     * The UUID becomes an ldc constant in the inlined bytecode — zero runtime lookup cost.
     */
    static class UuidOffsetMappingFactory implements Advice.OffsetMapping.Factory<ResolvedUuid> {
        @Override
        public Class<ResolvedUuid> getAnnotationType() {
            return ResolvedUuid.class;
        }

        @Override
        public Advice.OffsetMapping make(ParameterDescription.InDefinedShape target,
                                         AnnotationDescription.Loadable<ResolvedUuid> annotation,
                                         Advice.OffsetMapping.Factory.AdviceType adviceType) {
            return (instrumentedType, instrumentedMethod, assigner, argumentHandler, sort) -> {
                String key = instrumentedType.getName() + "." + instrumentedMethod.getName();
                String uuid = MethodInterceptor.functionUuidByKey.getOrDefault(key, "unknown");
                return Advice.OffsetMapping.Target.ForStackManipulation.of(uuid);
            };
        }
    }
}
