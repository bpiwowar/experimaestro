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

package sf.net.experimaestro.server;

import com.google.common.collect.HashMultimap;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Multimap;
import org.apache.commons.lang.ClassUtils;
import org.apache.commons.lang.NotImplementedException;
import org.apache.log4j.Hierarchy;
import org.apache.log4j.Level;
import org.apache.log4j.PatternLayout;
import org.apache.log4j.WriterAppender;
import org.apache.log4j.spi.RootLogger;
import org.eclipse.jetty.server.Server;
import org.eclipse.wst.jsdt.debug.rhino.debugger.RhinoDebugger;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.JSONValue;
import org.mozilla.javascript.*;
import sf.net.experimaestro.connectors.LocalhostConnector;
import sf.net.experimaestro.exceptions.ContextualException;
import sf.net.experimaestro.exceptions.ExperimaestroRuntimeException;
import sf.net.experimaestro.manager.Repositories;
import sf.net.experimaestro.manager.js.XPMObject;
import sf.net.experimaestro.scheduler.*;
import sf.net.experimaestro.utils.Cleaner;
import sf.net.experimaestro.utils.CloseableIterator;
import sf.net.experimaestro.utils.log.Logger;

import javax.script.ScriptException;
import javax.servlet.http.HttpServlet;
import java.io.*;
import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.*;
import java.util.regex.Pattern;

/**
 * @author B. Piwowarski <benjamin@bpiwowar.net>
 * @date 28/3/13
 */
public class JsonRPCMethods extends HttpServlet {
    final static private Logger LOGGER = Logger.getLogger();
    /**
     * Server
     */
    private Server server;
    private final Scheduler scheduler;
    private final Repositories repository;
    private final JSONRPCRequest mos;

    public JsonRPCMethods(Server server, Scheduler scheduler, Repositories repository, JSONRPCRequest mos) {
        this.server = server;
        this.scheduler = scheduler;
        this.repository = repository;
        this.mos = mos;
    }


    static public interface Arguments {
        public abstract RPCArgument getArgument(int i);

        public abstract Class<?> getType(int i);

        public abstract int size();
    }

    static public class MethodDescription implements Arguments {
        Method method;
        private RPCArgument[] arguments;
        private Class<?>[] types;

        public MethodDescription(Method method) {
            this.method = method;
            types = method.getParameterTypes();
            Annotation[][] annotations = method.getParameterAnnotations();
            arguments = new RPCArgument[annotations.length];
            for (int i = 0; i < annotations.length; i++) {
                types[i] = ClassUtils.primitiveToWrapper(types[i]);
                for (int j = 0; j < annotations[i].length && arguments[i] == null; j++) {
                    if (annotations[i][j] instanceof RPCArgument)
                        arguments[i] = (RPCArgument) annotations[i][j];
                }

                if (arguments[i] == null)
                    throw new ExperimaestroRuntimeException("No annotation for %dth argument of %s", i + 1, method);

            }
        }

        @Override
        public RPCArgument getArgument(int i) {
            return arguments[i];
        }

        @Override
        public Class<?> getType(int i) {
            return types[i];
        }

        @Override
        public int size() {
            return arguments.length;
        }
    }

    private static Multimap<String, MethodDescription> methods = HashMultimap.create();

    static {
        for (Method method : JsonRPCMethods.class.getDeclaredMethods()) {
            final RPCMethod rpcMethod = method.getAnnotation(RPCMethod.class);
            if (rpcMethod != null) {
                methods.put("".equals(rpcMethod.name()) ? method.getName() : rpcMethod.name(), new MethodDescription(method));
            }
        }

    }

    static public class RPCArrayArgument implements RPCArgument {
        @Override
        public String name() {
            return null;
        }

        @Override
        public boolean required() {
            return true;
        }

        @Override
        public String help() {
            return "Array element";
        }

        @Override
        public Class<? extends Annotation> annotationType() {
            return RPCArgument.class;
        }
    }

