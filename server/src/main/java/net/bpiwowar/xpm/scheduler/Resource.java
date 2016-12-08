package net.bpiwowar.xpm.scheduler;

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

import com.google.gson.JsonObject;
import net.bpiwowar.xpm.connectors.Launcher;
import net.bpiwowar.xpm.connectors.NetworkShare;
import net.bpiwowar.xpm.exceptions.CloseException;
import net.bpiwowar.xpm.exceptions.ExperimaestroCannotOverwrite;
import net.bpiwowar.xpm.exceptions.LockException;
import net.bpiwowar.xpm.exceptions.XPMRuntimeException;
import net.bpiwowar.xpm.manager.scripting.Expose;
import net.bpiwowar.xpm.manager.scripting.Exposed;
import net.bpiwowar.xpm.utils.CloseableIterable;
import net.bpiwowar.xpm.utils.CloseableIterator;
import net.bpiwowar.xpm.utils.FileNameTransformer;
import net.bpiwowar.xpm.utils.FileSystem;
import net.bpiwowar.xpm.utils.GsonSerialization;
import net.bpiwowar.xpm.utils.JsonSerializationInputStream;
import net.bpiwowar.xpm.utils.PathUtils;
import net.bpiwowar.xpm.utils.db.SQLInsert;
import net.bpiwowar.xpm.utils.log.Logger;
import org.apache.commons.lang.NotImplementedException;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Collection;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;

import static java.lang.String.format;
import static net.bpiwowar.xpm.utils.GsonConverter.defaultBuilder;

/**
 * The most general type of object manipulated by the server (can be a server, a
 * task, or a data)
 *
 * @author B. Piwowarski <benjamin@bpiwowar.net>
 */
@Exposed
@TypeIdentifier("RESOURCE")
public class Resource implements Identifiable {
    static ConstructorRegistry<Resource> REGISTRY = new ConstructorRegistry(new Class[]{Long.TYPE, Path.class})
            .add(Resource.class, CommandLineTask.class, TokenResource.class);

    public static final String SELECT_BEGIN = "SELECT id, type, path, status FROM resources";


    public static SQLInsert SQL_INSERT = new SQLInsert("Resources", true, "id", "type", "path", "status", "oldStatus", "data");

    /**
     * Extension for the PID file (its interpretation depends on the launcher)
     */
    public static final FileNameTransformer PID_FILE = new FileNameTransformer("", ".pid");

    /**
     * Extension for the lock file
     */
    public static final FileNameTransformer LOCK_EXTENSION = new FileNameTransformer("", ".lock");

    // --- Resource description

    /**
     * Extension for the file that describes the state of the resource
     */
    public static final FileNameTransformer STATUS_EXTENSION = new FileNameTransformer("", ".state");

    /**
     * Extension used status mark a produced resource
     */
    public static final FileNameTransformer DONE_EXTENSION = new FileNameTransformer("", ".done");

    /**
     * Extension for the file containing the return code
     */
    public static final FileNameTransformer CODE_EXTENSION = new FileNameTransformer("", ".code");


    // --- Values filled on demand

    /**
     * Extension for the file containing the script status run
     */
    public static final FileNameTransformer RUN_EXTENSION = new FileNameTransformer("", ".xpm.run");

    /**
     * Extension for the standard output of a job
     */
    public static final FileNameTransformer OUT_EXTENSION = new FileNameTransformer("", ".out");


    // --- Values filled on doPostInit

    /**
     * Extension for the standard error of a job
     */
    public static final FileNameTransformer ERR_EXTENSION = new FileNameTransformer("", ".err");

    /**
     * Extension for the standard input of a job
     */
    public static final FileNameTransformer INPUT_EXTENSION = new FileNameTransformer("", ".xpm.input.");


    final static private Logger LOGGER = Logger.getLogger();

    /**
     * The path with the launcher
     */
    @GsonSerialization(serialize = false)
    protected Path locator;

    /**
     * The resource ID
     */
    @GsonSerialization(serialize = false)
    private Long resourceID;

    /**
     * The ingoing dependencies (resources that we depend upon)
     */
    transient private Map<Resource, Dependency> ingoingDependencies;

    /**
     * The resource state
     */
    transient private ResourceState state = ResourceState.ON_HOLD;

    /**
     * Flag that says whether the data has been loaded
     */
    transient private boolean dataLoaded;

