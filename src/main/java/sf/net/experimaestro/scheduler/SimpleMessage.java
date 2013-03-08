/*
 * This file is part of experimaestro.
 * Copyright (c) 2012 B. Piwowarski <benjamin@bpiwowar.net>
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

package sf.net.experimaestro.scheduler;

/**
 * Simple messages that can be sent to resources
 *
 * @author B. Piwowarski <benjamin@bpiwowar.net>
 * @date 30/11/12
 */
public enum  SimpleMessage {
    // Resource was stored message
    STORED_IN_DATABASE;

    Wrapper wrap() {
        return new Wrapper(this);
    }

    static public class Wrapper extends Message {
        SimpleMessage message;

        public Wrapper(SimpleMessage message) {
            this.message = message;
        }

        SimpleMessage get() {
            return message;
        }

        @Override
        public String toString() {
            return message.toString();
        }
    }
}