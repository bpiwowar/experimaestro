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

package sf.net.experimaestro.manager.js;

import org.apache.log4j.Level;
import org.mozilla.javascript.*;
import sf.net.experimaestro.exceptions.ExperimaestroRuntimeException;
import sf.net.experimaestro.utils.JSUtils;
import sf.net.experimaestro.utils.log.Logger;

/**
 * @author B. Piwowarski <benjamin@bpiwowar.net>
 * @date 26/11/12
 */
public class JSTokenResource extends ScriptableObject {

    private Logger logger;

    @Override
    public String getClassName() {
        return "Logger";
    }

    public JSTokenResource() {
    }

    public void jsConstructor(Scriptable xpm, String name) {
        XPMObject xpmObject = (XPMObject) ((NativeJavaObject)xpm).unwrap();
        logger = Logger.getLogger(xpmObject.loggerRepository, name);
    }


    static public void jsFunction_trace(Context cx, Scriptable thisObj, Object[] args, Function funObj) {
        log(Level.TRACE, cx, thisObj, args, funObj);
    }

    static public void jsFunction_debug(Context cx, Scriptable thisObj, Object[] args, Function funObj) {
        log(Level.DEBUG, cx, thisObj, args, funObj);
    }

    static public void jsFunction_info(Context cx, Scriptable thisObj, Object[] args, Function funObj) {
        log(Level.INFO, cx, thisObj, args, funObj);
    }

    static public void jsFunction_warn(Context cx, Scriptable thisObj, Object[] args, Function funObj) {
        log(Level.WARN, cx, thisObj, args, funObj);
    }

    static public void jsFunction_error(Context cx, Scriptable thisObj, Object[] args, Function funObj) {
        log(Level.ERROR, cx, thisObj, args, funObj);
    }

    static public void jsFunction_fatal(Context cx, Scriptable thisObj, Object[] args, Function funObj) {
        log(Level.FATAL, cx, thisObj, args, funObj);
    }


    static private void log(Level level, Context cx, Scriptable thisObj, Object[] args, Function funObj) {
        if (args.length < 1)
            throw new ExperimaestroRuntimeException("There should be at least one argument when logging");

        String format = Context.toString(args[0]);
        Object[] objects = new Object[args.length - 1];
        for (int i = 1; i < args.length; i++)
            objects[i - 1] = JSUtils.unwrap(args[i]);

        ((JSTokenResource) thisObj).logger.log(level, format, objects);
    }


}