    private int convert(Object p, Arguments description, int score, Object args[], int index) {
        Object o;
        if (p instanceof JSONObject)
            // If p is a map, then use the json name of the argument
            o = ((JSONObject) p).get(description.getArgument(index).name());
        else if (p instanceof JSONArray)
            // if it is an array, then map it
            o = ((JSONArray) p).get(index);
        else {
            // otherwise, suppose it is a one value array
            if (index > 0)
                return Integer.MIN_VALUE;
            o = p;
        }

        final Class aType = description.getType(index);

        if (o == null) {
            if (description.getArgument(index).required())
                return Integer.MIN_VALUE;

            return score - 10;
        }

        if (aType.isArray()) {
            if (o instanceof JSONArray) {
                final JSONArray array = (JSONArray) o;
                final Object[] arrayObjects = args != null ? new Object[array.size()] : null;
                Arguments arguments = new Arguments() {
                    @Override
                    public RPCArgument getArgument(int i) {
                        return new RPCArrayArgument();
                    }

                    @Override
                    public Class<?> getType(int i) {
                        return aType.getComponentType();
                    }

                    @Override
                    public int size() {
                        return array.size();
                    }
                };
                for (int i = 0; i < array.size() && score > Integer.MIN_VALUE; i++) {
                    score = convert(array.get(i), arguments, score, arrayObjects, i);
                }
                return score;
            }
            return Integer.MIN_VALUE;
        }

        if (aType.isAssignableFrom(o.getClass())) {
            if (args != null)
                args[index] = o;
            return score;
        }

        if (o.getClass() == Long.class && aType == Integer.class) {
            if (args != null)
                args[index] = ((Long) o).intValue();
            return score - 1;
        }

        return Integer.MIN_VALUE;
    }


    public void handle(String message) {
        JSONObject object;
        try {
            Object parse = JSONValue.parse(message);
            object = (JSONObject) parse;

        } catch (Throwable t) {
            LOGGER.warn(t, "Error while handling JSON request");

            try {
                mos.error(null, 1, "Could not parse JSON: " + t.getMessage());
            } catch (IOException e) {
                LOGGER.error(e, "Could not send the error message");
            }
            return;
        }

        handleJSON(object);
    }

    void handleJSON(JSONObject object) {
        String requestID = null;

        try {
            requestID = object.get("id").toString();
            if (requestID == null)
                throw new RuntimeException("No id in JSON request");


            Object command = object.get("method");
            if (command == null)
                throw new RuntimeException("No method in JSON");

            if (!object.containsKey("params"))
                throw new RuntimeException("No params in JSON");
            Object p = object.get("params");

            Collection<MethodDescription> candidates = methods.get(command.toString());
            int max = Integer.MIN_VALUE;
            MethodDescription argmax = null;
            for (MethodDescription candidate : candidates) {
                int score = Integer.MAX_VALUE;
                for (int i = 0; i < candidate.types.length && score > max; i++) {
                    score = convert(p, candidate, score, null, i);
                }
                if (score > max) {
                    max = score;
                    argmax = candidate;
                }
            }

            if (argmax == null)
                throw new ExperimaestroRuntimeException("Cannot find a matching method");

            Object[] args = new Object[argmax.arguments.length];
            for (int i = 0; i < args.length; i++) {
                int score = convert(p, argmax, 0, args, i);
                assert score > Integer.MIN_VALUE;
            }
            Object result = argmax.method.invoke(this, args);
            mos.endMessage(requestID, result);
        } catch (Throwable t) {
            LOGGER.error(t, "Error while handling JSON request");
            try {
                while (t.getCause() != null)
                    t = t.getCause();
                mos.error(requestID, 1, t.getMessage());
            } catch (IOException e) {
                LOGGER.error("Could not send the return code");
            }
            return;
        }
    }


    private EnumSet<ResourceState> getStates(Object[] states) {
        final EnumSet<ResourceState> statesSet;

        if (states == null || states.length == 0)
            statesSet = EnumSet.allOf(ResourceState.class);
        else {
            ResourceState statesArray[] = new ResourceState[states.length];
            for (int i = 0; i < states.length; i++)
                statesArray[i] = ResourceState.valueOf(states[i].toString());
            statesSet = EnumSet.of(statesArray[0], statesArray);
        }
        return statesSet;
    }


    // -------- RPC METHODS -------


    /**
     * Information about a job
     */
    @RPCMethod(help = "Returns detailed information about a job (XML format)")
    public JSONObject getResourceInformation(@RPCArgument(name = "id") String resourceId) throws IOException {
        Resource resource = getResource(resourceId);

        if (resource == null)
            throw new ExperimaestroRuntimeException("No resource with id [%s]", resourceId);


        return resource.toJSON();
    }

