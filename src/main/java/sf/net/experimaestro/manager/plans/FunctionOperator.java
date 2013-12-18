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

package sf.net.experimaestro.manager.plans;

import com.google.common.collect.AbstractIterator;
import com.google.common.collect.Iterators;
import sf.net.experimaestro.manager.Manager;
import sf.net.experimaestro.manager.json.Json;

import java.util.Iterator;
import java.util.Map;

/**
 * @author B. Piwowarski <benjamin@bpiwowar.net>
 * @date 22/2/13
 */
public class FunctionOperator extends UnaryOperator {
    Function function;

    public FunctionOperator(Function function) {
        this.function = function;
    }

    @Override
    protected String getName() {
        return "Function " + function;
    }

    @Override
    protected Operator doCopy(boolean deep, Map<Object, Object> map) {
        FunctionOperator copy = new FunctionOperator(function);
        return super.copy(deep, map, copy);
    }

    @Override
    protected Iterator<ReturnValue> _iterator(final RunOptions runOptions) {
        return new AbstractIterator<ReturnValue>() {
            Iterator<Value> iterator = input.iterator(runOptions);
            public Iterator<? extends Json> innerIterator = Iterators.emptyIterator();
            DefaultContexts contexts;

            @Override
            protected ReturnValue computeNext() {
                while (true) {
                    if (innerIterator.hasNext())
                        return new ReturnValue(contexts, Manager.wrap(innerIterator.next()));
                    if (!iterator.hasNext())
                        return endOfData();

                    Value value = iterator.next();
                    innerIterator = function.f(value.nodes).iterator();

                    contexts = new DefaultContexts(value.context);
                }
            }
        };
    }
}