    public Resource(Path locator) {
        this.locator = locator;
        ingoingDependencies = new HashMap<>();
        dataLoaded = true;
    }

    /**
     * Construct from DB
     */
    public Resource(long id, Path locator) throws SQLException {
        this.locator = locator;
        this.resourceID = id;

        ingoingDependencies = null;
        dataLoaded = false;
    }

    @Override
    protected void finalize() {
        LOGGER.debug("Finalizing resource [%s@%s]", System.identityHashCode(this), this);
    }

    @Override
    public int hashCode() {
        return getLocator().hashCode();
    }

    @Override
    public boolean equals(Object obj) {
        return obj instanceof Resource && locator.equals(((Resource) obj).getLocator());
    }

    @Override
    @Expose
    public String toString() {
        if (resourceID == null)
            return "R-";

        return format("R%d", resourceID);
    }

    /**
     * Returns a detailed description
     */
    public String toDetailedString() {
        return toString();
    }

    /**
     * Get the ID
     */
    @Override
    public Long getId() {
        return resourceID;
    }

    public void setId(Long id) {
        this.resourceID = id;
    }

    /**
     * Update the state of the resource by checking all that could have changed externally
     * <p>
     * Calls {@linkplain #doUpdateStatus()}.
     * If the update fails for some reason, then we just put the state into HOLD
     */
    synchronized final public boolean updateStatus() throws SQLException {
        try {
            boolean b = doUpdateStatus();
            return b;
        } catch (Exception e) {
            LOGGER.error(e, "Exception while updating status");
            return setState(ResourceState.ON_HOLD);
        }
    }

    /**
     * Do a full update of the state of this resource.
     * <p>
     * This implies looking at the disk status check for done/lock files, etc.
     *
     * @return True if the state was updated
     */
    protected boolean doUpdateStatus() throws Exception {
        return false;
    }

    public boolean isStored() {
        return resourceID != null;
    }

    /**
     * Checks whether this resource can be replaced
     *
     * @return
     */
    public boolean canBeReplaced() {
        return ResourceState.UPDATABLE_STATES.contains(state);
    }

    /**
     * Notifies the resource that something happened
     *
     * @param message The message
     */
    public void notify(Message message) throws SQLException {
        switch (message.getEvent()) {
            case RESOURCE_REMOVED:
                break;
        }
    }

    /**
     * The set of resources the resource is dependent upon
     */
    public Collection<Dependency> getDependencies() {
        return ingoingDependencies().values();
    }

    /**
     * The set of dependencies that are dependent on this resource
     */
    public CloseableIterator<Dependency> getOutgoingDependencies(boolean restrictToActive) {
        return new CloseableIterator<Dependency>() {
            public ResultSet resultSet;

            public PreparedStatement st;

            @Override
            public void close() throws CloseException {
                try {
                    if (st != null) {
                        st.close();
                        resultSet.close();
                    }
                } catch (SQLException e1) {
                    throw new CloseException(e1);
                }
            }

            @Override
            protected Dependency computeNext() {
                try {
                    if (st == null) {
                        final String sql = restrictToActive ? Dependency.SELECT_OUTGOING_DEPENDENCIES
                                : Dependency.SELECT_OUTGOING_ACTIVE_DEPENDENCIES;
                        st = Scheduler.prepareStatement(sql);
                        st.setLong(1, getId());
                        st.execute();
                        resultSet = st.getResultSet();
                    }

                    if (!resultSet.next()) {
                        return endOfData();
                    }

                    return Dependency.create(resultSet);
                } catch (SQLException e) {
                    throw new XPMRuntimeException("Could not retrieve ingoing dependencies [%s] from DB", Resource.this);
                }
            }
        };

    }

    /**
     * @return the generated
     */
    public boolean isGenerated() {
        return getState() == ResourceState.DONE;
    }

    /**
     * Get a filesystem path
     */
    public Path getLocator() {
        return locator;
    }

    /**
     * Get the identifier of this task
     *
     * @return Return a stringified version of the path
     */
    public String getIdentifier() {
        return getLocator().toString();
    }

    /**
     * Get the state of the resource
     *
     * @return The current state of the resource
     */
    final public ResourceState getState() {
        return state;
    }

