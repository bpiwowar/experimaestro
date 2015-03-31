package sf.net.experimaestro.manager.js;

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

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * @author B. Piwowarski <benjamin@bpiwowar.net>
 * @date 7/2/13
 */
@Retention(RetentionPolicy.RUNTIME)
public @interface JSFunction {
    String value() default "";

    /**
     * Whether the constructor takes scope & context
     */
    boolean scope() default false;


    /**
     * Marks a function that is used when the object is called
     */
    boolean call() default false;

    /**
     * Number of arguments that are optional.
     */
    int optional() default 0;

    /**
     * Optional arguments are at the beginning when true
     */
    boolean optionalsAtStart() default false;
}