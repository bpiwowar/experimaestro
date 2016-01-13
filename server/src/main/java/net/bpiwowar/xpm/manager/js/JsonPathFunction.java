package net.bpiwowar.xpm.manager.js;

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

import net.bpiwowar.xpm.exceptions.XPMRhinoException;
import net.bpiwowar.xpm.manager.QName;
import net.bpiwowar.xpm.manager.json.Json;
import net.bpiwowar.xpm.manager.json.JsonArray;
import net.bpiwowar.xpm.manager.json.JsonObject;
import net.bpiwowar.xpm.manager.plans.functions.Function;

import java.util.Iterator;

/**
 * @author B. Piwowarski <benjamin@bpiwowar.net>
 * @date 2/4/13
 */
public class JsonPathFunction implements Function {
    private final String[] path;
    private final String query;

    public JsonPathFunction(String query, java.util.function.Function<Json, Object> scope) {
        this.query = query;
        this.path = query.split("\\.");
        for (int i = 0; i < path.length; i++) {
            path[i] = path[i].equals("*") ? "*" : QName.parse(path[i]).toString();
        }
    }

    @Override
    public String toString() {
        return String.format("select(%s)", query);
    }

    @Override
    public Iterator<? extends Json> apply(Json[] input) {
        if (input.length != 1)
            throw new XPMRhinoException("Expected a single JSON in JsonPathFunction");

        JsonArray current = new JsonArray();
        current.add(input[0]);
        for (String aPath : path) {
            boolean any = aPath.equals("*");
            JsonArray next = new JsonArray();
            for (Json json : current) {
                if (json instanceof JsonArray) {
                    if (any)
                        for (Json e : (JsonArray) json)
                            next.add(e);
                } else if (json instanceof JsonObject) {
                    if (any) {
                        for (Json e : ((JsonObject) json).values())
                            next.add(e);
                    } else {
                        Json value = ((JsonObject) json).get(aPath);
                        if (value != null)
                            next.add(value);
                    }
                }
            }
            current = next;
        }
        return current.iterator();
    }
}