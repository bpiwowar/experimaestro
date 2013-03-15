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

/**
 *
 */
package sf.net.experimaestro.tasks;

import bpiwowar.argparser.ArgumentClass;
import bpiwowar.experiments.AbstractTask;
import bpiwowar.experiments.TaskDescription;
import org.apache.commons.configuration.HierarchicalINIConfiguration;
import org.apache.commons.configuration.SubnodeConfiguration;
import org.apache.xmlrpc.webserver.XmlRpcServlet;
import org.mortbay.jetty.Server;
import org.mortbay.jetty.security.Constraint;
import org.mortbay.jetty.security.ConstraintMapping;
import org.mortbay.jetty.security.HashUserRealm;
import org.mortbay.jetty.security.Password;
import org.mortbay.jetty.security.SecurityHandler;
import org.mortbay.jetty.servlet.Context;
import org.mortbay.jetty.servlet.ServletHolder;
import org.mortbay.thread.ThreadPool;
import sf.net.experimaestro.connectors.XPMConnector;
import sf.net.experimaestro.manager.Repositories;
import sf.net.experimaestro.scheduler.ResourceLocator;
import sf.net.experimaestro.scheduler.Scheduler;
import sf.net.experimaestro.server.StreamServer;
import sf.net.experimaestro.server.ContentServlet;
import sf.net.experimaestro.server.JSHelpServlet;
import sf.net.experimaestro.server.StatusServlet;
import sf.net.experimaestro.server.TasksServlet;
import sf.net.experimaestro.server.XPMXMLRpcServlet;
import sf.net.experimaestro.utils.log.Logger;

import java.io.File;
import java.net.URL;
import java.util.Iterator;

/**
 * The server displays information about the tasks and responds to XML RPC tasks
 *
 * @author B. Piwowarski <benjamin@bpiwowar.net>
 */
@TaskDescription(name = "server", project = {"xpmanager"})
public class ServerTask extends AbstractTask {
    final static Logger LOGGER = Logger.getLogger();
    public static final String KEY_SERVER_SOCKET = "server.socket";

    @ArgumentClass(prefix = "conf", help = "Configuration file for the XML RPC call")
    HierarchicalINIConfiguration configuration;

    /**
     * Server thread
     */
    public int execute() throws Throwable {
        if (configuration == null || configuration.getFile() == null) {
            final File file = new File(System.getProperty("user.home"), ".experimaestro");
            LOGGER.info("Using the default configuration file " + file);
            configuration = new HierarchicalINIConfiguration(file);
        }
        LOGGER.info("Reading configuration from " + configuration.getFileName());


        // --- Get the port
        int port = configuration.getInt("server.port", 8080);
        LOGGER.info("Starting server on port %d", port);

        // --- Set up the task manager
        final String property = configuration.getString("server.database");
        if (property == null)
            throw new IllegalArgumentException("No 'database' in 'server' section of the configuration file");

        File taskmanagerDirectory = new File(property);
        final Scheduler scheduler = new Scheduler(taskmanagerDirectory);

        // Main repository
        final Repositories repositories = new Repositories(new ResourceLocator(XPMConnector.getInstance(), ""));

        Server webServer = new Server(port);
//        server.addConnector(new UnixSocketConnector());

        Context context = new Context(webServer, "/");

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

        SecurityHandler sh = new SecurityHandler();
        String passwordProperty = configuration.getString("passwords");
        final HashUserRealm userRealm;
        if (passwordProperty != null) {
            File passwordFile = new File(passwordProperty);
            userRealm = new HashUserRealm("xpm-realm", passwordFile
                    .getAbsolutePath());
        } else
            userRealm = new HashUserRealm("xpm-realm");

        // Read passwords
        final SubnodeConfiguration passwords = configuration.getSection("passwords");
        final Iterator keys = passwords.getKeys();
        while (keys.hasNext()) {
            String user = (String) keys.next();
            final String[] fields = passwords.getString(user).split("\\s*,\\s*");

            LOGGER.info("Adding user %s", user);
            userRealm.put(user, new Password(fields[0]));
            for (int i = 1; i < fields.length; i++)
                userRealm.addUserToRole(user, fields[i]);
        }


        sh.setUserRealm(userRealm);
        sh.setConstraintMappings(new ConstraintMapping[]{cm});
        context.addHandler(sh);

        // --- Add the XML RPC servlet

        final XmlRpcServlet xmlRpcServlet = new XPMXMLRpcServlet(webServer,
                repositories, scheduler);

        xmlRpcServlet.init(new XPMXMLRpcServlet.Config(xmlRpcServlet));

        final ServletHolder servletHolder = new ServletHolder(xmlRpcServlet);
        context.addServlet(servletHolder, "/xmlrpc");

        // --- Add the status servlet

        context.addServlet(new ServletHolder(new StatusServlet(scheduler)),
                "/status/*");

        // --- Add the status servlet

        context.addServlet(new ServletHolder(new TasksServlet(repositories,
                scheduler)), "/tasks/*");


        // --- Add the JS Help servlet

        context.addServlet(new ServletHolder(new JSHelpServlet()), "/jshelp/*");


        // --- Add the default servlet

        context.addServlet(new ServletHolder(new ContentServlet()), "/*");

        // final URL warUrl =
        // this.getClass().getClassLoader().getResource("web");
        // final String warUrlString = warUrl.toExternalForm();
        // server.setHandler(new WebAppContext(warUrlString, "/"));

        // --- start the server

        webServer.start();
        ThreadPool threadPool = webServer.getThreadPool();

        // --- Socket
        if (configuration.containsKey(KEY_SERVER_SOCKET)) {
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
            StreamServer streamServer = new StreamServer(threadPool, new File(socketSpec), repositories, scheduler);
            streamServer.start();
        }

        // --- Wait for servers to close
        threadPool.join();


        LOGGER.info("Servers are stopped. Clean exit!");

        return 0;
    }


}