    /**
     * Sets the state
     *
     * @param state The new state
     * @return <tt>true</tt> if state changed, <tt>false</tt> otherwise
     */
    synchronized public boolean setState(ResourceState state) throws SQLException {
        if (this.state == state)
            return false;

        // Update in DB
        if (inDatabase()) {
            try (final PreparedStatement st = Scheduler.getConnection().prepareStatement("UPDATE Resources SET status=? WHERE id=?")) {
                st.setInt(1, state.value());
                st.setLong(2, getId());
                st.execute();
                final int count = st.getUpdateCount();

                if (count != 1) {
                    throw new SQLException(format("Updating resource resulted in %d updated rows", count));
                }
            } catch (SQLException e) {
                throw new SQLException(e);
            }
            LOGGER.debug("Stored new state [%s] of job %s in database", state, this);
        }
        this.state = state;

        if (inDatabase()) {
            if (state == ResourceState.READY) {
                LOGGER.debug("Notifying runners");
                Scheduler.notifyRunners();
            }
            LOGGER.debug("Notifying dependencies of %s [new state %s]", this, state);
            Scheduler.get().addChangedResource(this);
        }

        Scheduler.get().notify(ResourceMessage.changed(this));

        return true;
    }

    public boolean inDatabase() {
        return resourceID != null;
    }

    /**
     * Creates a new dependency on this resource
     *
     * @param type The parameters for the dependency
     * @return a new dependency or null if this object does not need to be locked
     */
    public Dependency createDependency(DependencyParameters type) {
        return null;
    }

    /**
     * Returns the main output file for this resource
     */
    public Path outputFile() throws IOException {
        throw new IllegalAccessError("No output file for resources of type " + this.getClass());
    }

    public Path errorFile() throws IOException {
        throw new IllegalAccessError("No error file for resources of type " + this.getClass());
    }



    /**
     * Get a JSON representation of the object
     *
     * @return
     * @throws IOException
     */
    public JsonObject toJSON() throws IOException {
        JsonObject object = new JsonObject();
        object.addProperty("id", getId());
        object.addProperty("status", getState().toString());

        // Add tags
        JsonObject tags = new JsonObject();
        int count = 0;
        try(XPMStatement st = Scheduler.statement("SELECT tag, value FROM ResourceTags WHERE resource=?")) {
            st.setLong(1, getId());
            st.execute();
            try(XPMResultSet set = st.resultSet()) {
                while (set.next()) {
                    ++count;
                    tags.addProperty(set.getString(1), set.getString(2));
                }
            }
        } catch (SQLException e) {
            LOGGER.error(e);
        }
        if (count > 0) {
            object.add("tags", tags);
        }

        return object;
    }

    /**
     * Removes files related to this resource
     *
     * @param removeFile If true, all files should be removed. If false, only job execution related
     *                   files are removed (error, done, etc.)
     */
    public void clean(boolean removeFile) {
        if (state == ResourceState.RUNNING) {
            throw new XPMRuntimeException("Cannot clean resource %s since it is running", this);
        }
        if (removeFile) {
            try (PreparedStatement st = Scheduler.prepareStatement("SELECT path FROM ResourcePaths WHERE id=?")) {
                st.setLong(1, resourceID);
                st.execute();
                try (ResultSet rs = st.executeQuery()) {
                    while (rs.next()) {
                        final String uri = rs.getString(1);
                        try {
                            final Path path = NetworkShare.uriToPath(uri);
                            if (Files.isRegularFile(path)) {
                                Files.delete(path);
                            } else if (Files.isDirectory(path)) {
                                FileSystem.recursiveDelete(path);
                            } else {
                                LOGGER.error("Could not delete %s while cleaning resource %s: unknown file type", uri, this);
                            }
                        } catch (Throwable e) {
                            LOGGER.error(e, "Could not delete %s while cleaning resource %s", uri, this);
                        }
                    }
                }
            } catch (SQLException e) {
                LOGGER.error(e, "while cleaning resource %s", this);
            }
            // Clean DB
            try (PreparedStatement st = Scheduler.prepareStatement("DELETE FROM ResourcePaths WHERE id=?")) {
                st.setLong(1, resourceID);
                st.execute();
            } catch (SQLException e) {
                LOGGER.error(e, "while removing associated path in DB for resource %s", this);
            }
        }
    }

