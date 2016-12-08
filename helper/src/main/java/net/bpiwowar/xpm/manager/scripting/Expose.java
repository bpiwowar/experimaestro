package net.bpiwowar.xpm.manager.scripting;

/*
 * This file is part of experimaestro.
 * Copyright (c) 2014 B. Piwowarski <benjamin@bpiwowar.net>
 *
 * experimaestro is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * experimaestro is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with experimaestro.  If not, see <http://www.gnu.org/licenses/>.
 */

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.EnumSet;

/**
 * Marks a method which is exposed to scripts
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.CONSTRUCTOR, ElementType.METHOD})
public @interface Expose {
    /**
     * The name of the function (by default, the name of the method)
     *
     * @return an empty string if using default, otherwise a valid name
     */
    String value() default "";


    /**
     * Number of arguments that are optional.
     *
     * @return An integer specifying the number of optional arguments
     */
    int optional() default 0;

    /**
     * Optional arguments are at the beginning when true
     *
     * @return true if the optional arguments are the first
     */
    boolean optionalsAtStart() default false;

    /**
     * Whether the context should be passed (language and script context)
     *
     * @return True if the first argument should be a ScriptContext object
     */
    boolean context() default false;

    /**
     * How is this method used (ignored for constructors)
     *
     * @return The mode (default: a method)
     */
    ExposeMode mode() default ExposeMode.METHOD;

    /**
     * List of languages that may use this method
     * @return List of languages or empty array if all
     */
    Languages[] languages() default {};
}