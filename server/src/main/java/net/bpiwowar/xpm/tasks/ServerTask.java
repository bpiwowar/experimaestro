/**
 *
 */
package net.bpiwowar.xpm.tasks;

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

import bpiwowar.argparser.ArgumentClass;
import bpiwowar.experiments.AbstractTask;
import bpiwowar.experiments.TaskDescription;
import net.bpiwowar.xpm.server.rpc.JsonRPCMethods;
import net.bpiwowar.xpm.server.rpc.JsonRPCServlet;
import org.apache.commons.configuration.HierarchicalINIConfiguration;
import org.apache.commons.configuration.SubnodeConfiguration;
import org.eclipse.jetty.security.AbstractLoginService;
import org.eclipse.jetty.security.ConstraintMapping;
import org.eclipse.jetty.security.ConstraintSecurityHandler;
import org.eclipse.jetty.security.authentication.BasicAuthenticator;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.handler.HandlerList;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.eclipse.jetty.util.security.Constraint;
import org.eclipse.jetty.util.security.Password;
import net.bpiwowar.xpm.scheduler.Scheduler;
import net.bpiwowar.xpm.server.*;
import net.bpiwowar.xpm.utils.log.Logger;

import java.io.File;
import java.net.InetAddress;
import java.net.URL;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Timer;
import java.util.TimerTask;

import static java.lang.String.format;

/**
 * The server displays information about the tasks and responds to XML RPC tasks
 *
 * @author B. Piwowarski <benjamin@bpiwowar.net>
 */
@TaskDescription(name = "server", project = {"xpmanager"})
public class ServerTask extends AbstractTask {
    public static final String KEY_SERVER_SOCKET = "server.socket";
    public static final String XPM_REALM = "xpm-realm";
    final static Logger LOGGER = Logger.getLogger();
    public static final String JSON_RPC_PATH = "/json-rpc";

    @ArgumentClass(prefix = "conf", help = "Configuration file for the XML RPC call")
    HierarchicalINIConfiguration configuration;

    /** The scheduler */
    private Scheduler scheduler;

    /** Should we wait ? */
    boolean wait = true;

    /** The port the web server was started */
    private int port;

    private Server webServer;

    public void setConfiguration(HierarchicalINIConfiguration configuration) {
        this.configuration = configuration;
    }

    public void wait(boolean wait) {
        this.wait = wait;
    }

    /**
     * Server thread
     */
    public int execute() throws Throwable {
        if (configuration == null || configuration.getFile() == null) {
            final String envConfFile = System.getenv("EXPERIMAESTRO_CONFIG_FILE");
            File file = null;
            if (envConfFile != null) {
                file = new File(envConfFile);
                LOGGER.info("Using configuration file set in environment: '" + file + "'");
            } else {
                file = new File(new File(System.getProperty("user.home"), ".experimaestro"), "settings.ini");
                LOGGER.info("Using the default configuration file " + file);
            }
            assert file != null;
            configuration = new HierarchicalINIConfiguration(file);
        }
        LOGGER.info("Reading configuration from " + configuration.getFileName());

        // --- Get the server settings
        ServerSettings serverSettings = new ServerSettings(configuration.subset("server"));

        // --- Get the port
        port = configuration.getInt("server.port", 8080);
        LOGGER.info("Starting server on port %d", port);

        // --- Set up the task manager
        final String property = configuration.getString("server.database");
        if (property == null)
            throw new IllegalArgumentException("No 'database' in 'server' section of the configuration file");

        File taskmanagerDirectory = new File(property);
        scheduler = new Scheduler(taskmanagerDirectory);

        final String baseURL = format("http://%s:%d", InetAddress.getLocalHost().getHostName(), port);
        LOGGER.info("Server URL is %s", baseURL);
        scheduler.setURL(baseURL);

        webServer = new Server();

        // TCP-IP socket
        ServerConnector connector = new ServerConnector(webServer);
        connector.setPort(port);
        webServer.addConnector(connector);

        // Unix domain socket
        if (configuration.containsKey(KEY_SERVER_SOCKET)) {
            // TODO: move this to the target class
            String libraryPath = System.getProperty("org.newsclub.net.unix.library.path");
            if (libraryPath == null) {
                URL url = ServerTask.class.getProtectionDomain().getCodeSource().getLocation();
                File file = new File(url.toURI());
                while (file != null && !new File(file, "native-libs").exists()) {
                    file = file.getParentFile();
                }
                if (file == null)
                    throw new UnsatisfiedLinkError("Cannot find the native-libs directory");
                file = new File(file, "native-libs");

                LOGGER.info("Using path for junixsocket library [%s]", file);
                System.setProperty("org.newsclub.net.unix.library.path", file.getAbsolutePath());
            }

            String socketSpec = configuration.getString(KEY_SERVER_SOCKET);
            UnixSocketConnector unixSocketConnector = new UnixSocketConnector(webServer);
            unixSocketConnector.setSocketFile(new File(socketSpec));
            webServer.addConnector(unixSocketConnector);
        }


        HandlerList collection = new HandlerList();

        // --- Non secure context

        ServletContextHandler nonSecureContext = new ServletContextHandler(collection, "/");
        nonSecureContext.getServletHandler().setEnsureDefaultServlet(false); // no 404 default page
        nonSecureContext.addServlet(new ServletHolder(new NotificationServlet(serverSettings, scheduler)), "/notification/*");

        // --- Sets the password on all pages

        ServletContextHandler context = new ServletContextHandler(collection, "/");
        ConstraintSecurityHandler csh = getSecurityHandler();
        context.setSecurityHandler(csh);

        // --- Add the JSON RPC servlet

        final JsonRPCServlet jsonRpcServlet = new JsonRPCServlet(webServer, serverSettings, scheduler);
        JsonRPCMethods.initMethods();
        final ServletHolder jsonServletHolder = new ServletHolder(jsonRpcServlet);
        context.addServlet(jsonServletHolder, JSON_RPC_PATH);

        // --- Add the web socket servlet

        final XPMWebSocketServlet webSocketServlet = new XPMWebSocketServlet(webServer, scheduler, serverSettings);
        final ServletHolder webSocketServletHolder = new ServletHolder(webSocketServlet);
        context.addServlet(webSocketServletHolder, "/web-socket");


        // --- Add the default servlet

        context.addServlet(new ServletHolder(new ContentServlet(serverSettings)), "/*");

        // final URL warUrl =
        // this.getClass().getClassLoader().getResource("web");
        // final String warUrlString = warUrl.toExternalForm();
        // server.setHandler(new WebAppContext(warUrlString, "/"));

        // --- Sets the main handler
        webServer.setHandler(collection);

        // --- start the server and wait

        webServer.start();

        if (wait) {
            webServer.join();
        }


        LOGGER.info("Servers are stopped. Clean exit!");

        return 0;
    }

