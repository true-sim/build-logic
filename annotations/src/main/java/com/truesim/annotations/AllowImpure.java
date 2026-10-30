package com.truesim.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Exempts a class or method from the {@code truesim.allocation-free} purity check of frame graph code: a technique's
 * {@code build} writing state, or a pass body storing into fields or captured arrays. On a class, it also covers nested
 * classes and lambdas.
 */
@Retention(RetentionPolicy.CLASS)
@Target({ElementType.TYPE, ElementType.METHOD})
public @interface AllowImpure {
    /** Why the state written is safe here. */
    String value() default "";
}
