package sf.net.experimaestro.exceptions;

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

/**
 * Exception that are caused by the execution of a script or a command - they are
 * not errors
 */
public class XPMCommandException extends XPMRuntimeException {
    public XPMCommandException() {
    }

    public XPMCommandException(String message, Throwable t) {
        super(message, t);
    }

    public XPMCommandException(Throwable t, String format, Object... values) {
        super(t, format, values);
    }

    public XPMCommandException(String message) {
        super(message);
    }

    public XPMCommandException(String format, Object... values) {
        super(format, values);
    }

    public XPMCommandException(Throwable t) {
        super(t);
    }
}
