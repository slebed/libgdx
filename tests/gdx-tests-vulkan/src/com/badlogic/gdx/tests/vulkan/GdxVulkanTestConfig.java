
package com.badlogic.gdx.tests.vulkan;

import static java.lang.annotation.ElementType.TYPE;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

import java.lang.annotation.Retention;
import java.lang.annotation.Target;

@Retention(RUNTIME)
@Target(TYPE)
public @interface GdxVulkanTestConfig {
	boolean requireGL30() default false;

	boolean requireGL31() default false;

	boolean requireGL32() default false;

	boolean OnlyGL20() default false;
}
