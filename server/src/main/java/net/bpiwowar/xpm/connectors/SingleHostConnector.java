package net.bpiwowar.xpm.connectors;

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

import net.bpiwowar.xpm.exceptions.LockException;
import net.bpiwowar.xpm.fs.XPMPath;
import net.bpiwowar.xpm.locks.Lock;
import net.bpiwowar.xpm.manager.scripting.Exposed;
import net.bpiwowar.xpm.utils.JsonAbstract;

import java.io.IOException;
import java.nio.file.FileSystem;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;

import static java.lang.String.format;

/**
 * A connector that corresponds to a single host.
 * <p>
 * Descendant of this class of connector provide access to a file system and to a process launcher.
 *
 * @author B. Piwowarski <benjamin@bpiwowar.net>
 */
@JsonAbstract
@Exposed
abstract public class SingleHostConnector extends Connector {
    /**
     * Underlying filesystem
     */
    transient private FileSystem filesystem;

    public SingleHostConnector(String id) {
        super(id);
    }


    protected SingleHostConnector() {
    }

    public SingleHostConnector(long id) {
        super(id);
    }

    @Override
    public SingleHostConnector getConnector(ComputationalRequirements requirements) {
        // By default, returns ourselves - TODO: check the requirements
        return this;
    }

    @Override
    public SingleHostConnector getMainConnector() {
        // By default, the main connector is ourselves
        return this;
    }

    /**
     * Get the underlying filesystem
     */
    protected abstract FileSystem doGetFileSystem() throws IOException;

    /**
     * Get the underlying file system
     *
     * @return
     * @throws FileSystemException
     */
    public FileSystem getFileSystem() throws IOException {
        if (filesystem == null)
            return filesystem = doGetFileSystem();
        return filesystem;
    }

    /**
     * Resolve the file name
     *
     * @param path The path to the file
     * @return A file object
     * @throws FileSystemException
     */
    public Path resolveFile(String path) throws IOException {
        return getFileSystem().getPath(path);
    }

    /**
     * Resolve a Path to a local path
     * <p>
     * Throws an exception when the file name cannot be resolved, i.e. when
     * the file object is not
     */
    public String resolve(Path path, Path reference) throws IOException {
        if (reference != null) {
            return reference.relativize(path).toString();
        }

        if (path instanceof XPMPath) {
            XPMPath xpmPath = (XPMPath) path;
            if (!(reference instanceof XPMPath)) {
                throw new IOException();
            }
            NetworkShareAccess access = null;
            try {
                access = NetworkShare.find(this, xpmPath);
            } catch (SQLException e) {
                throw new IOException("Cannot find the share", e);
            }
            if (access != null) {
                return access.resolve(xpmPath);
            }
            return null;
        }

        if (!contains(path.getFileSystem())) {
            throw new FileSystemException(format("Cannot resolve file %s within filesystem %s", path, this));
        }

        return path.toAbsolutePath().toString();
    }

    /**
     * Returns true if the filesystem matches
     */
    protected abstract boolean contains(FileSystem fileSystem) throws IOException;

    /**
     * Returns a process builder
     */
    public abstract AbstractProcessBuilder processBuilder();

    /**
     * Lock a file
     */
    public abstract Lock createLockFile(Path path, boolean wait) throws LockException;

    /**
     * Returns the hostname
     */
    public abstract String getHostName();


    public Path getTemporaryFile(String prefix, String suffix) throws IOException {
        return Files.createTempFile(getTemporaryDirectory(), prefix, suffix);
    }

    protected abstract Path getTemporaryDirectory() throws IOException;

    @Override
    public Path resolve(String path) throws IOException {
        return getFileSystem().getPath(path);
    }
}