    synchronized protected Dependency addIngoingDependency(Dependency dependency) {
        dependency.to = reference();

        final Dependency put = this.ingoingDependencies().put(dependency.getFrom(), dependency);
        assert put == null;
        return put;
    }


    /**
     * Cleanup old locks
     * <p>
     * Used when database becomes corrupted for whatever reason
     */
    static public int cleanupLocks(boolean simulate) throws SQLException {
        String query = "SELECT r.id, r.type, r.path, r.status FROM Resources r WHERE r.status <> ? "
                + " AND EXISTS(SELECT * FROM Dependencies d WHERE d.toid = r.id AND lock IS NOT NULL)";
        final DatabaseObjects<Resource, Void> resources = Scheduler.get().resources();
        int count = 0;

        try (XPMStatement st = Scheduler.statement(query).setLong(1, ResourceState.RUNNING.value()).execute();
             XPMResultSet rs = st.resultSet()) {
            while (rs.next()) {
                final Resource resource = resources.getOrCreate(rs.resultSet);
                synchronized (resource) {
                    // We are not running, but have locks... remove them!
                    if (resource.getState() != ResourceState.RUNNING) {
                        if (resource instanceof Job && ((Job) resource).getProcess() != null) {
                            LOGGER.error("Job is not running but has a process... not doing anything!");
                            continue;
                        }

                        for (Dependency dependency : resource.ingoingDependencies().values()) {
                            if (dependency.hasLock()) {
                                try {
                                    if (!simulate) dependency.unlock();
                                    ++count;
                                    LOGGER.info("Unlocked %s", dependency);
                                } catch(LockException e) {
                                    LOGGER.error("Could not unlock %s", dependency);
                                }
                            }
                        }

                    }
                }
            }
        }
        return count;
    }

    ;


    /**
     * Store a resource
     *
     * @param resource
     * @return The old resource, or null if there was nothing
     * @throws ExperimaestroCannotOverwrite If the old resource could not be overriden
     */
    synchronized public Resource put(Resource resource) throws ExperimaestroCannotOverwrite, SQLException {
        // Get the group
        final boolean newResource = !resource.isStored();
        if (newResource) {
            resource.updateStatus();
        }

        // TODO implement
        final Resource old = null;
        if (old == null) {
            throw new NotImplementedException();
        }

        LOGGER.debug("Storing resource %s [%x@%s] in state %s", resource, System.identityHashCode(resource), resource.getId(), resource.getState());

        if (newResource) {
            final long id = resource.getId();
            LOGGER.debug("Adding a new resource [%s] in database [id=%d/%x]", resource, id, System.identityHashCode(resource));

            // Notify
            ResourceMessage.added(resource).send();
        }


        return old;
    }

    /**
     * Delete a resource
     *
     * @param recursive Delete dependent resources
     */
    public synchronized void delete(boolean recursive) throws SQLException {
        if (getState() == ResourceState.RUNNING) {
            throw new XPMRuntimeException("Cannot delete the running task [%s]", this);
        }

        try (CloseableIterator<Dependency> dependencies = getOutgoingDependencies(false)) {
            while (dependencies.hasNext()) {
                if (!recursive) {
                    throw new XPMRuntimeException("Cannot delete the resource %s: it has dependencies [%s]", this,
                            dependencies);
                }
                Dependency dependency = dependencies.next();
                Resource dep = dependency.getTo();
                if (dep != null) {
                    dep.delete(true);
                }
            }
        } catch (CloseException e) {
            LOGGER.error(e, "Error while closing iterator");
        }

        // Remove
        final ResourceMessage message = ResourceMessage.removed(this);
        clean(true);
        Scheduler.get().resources().delete(this);

        // Notify
        Scheduler.get().notify(message);
    }

    public Path getFileWithExtension(FileNameTransformer extension) {
        return extension.transform(locator);
    }

    synchronized final public void replaceBy(Resource resource) throws ExperimaestroCannotOverwrite, SQLException {
        if (!resource.getLocator().equals(this.locator)) {
            throw new ExperimaestroCannotOverwrite("Path %s and %s differ", resource.getLocator(), getLocator());
        }

        updateStatus(); // Update before
        if (!canBeReplaced()) {
            throw new ExperimaestroCannotOverwrite("Cannot replace %s [state is %s]", this, getState());
        }

        resource.save(Scheduler.get().resources(), this);

        // Not in DB anymore
        this.setId(null);
    }