    private Resource getResource(String resourceId) {
        Resource resource;
        try {
            long rid = Long.parseLong(resourceId);
            resource = scheduler.getResource(rid);
        } catch (NumberFormatException e) {
            resource = scheduler.getResource(ResourceLocator.parse(resourceId));
        }
        return resource;
    }

    @RPCMethod(help = "Ping (to maintain a WebSocket)")
    public String ping() {
        return "pong";
    }


    @RPCMethod(help = "Sets a log level")
    public int setLogLevel(@RPCArgument(name = "identifier") String identifier, @RPCArgument(name = "level") String level) {
        final Logger logger = Logger.getLogger(identifier);
        logger.setLevel(Level.toLevel(level));
        return 0;
    }

    /**
     * Run javascript
     *
     * @param files
     * @param environment
     */
    @RPCMethod(name = "run-javascript", help = "Run a javascript")
    public String runJSScript(@RPCArgument(name = "files") List<JSONArray> files,
                              @RPCArgument(name = "environment") Map<String, String> environment,
                              @RPCArgument(name = "debug", required = false) Integer debugPort) {

        final StringWriter errString = new StringWriter();
        final PrintWriter err = new PrintWriter(errString);
        XPMObject jsXPM;

        final Hierarchy loggerRepository = getScriptLogger();


        // Creates and enters a Context. The Context stores information
        // about the execution environment of a script.
        RhinoDebugger debugger = null;
        try (Cleaner cleaner = new Cleaner()) {


            // --- Debugging via JSDT
            // http://wiki.eclipse.org/JSDT/Debug/Rhino/Embedding_Rhino_Debugger#Example_Code
            ContextFactory factory = new ContextFactory();

            if (debugPort != null) {
                debugger = new RhinoDebugger("transport=socket,suspend=y,address=" + debugPort);
                debugger.start();
                factory.addListener(debugger);
            }
            // --- End (Debugging via JSDT)

            Context jsContext = factory.enterContext();

            // Initialize the standard objects (Object, Function, etc.)
            // This must be done before scripts can be executed. Returns
            // a scope object that we use in later calls.
            Scriptable scope = jsContext.initStandardObjects();


            // TODO: should be a one shot repository - ugly
            Repositories repositories = new Repositories(new ResourceLocator(LocalhostConnector.getInstance(), ""));
            repositories.add(repository, 0);

            ScriptableObject.defineProperty(scope, "env", new RPCHandler.JSGetEnv(environment), 0);
            jsXPM = new XPMObject(null, jsContext, environment, scope, repositories,
                    scheduler, loggerRepository, cleaner, null);

            Object result = null;
            for (int i = 0; i < files.size(); i++) {
                JSONArray filePointer = files.get(i);

                boolean isFile = filePointer.size() < 2 || filePointer.get(1) == null;
                final String content = isFile ? null : filePointer.get(1).toString();
                final String filename = filePointer.get(0).toString();

                ResourceLocator locator = new ResourceLocator(LocalhostConnector.getInstance(), isFile ? filename : "/");
                jsXPM.setLocator(locator);
                LOGGER.info("Script locator is %s", locator);

                if (isFile)
                    result = jsContext.evaluateReader(scope, new FileReader(filename), filename, 1, null);
                else
                    result = jsContext.evaluateString(scope, content, filename, 1, null);

            }

            if (result != null)
                LOGGER.debug("Returns %s", result.toString());
            else
                LOGGER.debug("Null result");

            return result != null && result != Scriptable.NOT_FOUND &&
                    result != Undefined.instance ? result.toString() : "";

        } catch (Throwable e) {
            Throwable wrapped = e;
            LOGGER.info("Exception thrown there: %s", e.getStackTrace()[0]);
            while (wrapped.getCause() != null)
                wrapped = wrapped.getCause();

            LOGGER.printException(Level.INFO, wrapped);

            err.println(wrapped.toString());

            for (Throwable ee = e; ee != null; ee = ee.getCause()) {
                if (ee instanceof ContextualException) {
                    ContextualException ce = (ContextualException) ee;
                    List<String> context = ce.getContext();
                    if (!context.isEmpty()) {
                        err.format("%n[context]%n");
                        for (String s : ce.getContext()) {
                            err.format("%s%n", s);
                        }
                    }
                }
            }

            if (wrapped instanceof NotImplementedException)
                err.format("Line where the exception was thrown: %s", wrapped.getStackTrace()[0]);

            // Search for innermost rhino exception
            RhinoException rhinoException = null;
            for (Throwable t = e; t != null; t = t.getCause())
                if (t instanceof RhinoException)
                    rhinoException = (RhinoException) t;

            if (rhinoException != null)
                err.append("\n" + rhinoException.getScriptStackTrace());

            // TODO: We should have something better
//            if (wrapped instanceof RuntimeException && !(wrapped instanceof RhinoException)) {
//                err.format("Internal error:%n");
//                e.printStackTrace(err);
//            }

            err.flush();
            throw new RuntimeException(errString.toString());

        } finally {
            // Stop debugger
            if (debugger != null)
                try {
                    debugger.stop();
                } catch (Exception e) {
                    LOGGER.error(e);
                }
            // Exit context
            Context.exit();
        }
    }

