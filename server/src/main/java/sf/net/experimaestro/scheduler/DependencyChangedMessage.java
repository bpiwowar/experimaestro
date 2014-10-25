package sf.net.experimaestro.scheduler;

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
 * @author B. Piwowarski <benjamin@bpiwowar.net>
 * @date 29/1/13
 */
final public class DependencyChangedMessage extends Message {
    Dependency dependency;
    DependencyStatus from;
    DependencyStatus to;

    public DependencyChangedMessage(Dependency dependency, DependencyStatus from, DependencyStatus to) {
        super(Type.DEPENDENCY_CHANGED);
        this.dependency = dependency;
        this.from = from;
        this.to = to;
    }

    @Override
    public String toString() {
        return String.format("Dependency changed [%s -> %s / %s]", from, to, dependency);
    }
}