    private ConstraintSecurityHandler getSecurityHandler() {
        // -- Security
        // From
        // http://docs.codehaus.org/display/JETTY/How+to+Configure+Security+with+Embedded+Jetty

        Constraint constraint = new Constraint();
        constraint.setName(Constraint.__BASIC_AUTH);
        constraint.setRoles(new String[]{"user"});
        constraint.setAuthenticate(true);

        ConstraintMapping cm = new ConstraintMapping();
        cm.setConstraint(constraint);
        cm.setPathSpec("/*");

        LoginService loginService = new LoginService();
        loginService.setName(XPM_REALM);

        // Read passwords
        final SubnodeConfiguration passwords = configuration.getSection("passwords");
        final Iterator keys = passwords.getKeys();
        while (keys.hasNext()) {
            String user = (String) keys.next();
            final String[] fields = passwords.getString(user).split("\\s*,\\s*", 2);
            final String[] roles = fields[1].split("\\s*,\\s*");

            LOGGER.info("Adding user %s", user);
            loginService.putUser(user, new Password(fields[0]), roles);
        }

        ConstraintSecurityHandler csh = new ConstraintSecurityHandler();
        csh.setRealmName(XPM_REALM);
        csh.setAuthenticator(new BasicAuthenticator());
        csh.setConstraintMappings(new ConstraintMapping[]{cm});
        csh.addConstraintMapping(cm);
        csh.setLoginService(loginService);
        return csh;
    }


    public Scheduler getScheduler() {
        return scheduler;
    }

    public int getPort() {
        return port;
    }

    @Override
    public void close() throws Exception {
        super.close();

        scheduler.close();

        Timer timer = new Timer();
        timer.schedule(new TimerTask() {

            @Override
            public void run() {
                boolean stopped = false;
                try {
                    webServer.stop();
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

    }

    private static class LoginService extends AbstractLoginService {
        HashMap<String, UserPrincipal> users = new HashMap<>();
        HashMap<String, String[]> roles = new HashMap<>();

        @Override
        protected String[] loadRoleInfo(UserPrincipal user) {
            return roles.get(user.getName());
        }

        @Override
        protected UserPrincipal loadUserInfo(String username) {
            return users.get(username);
        }

        public void putUser(String user, Password password, String[] roles) {
            this.roles.put(user, roles);
            this.users.put(user, new UserPrincipal(user, password));
        }
    }
}