    private Hierarchy getScriptLogger() {
        final RootLogger root = new RootLogger(Level.INFO);
        final Hierarchy loggerRepository = new Hierarchy(root);
        BufferedWriter stringWriter = getRequestErrorStream();

        PatternLayout layout = new PatternLayout("%-6p [%c] %m%n");
        WriterAppender appender = new WriterAppender(layout, stringWriter);
        root.addAppender(appender);
        return loggerRepository;
    }


    /**
     * Return the output stream for the request
     */
    private BufferedWriter getRequestOutputStream() {
        return getRequestStream("out");
    }

    /**
     * Return the error stream for the request
     */
    private BufferedWriter getRequestErrorStream() {
        return getRequestStream("err");
    }

    HashMap<String, BufferedWriter> writers = new HashMap<>();

    /**
     * Return a stream with the given ID
     */
    private BufferedWriter getRequestStream(final String id) {
        BufferedWriter bufferedWriter = writers.get(id);
        if (bufferedWriter == null) {
            bufferedWriter = new BufferedWriter(new Writer() {
                @Override
                public void write(char[] cbuf, int off, int len) throws IOException {
                    ImmutableMap<String, String> map = ImmutableMap.of("stream", id, "value", new String(cbuf, off, len));
                    mos.message(map);
                }

                @Override
                public void flush() throws IOException {
                }

                @Override
                public void close() throws IOException {
                    throw new UnsupportedOperationException();
                }
            });
            writers.put(id, bufferedWriter);
        }
        return bufferedWriter;
    }

    /**
     * Shutdown the server
     */
    @RPCMethod(help = "Shutdown Experimaestro server")
    public boolean shutdown() {
        // Close the scheduler
        scheduler.close();

        // Shutdown jetty (after 1s to allow this thread to finish)
        Timer timer = new Timer();
        timer.schedule(new TimerTask() {

            @Override
            public void run() {
                boolean stopped = false;
                try {
                    server.stop();
                    stopped = true;
                } catch (Exception e) {
                    LOGGER.error(e, "Could not stop properly jetty");
                }
                if (!stopped)
                    synchronized (this) {
                        try {
                            wait(10000);
                        } catch (InterruptedException e) {
                            LOGGER.error(e);
                        }
                        System.exit(1);

                    }
            }
        }, 2000);

        // Everything's OK
        return true;
    }


    // Restart all the job (recursion)
    private int invalidate(Resource resource) throws Exception {
        final Collection<Dependency> deps = scheduler.getDependentResources(resource.getId());

        if (deps.isEmpty())
            return 0;

        int nbUpdated = 0;

        for (Dependency dependency : deps) {
            final long to = dependency.getTo();
            LOGGER.info("Invalidating %s", to);
            Resource child = scheduler.getResource(to);

            invalidate(child);

            final ResourceState state = child.getState();
            if (state == ResourceState.RUNNING)
                ((Job) child).stop();
            if (!state.isActive()) {
                nbUpdated++;
                // We invalidate grand-children if the child was done
                if (state == ResourceState.DONE) invalidate(child);
                ((Job) child).restart();
            }
        }
        return nbUpdated;
    }

