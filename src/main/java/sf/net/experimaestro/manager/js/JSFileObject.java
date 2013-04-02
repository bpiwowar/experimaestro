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

import org.apache.commons.vfs2.FileObject;
import org.apache.commons.vfs2.FileSystemException;
import org.mozilla.javascript.Context;
import sf.net.experimaestro.manager.ValueType;
import sf.net.experimaestro.manager.json.Json;
import sf.net.experimaestro.scheduler.Scheduler;

import java.io.*;

/**
 * @author B. Piwowarski <benjamin@bpiwowar.net>
 * @date 26/11/12
 */
public class JSFileObject extends JSBaseObject implements XMLSerializable {
    public static final String JSCLASSNAME = "FileObject";
    private FileObject file;
    private XPMObject xpm;

    public JSFileObject() {
    }

    public JSFileObject(XPMObject xpm, FileObject file) {
        this.xpm = xpm;
        this.file = file;
    }

    public JSFileObject(XPMObject xpm, String path) throws FileSystemException {
        this.xpm = xpm;
        this.file = Scheduler.getVFSManager().resolveFile(path);
    }

    @Override
    @JSFunction("toString")
    public String toString() {
        return file == null ? "[null]" : file.toString();
    }

    @JSFunction("toSource")
    public String toSource() {
        return String.format("new FileObject(%s)", file.toString());
    }


    @JSHelp(value = "Get the parent file object")
    @JSFunction("get_parent")
    public JSFileObject getParent() throws FileSystemException {
        return get_ancestor(1);
    }

    @JSFunction("resolve")
    public JSFileObject resolve(String path) throws FileSystemException {
        return new JSFileObject(xpm, file.resolveFile(path));
    }

    @JSHelp(value = "Get the n<sup>th</sup> ancestor of this file object",
            arguments = @JSArguments(@JSArgument(type = "Integer", name = "levels")))
    @JSFunction("get_ancestor")
    public JSFileObject get_ancestor(int level) throws FileSystemException {
        if (level < 0)
            throw new IllegalArgumentException("Level is negative (" + level + ")");

        FileObject ancestor = this.file;
        while (--level >= 0)
            ancestor = ancestor.getParent();

        return new JSFileObject(xpm, ancestor);
    }


    @JSFunction("path")
    @JSHelp(value = "Returns a file object corresponding to the path given in the arguments. " +
            "Each name given corresponds to a new path component starting from this file object.",
            arguments = @JSArguments({@JSArgument(type = "String", name = "name"), @JSArgument(name = "...")}))
    public JSFileObject path(String... args) throws FileSystemException {
        FileObject current = file;
        for (int i = 0; i < args.length; i++) {
            if (args[i] == null || args[i].equals(""))
                throw new IllegalArgumentException("Undefined element in path");
            String name = Context.toString(args[i]);
            current = current.resolveFile(name);
        }

        return new JSFileObject(xpm, current);
    }

    @JSFunction("mkdirs")
    @JSHelp("Creates this folder, if it does not exist.  Also creates any ancestor\n" +
            "folders which do not exist.  This method does nothing if the folder\n" +
            "already exists.")
    public void mkdirs() throws FileSystemException {
        file.createFolder();
    }

    @JSFunction("exists")
    public boolean exists() throws FileSystemException {
        return file.exists();
    }

    @JSFunction("get_size")
    public long get_size() throws FileSystemException {
        return file.getContent().getSize();
    }

    @JSFunction("add_extension")
    @JSHelp("Adds an extension to the current filename")
    public Object add_extension(String extension) throws FileSystemException {
        return new JSFileObject(xpm, file.getParent().resolveFile(file.getName().getBaseName() + extension));
    }

    @JSFunction
    @JSHelp("Removes extension to the current filename")
    public Object remove_extension(String extension) throws FileSystemException {
        String baseName = file.getName().getBaseName();
        if (baseName.endsWith(extension))
            baseName = baseName.substring(0, baseName.length()-extension.length());
        return new JSFileObject(xpm, file.getParent().resolveFile(baseName));
    }

    @Override
    public Json serialize() {
        return ValueType.wrapString(file.toString(), ValueType.XPM_FILE);
    }

    static class MyPrintWriter extends PrintWriter {
        final XPMObject xpm;

        public MyPrintWriter(XPMObject xpm, OutputStream out) {
            super(out);
            this.xpm = xpm;
            xpm.register(this);
        }

        @Override
        public void close() {
            super.close();
            xpm.unregister(this);
        }
    }

    @JSFunction
    public PrintWriter output_stream() throws FileSystemException {
        final OutputStream output = file.getContent().getOutputStream();
        final PrintWriter writer = new MyPrintWriter(xpm, output);
        return writer;
    }

    static class MyInputStreamReader extends InputStreamReader {
        final XPMObject xpm;

        public MyInputStreamReader(XPMObject xpm, InputStream in) {
            super(in);
            this.xpm = xpm;
            xpm.register(this);
        }

        @Override
        public void close() throws IOException {
            super.close();
            xpm.unregister(this);
        }
    }

    @JSFunction
    public BufferedReader input_stream() throws FileSystemException {
        final BufferedReader reader = new BufferedReader(new MyInputStreamReader(xpm, file.getContent().getInputStream()));
        return reader;
    }

    public FileObject getFile() {
        return file;
    }

    @JSFunction("get_path")
    public String get_path() {
        return file.getName().getPath();
    }
}