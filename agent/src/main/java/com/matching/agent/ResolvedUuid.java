package com.matching.agent;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Advice parameter annotation that injects the @FunctionMetadata UUID
 * as a compile-time constant, eliminating the runtime HashMap lookup.
 * Resolved at instrumentation time by Advice.withCustomMapping().
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.PARAMETER)
public @interface ResolvedUuid {
}