    public void save() throws SQLException {
        // For saving, we use
        Scheduler scheduler = Scheduler.get();
        boolean success = false;
        Connection connection = Scheduler.getConnection();
        connection.setAutoCommit(false);
        try {
            save(scheduler.resources(), null);
            success = true;
        } catch (Exception e) {
            throw new SQLException(e);
        } finally {
            if (success) {
                connection.commit();
            } else {
                if (!connection.isClosed()) {
                    connection.rollback();
                }
            }
            if (!connection.isClosed()) {
                connection.setAutoCommit(true);
            }
        }
    }

    synchronized protected void save(DatabaseObjects<Resource, Void> resources, Resource old) throws SQLException {
        LOGGER.debug("Saving resource %s [old=%s]", this, old);

        boolean update = old != null;
        if (update) {
            setId(old.getId());
        }

        if (update && getId() == null) {
            throw new SQLException("Resource not in database");
        } else if (!update && getId() != null) {
            throw new SQLException("Resource already in database");
        }

        // Update the status
        updateStatus();

        // Save resource
        // Save on file
        final long typeValue = DatabaseObjects.getTypeValue(getClass());
        LOGGER.debug("Saving resource [%s] of type %s [%d], status %s [%s]",
                getLocator(), getClass(), typeValue,
                getState(), getState().value());

        try (final JsonSerializationInputStream jsonInputStream = JsonSerializationInputStream.of(this, defaultBuilder)) {
            resources.save(this, SQL_INSERT, update, typeValue, PathUtils.normalizedString(this.locator), getState().value(),
                    update ? old.getState().value() : getState().value(),
                    jsonInputStream);
        } catch (IOException e) {
            throw new SQLException(e);
        }

        if (update) {
            ingoingDependencies(); // just load from db if needed

            // Delete dependencies that are not required anymore
            try (PreparedStatement st = Scheduler.getConnection().prepareStatement("DELETE FROM Dependencies WHERE fromId=? and toId=?")) {
                st.setLong(2, getId());

                for (Dependency dependency : old.ingoingDependencies().values()) {
                    if (!ingoingDependencies.containsKey(dependency.getFrom())) {
                        // Remove dependency
                        st.setLong(1, dependency.getFromId());
                        st.execute();
                    } else {
                        // Replace dependency
                        dependency.replaceBy(dependency);
                    }
                }
            }

        }

        // Save dependencies
        for (Dependency dependency : ingoingDependencies().values()) {
            if (!update || !old.ingoingDependencies.containsKey(dependency.getFrom())) {
                dependency.save(false);
            }
        }


        LOGGER.debug("Resource %s saved/updated", this);
        if (update && old.getState() != getState()) {
            Scheduler.get().addChangedResource(this);
            ResourceMessage.changed(this).send();
        } else if (!update) {
            ResourceMessage.added(this).send();
        }

        if (getState() == ResourceState.READY) {
            LOGGER.debug("Job is READY, notifying");
            Scheduler.notifyRunners();
        }
    }

    /**
     * Get a resource by locator
     *
     * @param path The path of the resource
     * @return The resource or null if there is no such resource
     */
    static public Resource getByLocator(Path path) throws SQLException {
        return Scheduler.get().resources().findUnique(SELECT_BEGIN + " WHERE path=?", st -> st.setString(1, PathUtils.normalizedString(path)));
    }

    static public Resource getByLocator(String path) throws SQLException {
        return Scheduler.get().resources().findUnique(SELECT_BEGIN + " WHERE path=?", st -> st.setString(1, path));
    }

    static protected Resource create(DatabaseObjects<Resource, Void> ignored, ResultSet result, Void ignored2) {
        try {
            long id = result.getLong(1);
            long type = result.getLong(2);
            Path path = NetworkShare.uriToPath(result.getString(3));
            int status = result.getInt(4);

            final Constructor<? extends Resource> constructor = REGISTRY.get(type);
            final Resource resource = constructor.newInstance(id, path);

            // Set stored values
            resource.state = ResourceState.fromValue(status);

            return resource;
        } catch (InstantiationException | SQLException | InvocationTargetException | IllegalAccessException e) {
            throw new XPMRuntimeException("Error retrieving database object", e);
        }
    }

    /**
     * Iterator on resources
     */
    static public CloseableIterable<Resource> resources() throws SQLException {
        final DatabaseObjects<Resource, Void> resources = Scheduler.get().resources();
        return resources.find(SELECT_BEGIN, st -> {
        });
    }

