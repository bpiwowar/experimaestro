package sf.net.experimaestro.manager.js;

import static java.lang.String.format;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.Map;

import javax.xml.namespace.QName;

import org.mozilla.javascript.Context;
import org.mozilla.javascript.NativeArray;
import org.mozilla.javascript.NativeJavaObject;
import org.mozilla.javascript.NativeObject;
import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.ScriptableObject;
import org.w3c.dom.Node;

import sf.net.experimaestro.locks.LockType;
import sf.net.experimaestro.manager.TaskFactory;
import sf.net.experimaestro.manager.TaskRepository;
import sf.net.experimaestro.scheduler.CommandLineTask;
import sf.net.experimaestro.scheduler.LockMode;
import sf.net.experimaestro.scheduler.Resource;
import sf.net.experimaestro.scheduler.SimpleData;
import sf.net.experimaestro.scheduler.TaskManager;
import sf.net.experimaestro.utils.JSUtils;
import sf.net.experimaestro.utils.XMLUtils;
import sf.net.experimaestro.utils.log.Logger;

/**
 * This class contains both utility static methods and functions that can be
 * called from javascript
 * 
 * @author B. Piwowarski <benjamin@bpiwowar.net>
 */
public class XPMObject {

	private static final String EXPERIMAESTRO_NS = "http://experimaestro.sf.net";

	final static private Logger LOGGER = Logger.getLogger();

	/**
	 * The environment variable that contains the script path
	 */
	public static final String ENV_SCRIPTPATH = "XPM_JS_SCRIPTPATH";

	/**
	 * The experiment repository
	 */
	private final TaskRepository repository;

	/**
	 * Our scope (global among javascripts)
	 */
	private final Scriptable scope;

	/**
	 * The context (local)
	 */
	private Context context;

	private final TaskManager manager;

	private final Map<String, String> environment;

	private static ThreadLocal<ArrayList<String>> log = new ThreadLocal<ArrayList<String>>() {
		protected synchronized ArrayList<String> initialValue() {
			return new ArrayList<String>();
		}
	};

	public XPMObject(Context cx, Map<String, String> environment,
			Scriptable scope, TaskRepository repository, TaskManager manager)
			throws IllegalAccessException, InstantiationException,
			InvocationTargetException {
		this.context = cx;
		this.environment = environment;
		this.scope = scope;
		this.repository = repository;
		this.manager = manager;

		ScriptableObject.defineClass(scope, TaskFactoryJSWrapper.class);
		ScriptableObject.defineClass(scope, TaskJSWrapper.class);
	}

	/**
	 * Add an experiment
	 * 
	 * @param object
	 */
	public void addTaskFactory(NativeObject object) {
		repository.register(new JSTaskFactory(context, scope, object));
	}

	/**
	 * Add an experiment
	 * 
	 * @param object
	 */
	public void addJointTaskFactory(NativeObject object) {
		repository.register(new JSJointTaskFactory(repository, context, scope,
				object));
	}

	/**
	 * Get the information about a given task
	 * 
	 * @param id
	 * @return
	 */
	public Scriptable getExperiment(String namespace, String id) {
		TaskFactory factory = repository.get(new QName(namespace, id));
		LOGGER.info("Information %s", factory);
		return context.newObject(scope, "XPMTaskFactory",
				new Object[] { Context.javaToJS(factory, scope) });
	}

	/**
	 * Returns the script path if available
	 */
	public String getScriptPath() {
		return environment.get(ENV_SCRIPTPATH);
	}

