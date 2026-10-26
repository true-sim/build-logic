package com.truesim.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Exempts a class, constructor or method from the {@code truesim.allocation-free} build check.
 * On a class, it also covers nested classes and lambdas.
 */
@Retention(RetentionPolicy.CLASS)
@Target({ElementType.TYPE, ElementType.CONSTRUCTOR, ElementType.METHOD})
public @interface AllowAllocation {
    /** Why allocating is acceptable here. */
    String value() default "";
}
