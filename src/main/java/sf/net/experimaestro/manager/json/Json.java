/*
 * This file is part of experimaestro.
 * Copyright (c) 2013 B. Piwowarski <benjamin@bpiwowar.net>
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

package sf.net.experimaestro.manager.json;

import sf.net.experimaestro.manager.QName;

/**
 * Bsae class for all JSON objects
 * @author B. Piwowarski <benjamin@bpiwowar.net>
 * @date 1/4/13
 */
public interface Json {
    Json clone();

    /**
     * Returns true if this Json object is a simple type
     */
    boolean isSimple();

    /**
     * Returns the simple value underlying this object
     * @return
     */
    Object get();

    /**
     * Get the XPM type
     */
    QName type();
}