    public static CloseableIterable<Resource> find(EnumSet<ResourceState> states) throws SQLException {
        final DatabaseObjects<Resource, Void> resources = Scheduler.get().resources();
        StringBuilder sb = new StringBuilder();
        sb.append(SELECT_BEGIN);
        sb.append(" WHERE status in (");
        boolean first = true;
        for (ResourceState state : states) {
            if (first) {
                first = false;
            } else {
                sb.append(',');
            }
            sb.append(state.value());
        }
        sb.append(")");

        final String query = sb.toString();
        LOGGER.debug("Searching for resources in states %s: %s", states, query);
        return resources.find(query, st -> {
        });
    }

    static public Resource getById(long resourceId) throws SQLException {
        final DatabaseObjects<Resource, Void> resources = Scheduler.get().resources();
        final Resource r = resources.getFromCache(resourceId);
        if (r != null) {
            return r;
        }

        return resources.findUnique(SELECT_BEGIN + " WHERE id=?", st -> st.setLong(1, resourceId));
    }


    /**
     * Load data from database
     */
    protected boolean loadData() {
        if (dataLoaded) {
            return false;
        }

        Scheduler.get().resources().loadData(defaultBuilder, this, "data");
        dataLoaded = true;
        return true;
    }

    @Expose("output")
    public Path output() throws IOException {
        return outputFile();
    }

    @Expose
    public Path file() throws FileSystemException {
        return getLocator();
    }

    @Expose
    public Path resolve(String path) throws FileSystemException {
        return getLocator().getParent().resolve(path);
    }

    @Expose
    public Dependency lock(DependencyParameters lockType) {
        return createDependency(lockType);
    }

    synchronized private Map<Resource, Dependency> ingoingDependencies() {
        if (ingoingDependencies == null) {
            HashMap<Resource, Dependency> ingoingDependencies = new HashMap<>();

            try (PreparedStatement st = Scheduler.prepareStatement(Dependency.SELECT_INGOING_DEPENDENCIES)) {
                st.setLong(1, getId());
                st.execute();
                try (final ResultSet rs = st.getResultSet()) {
                    while (rs.next()) {
                        Dependency dependency = Dependency.create(rs);
                        ingoingDependencies.put(dependency.getFrom(), dependency);
                    }
                }

                this.ingoingDependencies = ingoingDependencies;
            } catch (SQLException e) {
                throw new XPMRuntimeException(e, "Could not retrieved ingoing dependencies from DB");
            }
        }
        return ingoingDependencies;
    }

    public ResourceReference reference() {
        return new ResourceReference(this);
    }

    synchronized public void updatedDependency(Dependency dep) throws SQLException {
        if (ingoingDependencies != null) {
            Dependency _dep = ingoingDependencies.get(dep.getFrom());
            if (_dep == null) {
                // Invalid cache since something changed...
                ingoingDependencies = null;
            }
        }

        DependencyStatus beforeState = dep.status;

        if (dep.update()) {
            final Resource depResource = dep.getTo();

            if (!ResourceState.NOTIFIABLE_STATE.contains(depResource.getState())) {
                LOGGER.debug("We won't notify resource %s since its state is %s", depResource, depResource.getState());
                return;
            }

            // Queue this change in dependency state
            depResource.notify(new DependencyChangedMessage(dep, beforeState, dep.status));

        } else {
            LOGGER.debug("No change in dependency status [%s -> %s]", beforeState, dep.status);
        }
    }

    /**
     * Does nothing for a resource
     */
    public void setLauncher(Launcher launcher, LauncherParameters launcherParameters) {
    }

    public Dependency createDependency() {
        return this.createDependency((DependencyParameters) null);
    }

    /**
     * Create a dependency
     *
     * @param pmap The dependency parameters map
     * @return The dependency
     */
    public Dependency createDependency(IdentityHashMap<Object, DependencyParameters> pmap) {
        // Get resource specific parameters
        DependencyParameters dp = pmap.get(this);
        if (dp != null) return createDependency(dp);

        // Get class specific parameters
        dp = pmap.get(getClass());
        return createDependency(dp);
    }

    /**
     * Defines how printing should be done
     */
    static public class PrintConfig {
        public String detailURL;
    }

}