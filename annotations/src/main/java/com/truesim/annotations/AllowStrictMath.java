package com.truesim.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Allows {@link StrictMath} in a class, constructor or method, for example to verify the
 * project's own math functions against it. On a class, it also covers nested classes and lambdas.
 *
 * <p>{@code java.lang.Math} stays forbidden: apart from its exactly specified methods, its results
 * may differ between platforms.
 */
@Retention(RetentionPolicy.CLASS)
@Target({ElementType.TYPE, ElementType.CONSTRUCTOR, ElementType.METHOD})
public @interface AllowStrictMath {
    /** Why StrictMath is needed here. */
    String value() default "";
}