	/**
	 * Run a command line experiment
	 * 
	 * @param jsargs
	 *            a native array
	 * @param a
	 *            E4X object
	 * @return
	 */
	public void addCommandLineJob(String identifier, Object jsargs,
			Object jsresources) {
		// --- Process arguments: convert the javascript array into a Java array
		// of String
		LOGGER.debug("Adding command line job");
		final String[] args;
		if (jsargs instanceof NativeArray) {
			NativeArray array = ((NativeArray) jsargs);
			int length = (int) array.getLength();
			args = new String[length];
			for (int i = 0; i < length; i++) {
				Object el = array.get(i, array);
				args[i] = toString(el);
				LOGGER.debug("arg %d: [%s] %s", i, el.getClass(), args[i]);
			}
		} else
			throw new RuntimeException(format(
					"Cannot handle an array of type %s", jsargs.getClass()));

		CommandLineTask task = new CommandLineTask(manager, identifier, args);

		// --- Resources
		NativeArray resources = ((NativeArray) jsresources);
		for (int i = (int) resources.getLength(); --i >= 0;) {
			NativeArray array = (NativeArray) resources.get(i, resources);
			assert array.getLength() == 2;
			Resource resource = manager.getResource(toString(array
					.get(0, array)));
			LockType lockType = LockType.valueOf(toString(array.get(1, array)));
			LOGGER.debug("Adding dependency on [%s] of tyep [%s]", resource, lockType);
			task.addDependency(resource, lockType);
		}

		// --- Add it
		manager.add(task);
	}

	static private String toString(Object object) {
		if (object instanceof NativeJavaObject)
			return ((NativeJavaObject) object).unwrap().toString();
		return object.toString();
	}

	public String addData(String identifier) {
		LockMode mode = LockMode.SINGLE_WRITER;
		SimpleData resource = new SimpleData(manager, identifier, mode, false);
		manager.add(resource);
		return identifier;
	}

	/**
	 * Simple evaluation of shell commands
	 * 
	 * @return
	 * @throws IOException
	 * @throws InterruptedException
	 */
	public NativeArray evaluate(Object jsargs) throws IOException,
			InterruptedException {
		final String[] args;
		if (jsargs instanceof NativeArray) {
			NativeArray array = ((NativeArray) jsargs);
			int length = (int) array.getLength();
			args = new String[length];
			for (int i = 0; i < length; i++) {
				Object el = array.get(i, array);
				if (el instanceof NativeJavaObject)
					el = ((NativeJavaObject) el).unwrap();
				LOGGER.debug("arg %d: %s/%s", i, el, el.getClass());
				args[i] = el.toString();
			}
		} else
			throw new RuntimeException(format(
					"Cannot handle an array of type %s", jsargs.getClass()));

		// Run the process and captures the output
		Process p = Runtime.getRuntime().exec(args);
		BufferedReader input = new BufferedReader(new InputStreamReader(
				p.getInputStream()));

		int len = 0;
		char[] buffer = new char[8192];
		StringBuffer sb = new StringBuffer();
		while ((len = input.read(buffer, 0, buffer.length)) >= 0)
			sb.append(buffer, 0, len);
		input.close();

		int error = p.waitFor();
		return new NativeArray(new Object[] { error, sb.toString() });
	}

	/**
	 * Log a message to be returned to the client
	 */
	public void log(String format, Object... objects) {
		String msg = format(format, objects);
		log.get().add(msg);
		LOGGER.debug(msg);
	}

	/**
	 * Get the log for the current thread
	 * 
	 * @return
	 */
	static public ArrayList<String> getLog() {
		return log.get();
	}

	/**
	 * Get a QName
	 */
	public QName qName(String namespaceURI, String localPart) {
		return new QName(namespaceURI, localPart);
	}

	/**
	 * Get experimaestro namespace
	 */
	public String ns() {
		return EXPERIMAESTRO_NS;
	}

	// XML Utilities

	public Scriptable domToE4X(Node node) {
		return JSUtils.domToE4X(node, context, scope);
	}

	public String xmlToString(Node node) {
		return XMLUtils.toString(node);
	}

	public static void resetLog() {
		log.set(new ArrayList<String>());
	}

	public File filepath(File file, String... names) {
		for (String name : names)
			file = new File(file, name);
		return file;
	}

}