    @RPCMethod(help = "Puts back a job into the waiting queue")
    public int restart(
            @RPCArgument(name = "id", help = "The id of the job (string or integer)") String id,
            @RPCArgument(name = "restart-done", help = "Whether done jobs should be invalidated") boolean restartDone,
            @RPCArgument(name = "recursive", help = "Whether we should invalidate dependent results when the job was done") boolean recursive
    ) throws Exception {

        int nbUpdated = 0;
        Resource resource = getResource(id);
        if (resource == null)
            throw new ExperimaestroRuntimeException("Job not found [%s]", id);

        final ResourceState rsrcState = resource.getState();

        if (rsrcState == ResourceState.RUNNING)
            throw new ExperimaestroRuntimeException("Job is running [%s]", rsrcState);

        // The job is active, so we have nothing to do
        if (rsrcState.isActive())
            return 0;

        if (!restartDone && rsrcState == ResourceState.DONE)
            return 0;

        ((Job) resource).restart();
        nbUpdated++;

        // If the job was done, we need to restart the dependences
        if (recursive && rsrcState == ResourceState.DONE) {
            nbUpdated += invalidate(resource);
        }

        return nbUpdated;
    }

    /**
     * Remove resources specified with the given filter
     *
     * @param group       The group of the resource (or none if no filter)
     * @param id          The URI of the resource to delete
     * @param statesNames The states of the resource to delete
     */
    @RPCMethod(name = "remove", help = "Remove jobs")
    public int remove(@RPCArgument(name = "group", required = false) String group,
                      @RPCArgument(name = "id", required = false) String id,
                      @RPCArgument(name = "regexp", required = false) Boolean _idIsRegexp,
                      @RPCArgument(name = "states", required = false) String[] statesNames,
                      @RPCArgument(name = "recursive", required = false) Boolean _recursive
    ) throws Exception {
        int n = 0;
        EnumSet<ResourceState> states = getStates(statesNames);
        boolean recursive = _recursive != null ? _recursive : false;

        Pattern idPattern = _idIsRegexp != null && _idIsRegexp ?
                Pattern.compile(id) : null;

        if (id != null && !id.equals("") && idPattern == null) {
            final Resource resource = getResource(id);
            if (resource == null)
                throw new ExperimaestroRuntimeException("Job not found [%s]", id);

            if (group != null && !resource.getGroup().startsWith(group))
                throw new ExperimaestroRuntimeException("Resource [%s] group [%s] does not match [%s]",
                        resource, resource.getGroup(), group);

            if (!states.contains(resource.getState()))
                throw new ExperimaestroRuntimeException("Resource [%s] state [%s] not in [%s]",
                        resource, resource.getState(), states);
            scheduler.delete(resource, recursive);
            n = 1;
        } else {
            // TODO order the tasks so that depencies are removed first
            HashSet<Resource> toRemove = new HashSet<>();
            try (final CloseableIterator<Resource> resources = scheduler.getResources().entities(group, true, states, true)) {
                while (resources.hasNext()) {
                    Resource resource = resources.next();
                    if (idPattern != null) {
                        if (!idPattern.matcher(resource.getIdentifier()).matches())
                            continue;
                    }
                    try {
                        toRemove.add(resource);
                    } catch (Exception e) {
                        // TODO should output this to the caller
                    }
                    n++;
                }
            }

            for (Resource resource : toRemove)
                scheduler.delete(resource, recursive);

        }
        return n;
    }


    HashSet<Listener> listeners = new HashSet<>();

    @RPCMethod(help = "Listen to XPM events")
    public void listen() {
        Listener listener = new Listener() {
            @Override
            public void notify(Message message) {
                try {
                    HashMap<String, Object> map = new HashMap<>();
                    map.put("event", message.getType().toString());
                    if (message instanceof SimpleMessage) {
                        map.put("resource", ((SimpleMessage) message).getResource().getId());
                        ResourceLocator locator = ((SimpleMessage) message).getResource().getLocator();
                        if (locator != null)
                            map.put("locator", locator.toString());
                    }

                    switch (message.getType()) {
                        case STATE_CHANGED:
                            map.put("state", ((SimpleMessage) message).getResource().getState().toString());
                            break;

                        case RESOURCE_REMOVED:
                            break;

                        case RESOURCE_ADDED:
                            map.put("state", ((SimpleMessage) message).getResource().getState().toString());
                            break;
                    }

                    mos.message(map);
                } catch (IOException e) {
                    LOGGER.error(e, "Could not output");
                } catch (RuntimeException e) {
                    LOGGER.error(e, "Error while trying to notify RPC client");
                }
            }
        };

        listeners.add(listener);
        scheduler.addListener(listener);
    }

    public void close() {
        for (Listener listener : listeners)
            scheduler.removeListener(listener);
    }

}