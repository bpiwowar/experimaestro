package net.bpiwowar.xpm.manager.scripting;

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

import net.bpiwowar.xpm.connectors.Launcher;
import net.bpiwowar.xpm.connectors.LocalhostConnector;
import net.bpiwowar.xpm.connectors.NetworkShare;
import net.bpiwowar.xpm.connectors.SingleHostConnector;
import net.bpiwowar.xpm.exceptions.ExperimaestroCannotOverwrite;
import net.bpiwowar.xpm.manager.Constants;
import net.bpiwowar.xpm.manager.UserCache;
import net.bpiwowar.xpm.manager.experiments.Experiment;
import net.bpiwowar.xpm.manager.json.Json;
import net.bpiwowar.xpm.manager.json.JsonArray;
import net.bpiwowar.xpm.manager.json.JsonObject;
import net.bpiwowar.xpm.manager.json.JsonSimple;
import net.bpiwowar.xpm.manager.json.JsonString;
import net.bpiwowar.xpm.scheduler.Dependency;
import net.bpiwowar.xpm.scheduler.DependencyParameters;
import net.bpiwowar.xpm.scheduler.Resource;
import net.bpiwowar.xpm.scheduler.ResourceState;
import net.bpiwowar.xpm.scheduler.Scheduler;
import net.bpiwowar.xpm.utils.log.Logger;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.FileSystemException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.NoSuchAlgorithmException;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.Map;

/**
 * General functions available to all scripting languages
 */
@Exposed
public class Functions {
    final static private Logger LOGGER = Logger.getLogger();


    private static Context context() {
        return Context.get();
    }

    @Expose
    static public String format(
            @Argument(name = "format", type = "String", help = "The string used to format")
                    String format,
            @Argument(name = "arguments...", type = "Object", help = "A list of objects")
                    Object... args) {
        return String.format(format, args);
    }


    @Expose()
    static public java.nio.file.Path path(@Argument(name = "uri") Path path) {
        return path;
    }

    @Expose()
    @Help("Returns a path object from an URI")
    static public java.nio.file.Path path(@Argument(name = "uri") String uri)
            throws FileSystemException, URISyntaxException {
        final URI _uri = new URI(uri);
        return _uri.getScheme() == null ? Paths.get(uri) : Paths.get(_uri);
    }


    @Expose(optional = 1)
    @Help("Defines a new relationship between a network share and a path on a connector")
    static public void define_share(@Argument(name = "host", help = "The logical host")
                                            String host,
                                    @Argument(name = "share")
                                            String share,
                                    @Argument(name = "connector")
                                            SingleHostConnector connector,
                                    @Argument(name = "path")
                                            String path,
                                    @Argument(name = "priority")
                                            Integer priority) throws SQLException {
        Scheduler.defineShare(host, share, connector, path, priority == null ? 0 : priority);
    }

    @Expose
    @Help("Defines the default launcher")
    static public void set_default_launcher(Launcher launcher) {
        Context.get().setDefaultLauncher(launcher);
    }

    @Expose()
    @Help("Get a lock over all the resources defined in a JSON object. When a resource is found, don't try " +
            "to lock the resources below")
    static public ArrayList<Dependency> get_locks(JsonObject json, DependencyParameters... parameters) throws SQLException {
        ArrayList<Dependency> dependencies = new ArrayList<>();

        IdentityHashMap<Object, DependencyParameters> pmap = new IdentityHashMap<>();
        for (DependencyParameters parameter : parameters) {
            pmap.put(parameter.getKey(), parameter);
        }

        get_locks(json, pmap, dependencies);

        return dependencies;
    }

    static private void get_locks(Json json, IdentityHashMap<Object, DependencyParameters> pmap, ArrayList<Dependency> dependencies) throws SQLException {
        if (json instanceof JsonObject) {
            final Resource resource = getResource((JsonObject) json);
            if (resource != null) {
                final Dependency dependency = resource.createDependency(pmap);
                dependencies.add(dependency);
            } else {
                for (Json element : ((JsonObject) json).values()) {
                    get_locks(element, pmap, dependencies);
                }

            }
        } else if (json instanceof JsonArray) {
            for (Json arrayElement : ((JsonArray) json)) {
                get_locks(arrayElement, pmap, dependencies);
            }

        }
    }

    private static Resource getResource(JsonObject json) throws SQLException {
        if (json.containsKey(Constants.XP_RESOURCE.toString())) {
            final Object o = json.get(Constants.XP_RESOURCE.toString()).get();
            if (o instanceof Resource) {
                return (Resource) o;
            } else {
                final String uri = o instanceof JsonString ? o.toString() : (String) o;
                Path path = NetworkShare.uriToPath(uri);
                if (Context.get().simulate()) {
                    final Resource resource = Context.get().getSubmittedJobs().get(uri).resource;
                    if (resource == null) {
                        return Resource.getByLocator(path);
                    }
                    return resource;
                } else {
                    return Resource.getByLocator(path);
                }
            }

        }
        return null;
    }

    @Expose(optional = 1)
    @Help("Set the experiment for all future commands")
    static public void set_experiment(
            @Argument(name = "identifier", help = "Name of the experiment") String identifier,
            @Argument(name = "holdPrevious") Boolean holdPrevious
    ) throws ExperimaestroCannotOverwrite, SQLException {
        final Context context = Context.get();
        if (!context.simulate()) {
            // We first put on hold all the resources belonging to this experiment
            if (holdPrevious == null || holdPrevious) {
                for (Resource resource : Experiment.resourcesByIdentifier(identifier, ResourceState.WAITING_STATES)) {
                    synchronized (resource.getState()) {
                        if (resource.getState().isWaiting()) {
                            resource.setState(ResourceState.ON_HOLD);
                        }
                    }
                }
            }


            Experiment experiment = new Experiment(identifier, System.currentTimeMillis());
            experiment.save();
            context.setExperiment(experiment);
        }
    }

    @Expose
    static public void set_workdir(java.nio.file.Path workdir) throws FileSystemException {
        context().setWorkingDirectory(workdir);
    }

    @Expose
    static public LocalhostConnector get_localhost_connector() {
        return Scheduler.get().getLocalhostConnector();
    }

    @Expose
    static public Object parameters(String key) {
        return Context.get().getParameter(key);
    }


    @Expose
    @Help("Find all tags and return a hash map")
    static public Map<String, JsonSimple> find_tags(Json json) {
        return json.findTags();
    }


    @Expose
    @Help("Returns the notification URL")
    static public String notification_url() {
        return Scheduler.get().getURL() + "/notification";
    }

    @Expose
    static public void cache(@Argument(name = "id") String id,
                             @Argument(name = "key") Json key,
                             @Argument(name = "value") Json value,
                             @Argument(name = "duration") long duration) throws NoSuchAlgorithmException, SQLException, IOException {
        UserCache.store(id, duration, key, value);
    }

    @Expose
    static public Json cache(@Argument(name = "id") String id,
                             @Argument(name = "key") Json key) throws NoSuchAlgorithmException, SQLException, IOException {
        return UserCache.retrieve(id, key);
    }

}
